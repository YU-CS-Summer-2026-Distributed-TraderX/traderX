package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.MessageInfo;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * YU18 (docs/engineering/downstream-at-400k.md §7.9): the bridges' publish path, one lane per
 * partition, LOSS-COUNTING. A core publish to a subject a JetStream stream captures is dropped
 * silently when the stream's ingest queue is full; the server's only per-message signal is a
 * {@code 429 Too Many Requests} reply, sent only when the message carries a reply subject. So each
 * lane publishes with its own reply inbox and counts the replies on one async subscription: an ack
 * means the stream stored it, a 429 means NATS dropped it. The publisher never waits for a reply.
 *
 * <p>Threading: the apply thread calls {@link #offer} (non-blocking, no allocation beyond the
 * caller's record); each lane has one SPSC queue, one publisher thread and one connection, so the
 * order of a partition's messages is the order the apply thread produced them.
 *
 * <p>Alarm, not retry: a 429'd message is not resent. Its partition's later messages may already be
 * stored, so a resend would land AFTER them - a reordering the projection would book (average cost
 * is order-dependent). Order-preserving recovery is a replay from the source (the Aeron archive), so
 * the lane makes the loss loud and exact: per-partition counters on /health and /metrics and a log
 * line naming the partition. With a {@code key} (the trade bridge: its trade id) each message's reply
 * subject is {@code <inbox>.<key>}, so a 429 names the exact message and the lane logs the rejected ids
 * in batches - the same ids the projection's recon reports MISSING.
 *
 * <p>Failover catch-up (FR-DRS-37): only the leader publishes, so a record the dying leader applied but did not
 * get stored - in its queue, on the wire, or committed only once the new leader's term began - was sent by no
 * one. Every member therefore offers every record, tagged with its role when applied. A lane keeps the records
 * applied while following (bounded: the last {@code capacityPerLane}) and, when its member becomes leader, reads
 * the last message the stream stored on its subject and publishes the kept records after it before anything new.
 * Records are in apply order on every member, so the stream stays in apply order and each record is stored once.
 *
 * <p>NATS restart catch-up (FR-DRS-38): a core publish is not confirmed, so what was in flight when NATS died (in its
 * buffers, its ingest queue, not yet stored) is gone and no member resends it. The lane therefore keeps its last
 * {@code capacityPerLane} records in every role, owns its reconnect (jnats' own would replay its buffer to the new
 * server, racing the stream read), and on every new connection while leading runs the same catch-up as a promotion.
 * A connection is dropped and replaced when NATS closes it, a publish throws, or no receipt arrives for 5 s with
 * publishes outstanding. Past the ring: if the newest record the lane no longer keeps is after the stream's last, the
 * stream is missing records nobody can resend - the catch-up counts a gap ({@code gaps} on /health), logs an ALERT
 * and still publishes what it kept, in order. A record the lane's full queue refused never reaches the ring, so it is
 * a gap the same way: at the next catch-up if it is after the stream's last, or at once while leading.
 *
 * <p>Warm-up (FR-DRS-39): the queue overflows when the lane publishes slower than the apply thread offers, and a lane
 * whose encode-and-publish path the JIT has not compiled yet does: a member's first load, or a follower promoted under
 * load, which has only ever kept records. So each lane first sends {@link #WARM_PUBLISHES} sample records through
 * that path to a scratch subject no stream captures.
 *
 * <p>Batching (FR-DRS-41): a lane publishes everything its drain holds, up to {@code BRIDGE_BATCH_MAX} records (default
 * {@value #BATCH_DEFAULT}, at most {@value #BATCH_CAP}) and {@value #BATCH_BYTES} bytes, as ONE message, the records in
 * apply order separated by a newline. One NATS message and one receipt per drain instead of per record: at a quiet
 * rate a drain holds one record and the wire is as before; under load it fills, which is exactly when the server's
 * per-message cost is the wall (one server stores ~400K messages/s of this wire whatever their size up to ~1 KB,
 * downstream-at-400k.md §7.13 and the 2026-11-09 local run). A message is stored or 429'd whole, so a drop is
 * still exact: the reply subject carries the record count and the first and last key, {@code <inbox>.<n>.<first>~<last>},
 * and the counters stay in records. The catch-up compares the LAST record of the stream's last message. Readers
 * split a message on the newline before their per-record parse; {@code BRIDGE_BATCH_MAX=1} is the one-record wire.
 */
final class ReceiptLanes<R extends ReceiptLanes.Record> {
    interface Encoder<R> { byte[] encode(StringBuilder sb, R r); }

    /** A bridged record; {@code leader} is the member's role when the record was applied, {@code sent} whether this
     *  lane has published it on some connection. */
    abstract static class Record { boolean leader, sent; }

    /** Where a record sits against the stream's last stored message (the cut), in apply order. */
    interface Catchup<R> {
        /** For the stored message's bytes: r -> negative (at or before the cut), 0 (the cut), positive (after
         *  it). Null when the message carries no position this bridge can compare - nothing is replayed. */
        java.util.function.ToIntFunction<R> cut(byte[] lastStored);
    }

    private final String name;
    private final String url;
    private final String[] subjects;
    private final Encoder<R> encoder;
    private final ToIntFunction<R> account;
    private final Function<R, String> key;
    private final StringBuilder[] rejectedIds;
    private final long[] rejectedFlushedAt;
    private static final int ID_BATCH = 500;
    /** Publishes outstanding and no receipt for this long: the connection is presumed dead (FR-DRS-38). */
    private static final long STALL_NANOS = 5_000_000_000L;
    /** FR-DRS-39: the scratch subject prefix and how many samples a lane sends on it before its first record. */
    static final String WARM_SUBJECT = "_bridge.warm";
    static final int WARM_PUBLISHES = 20_000;
    /** FR-DRS-41: records per message. The byte cap keeps a message far under NATS's 1 MiB payload. */
    static final int BATCH_DEFAULT = 64, BATCH_CAP = 1024, BATCH_BYTES = 256 * 1024;
    volatile int batchMax = batchMaxFromEnv();
    private final OneToOneConcurrentArrayQueue<R>[] queues;
    private final AtomicLongArray published, rejected, acked, failed, messages;
    private final Thread[] threads;
    private final Connection[] connections;
    private final int[] replyPrefix;
    private final Catchup<R> catchup;
    private final IntFunction<R> sample; // FR-DRS-39: the k-th warm-up record; null = no warm-up
    private final int retain;
    private final AtomicLong replayed = new AtomicLong();
    private final AtomicLong gaps = new AtomicLong();
    private final AtomicReferenceArray<R> lastDropped; // per lane: the newest record its full queue refused
    private volatile boolean leader = true; // a caller that never sets a role keeps leader-only publishing
    private volatile boolean running = true;

    @SuppressWarnings("unchecked")
    ReceiptLanes(final String name, final String url, final String[] subjects, final int capacityPerLane,
                 final Encoder<R> encoder, final ToIntFunction<R> account, final Function<R, String> key,
                 final Catchup<R> catchup, final IntFunction<R> sample) {
        this.key = key;
        this.catchup = catchup;
        this.sample = sample;
        this.retain = capacityPerLane;
        this.rejectedIds = new StringBuilder[subjects.length];
        this.rejectedFlushedAt = new long[subjects.length];
        for (int i = 0; i < subjects.length; i++) { rejectedIds[i] = new StringBuilder(); }
        this.name = name;
        this.url = url;
        this.subjects = subjects;
        this.encoder = encoder;
        this.account = account;
        final int n = subjects.length;
        this.queues = new OneToOneConcurrentArrayQueue[n];
        for (int i = 0; i < n; i++) { queues[i] = new OneToOneConcurrentArrayQueue<>(capacityPerLane); }
        this.published = new AtomicLongArray(n);
        this.rejected = new AtomicLongArray(n);
        this.acked = new AtomicLongArray(n);
        this.failed = new AtomicLongArray(n);
        this.messages = new AtomicLongArray(n);
        this.threads = new Thread[n];
        this.connections = new Connection[n];
        this.replyPrefix = new int[n];
        this.lastDropped = new AtomicReferenceArray<>(n);
    }

    int lanes() { return subjects.length; }

    /** {@code BRIDGE_BATCH_MAX}: unset, blank or unparsable is the default; clamped to 1..{@value #BATCH_CAP}. */
    static int batchMaxFromEnv() {
        final String v = System.getenv("BRIDGE_BATCH_MAX");
        if (v == null || v.isBlank()) { return BATCH_DEFAULT; }
        try { return Math.max(1, Math.min(BATCH_CAP, Integer.parseInt(v.trim()))); } catch (final NumberFormatException e) { return BATCH_DEFAULT; }
    }

    /** A stored message's records (FR-DRS-41: one per line, in apply order); a one-record message is itself, uncopied. */
    static List<byte[]> records(final byte[] msg) {
        int nl = -1;
        for (int i = 0; i < msg.length; i++) { if (msg[i] == '\n') { nl = i; break; } }
        if (nl < 0) { return List.of(msg); }
        final List<byte[]> out = new java.util.ArrayList<>();
        int start = 0;
        for (int i = nl; i <= msg.length; i++) {
            if (i == msg.length || msg[i] == '\n') {
                if (i > start) { out.add(java.util.Arrays.copyOfRange(msg, start, i)); }
                start = i + 1;
            }
        }
        return out;
    }

    /** The last record of a stored message (FR-DRS-41: one record per line); a one-record message is itself. */
    static byte[] lastRecord(final byte[] msg) {
        int end = msg.length;
        while (end > 0 && msg[end - 1] == '\n') { end--; }
        int start = end;
        while (start > 0 && msg[start - 1] != '\n') { start--; }
        return start == 0 && end == msg.length ? msg : java.util.Arrays.copyOfRange(msg, start, end);
    }

    /** Apply thread: the member's role, from onStart and every onRoleChange. */
    void role(final boolean isLeader) { leader = isLeader; }

    /** Apply thread: queue the record on its account's lane, tagged with the role it was applied in.
     *  False = that lane is full (caller counts). */
    boolean offer(final R r) {
        r.leader = leader;
        final int lane = BridgePartitions.index(account.applyAsInt(r), subjects.length);
        if (queues[lane].offer(r)) { return true; }
        lastDropped.set(lane, r); // never reaches the ring: the lane counts the hole (FR-DRS-38)
        return false;
    }

    /** Verifier/test seam: the next queued record on any lane, or null. */
    R poll() {
        for (final OneToOneConcurrentArrayQueue<R> q : queues) {
            final R r = q.poll();
            if (r != null) { return r; }
        }
        return null;
    }

    void start() {
        for (int i = 0; i < subjects.length; i++) {
            final int lane = i;
            threads[i] = new Thread(() -> run(lane), name + "-nats-bridge-p" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
    }

    private void run(final int lane) {
        final Lane l = new Lane(lane);
        final OneToOneConcurrentArrayQueue<R> q = queues[lane];
        long n = 0;
        if (!l.connect()) { return; }
        l.warm();
        while (running) {
            if (l.nc == null && !l.connect()) { return; }
            // FR-DRS-41: take what the queue holds, up to one message's worth, before publishing: under load that is
            // the drain one message carries; at a quiet rate it is one record.
            int polled = 0;
            for (R r; polled < batchMax && (r = q.poll()) != null; polled++) {
                l.append(r);
                if (!r.leader) { l.leading = false; }
                else if (!l.leading) { l.leading = true; l.caughtUp = false; }
            }
            if (polled == 0 && !l.leading && leader) { // promoted with nothing new applied yet
                l.leading = true;
                l.caughtUp = false;
            }
            if (l.leading && !l.caughtUp) { l.catchUp(); }
            if (l.leading && l.caughtUp) {
                l.pump();
                if (lastDropped.get(lane) != null && lastDropped.getAndSet(lane, null) != null) {
                    gaps.incrementAndGet();
                    System.out.println("ALERT " + name + "-nats-bridge partition " + lane + " (" + subjects[lane]
                        + ") its full queue refused record(s) while leading: they were never published - the stream"
                        + " is missing them (replay from the archive to repair)");
                }
            }
            final long before = n;
            n += polled;
            if (polled == 0 || before >> 10 != n >> 10) { l.check(); }   // idle, or every 1024 records
            if (polled == 0) {
                flushRejectedIds(lane, false);
                try { Thread.sleep(1); } catch (final InterruptedException e) { break; }
            }
        }
        l.close();
    }

    /**
     * One partition's publisher state, owned by its lane thread. {@code ring} holds the last {@code retain} records
     * applied, in apply order and in every role; {@code next} is the next one to publish. A catch-up rewinds
     * {@code next} to the oldest kept record and sets {@code cut} from the stream, so the publish loop skips what the
     * stream already holds and sends the rest - the same path for a promotion and for a new NATS connection.
     */
    private final class Lane {
        final int i;
        final String subject;
        @SuppressWarnings("unchecked")
        final R[] ring = (R[]) new Record[retain];
        final StringBuilder sb = new StringBuilder(512);
        byte[] buf = new byte[BATCH_BYTES + 4096];   // the message being built: records separated by '\n'
        int len, count;                              // bytes and records in buf
        String firstKey, lastKey;                    // the reply subject names the message's first and last key
        long head, next;        // records appended; index of the next to publish
        R lastEvicted;          // the newest record the ring no longer keeps
        ToIntFunction<R> cut;   // non-null until a record past the stream's last is published
        boolean leading, caughtUp;
        boolean expectReceipts; // the catch-up found a stream, so every publish draws a receipt
        Connection nc;
        String inbox;
        AtomicLong receipts;    // this connection's receipts
        volatile long lastReceipt;
        long sentOnConn, quietSince;

        Lane(final int i) { this.i = i; this.subject = subjects[i]; }

        boolean connect() {
            while (running && nc == null) {
                try {
                    // noNoResponders: a reply-subject publish to a subject nobody subscribes (core feed,
                    // projection down) would otherwise draw a 503 per message; only the stream's
                    // replies are wanted here. noReconnect (FR-DRS-38): jnats would resend its buffer to a
                    // restarted server, racing the catch-up; a new connection starts from the stream instead.
                    nc = Nats.connect(new Options.Builder().server(url).noNoResponders().noReconnect()
                        .maxMessagesInOutgoingQueue(1 << 20).build());
                } catch (final Exception e) {
                    try { Thread.sleep(1000); } catch (final InterruptedException ie) { return false; }
                }
            }
            if (nc == null) { return false; }
            connections[i] = nc;
            inbox = nc.createInbox();
            replyPrefix[i] = inbox.length() + 1;   // set before the dispatcher thread exists
            final AtomicLong counted = receipts = new AtomicLong();
            final Dispatcher d = nc.createDispatcher(m -> {
                counted.incrementAndGet();
                lastReceipt = System.nanoTime();
                onReceipt(i, m);
            });
            d.setPendingLimits(-1, -1);
            d.subscribe(inbox + ".>");
            sentOnConn = 0;
            quietSince = System.nanoTime();
            caughtUp = false;
            return true;
        }

        /** Drop the connection; the run loop connects again and, if leading, catches up from the stream. */
        void reconnect(final String why) {
            System.out.println("WARN " + name + "-nats-bridge partition " + i + " (" + subject + ") " + why
                + "; reconnecting, then publishing what the stream is missing");
            close();
        }

        void close() {
            if (nc != null) { try { nc.close(); } catch (final Exception ignore) { } }
            nc = null;
            caughtUp = false;
        }

        void append(final R r) {
            if (head >= retain) {
                final long oldest = head - retain;
                lastEvicted = ring[(int) (oldest % retain)];
                if (next <= oldest) { next = oldest + 1; }
            }
            ring[(int) (head % retain)] = r;
            head++;
        }

        /**
         * Publish what the ring holds from {@code next}: each pass encodes up to {@code batchMax} records (and
         * {@value #BATCH_BYTES} bytes) into one message (FR-DRS-41). A failed publish leaves {@code next} where the
         * message began; the reconnect's catch-up then decides what the stream still needs.
         */
        void pump() {
            final int max = batchMax;
            while (next < head && nc != null) {
                final long from = next;
                long first = next;      // the first record in the message (the cut's skips precede it)
                reset();
                while (next < head && count < max) {
                    final R r = ring[(int) (next % retain)];
                    if (cut != null) {
                        final int c = cut.applyAsInt(r);
                        if (c <= 0) { next++; if (c == 0) { cut = null; } continue; } // the stream holds it
                        cut = null;
                    }
                    if (count == 0) { first = next; }
                    if (!add(r)) { break; }   // the byte cap: this record starts the next message
                    next++;
                }
                if (count == 0) { continue; }
                if (!publish(subject)) { next = from; return; }
                for (long k = first; k < next; k++) {
                    final R r = ring[(int) (k % retain)];
                    if (r.sent || !r.leader) { replayed.incrementAndGet(); }
                    r.sent = true;
                }
            }
        }

        void reset() { len = 0; count = 0; firstKey = null; lastKey = null; }

        /** Encode r onto the message being built; false when it does not fit behind what is there (never when first). */
        boolean add(final R r) {
            final byte[] rec = encoder.encode(sb, r);
            if (count > 0 && len + 1 + rec.length > BATCH_BYTES) { return false; }
            if (len + 1 + rec.length > buf.length) { buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, len + 1 + rec.length)); }
            if (count > 0) { buf[len++] = '\n'; }
            System.arraycopy(rec, 0, buf, len, rec.length);
            len += rec.length;
            if (key != null) { final String k = key.apply(r); if (count == 0) { firstKey = k; } lastKey = k; }
            count++;
            return true;
        }

        /** The reply subject: the record count, then (keyed bridges) the first and last key, so a 429 names them. */
        String reply() {
            if (key == null) { return inbox + "." + count; }
            return inbox + "." + count + "." + (count == 1 ? firstKey : firstKey + "~" + lastKey);
        }

        /** The one encode-and-publish path, for real records and warm-up samples alike: one record, one message. */
        void send(final String to, final R r) {
            reset();
            add(r);
            nc.publish(to, reply(), java.util.Arrays.copyOf(buf, len));
        }

        /**
         * FR-DRS-39: run the publish path on samples before the first record, so it is compiled when load arrives.
         * Interpreted, a lane publishes about half of what the apply thread offers at 400K orders/s and its queue
         * overflows within seconds. The scratch subject has no stream and no subscriber: NATS discards the samples.
         */
        void warm() {
            if (sample == null) { return; }
            final String scratch = WARM_SUBJECT + "." + name;
            try {
                for (int k = 0; k < WARM_PUBLISHES && running; k++) { send(scratch, sample.apply(k)); }
            } catch (final Exception e) {
                System.out.println("WARN " + name + "-nats-bridge partition " + i + " (" + subject
                    + ") warm-up stopped early: " + e);
            }
        }

        /** Publish the message built in buf; counts its records as published. */
        boolean publish(final String to) {
            try {
                nc.publish(to, reply(), java.util.Arrays.copyOf(buf, len));
            } catch (final Exception e) {
                final long n = failed.incrementAndGet(i);
                if (n == 1 || n % 10_000 == 0) {
                    System.out.println("WARN " + name + "-nats-bridge partition " + i + " (" + subject
                        + ") publish failed: failed=" + n + " " + e);
                }
                reconnect("publish failed");
                return false;
            }
            published.addAndGet(i, count);
            messages.incrementAndGet(i);
            sentOnConn++;
            return true;
        }

        /** Idle (or every 1024 records): a closed connection, or receipts stalled with publishes outstanding. */
        void check() {
            if (nc == null) { return; }
            if (nc.getStatus() != Connection.Status.CONNECTED) { reconnect("connection " + nc.getStatus()); return; }
            if (!expectReceipts) { return; }
            final long now = System.nanoTime();
            final long outstanding = sentOnConn - receipts.get();
            if (outstanding <= 0) { quietSince = now; return; }
            if (now - Math.max(lastReceipt, quietSince) > STALL_NANOS) {
                reconnect("no receipt for " + STALL_NANOS / 1_000_000_000L + " s with " + outstanding + " outstanding");
            }
        }

        /**
         * Read the stream's last message on the subject, then rewind {@code next} to the oldest kept record with the
         * cut set, so {@link #pump} publishes exactly what follows it. A failed read on a JetStream server drops the
         * connection and is retried on the next one; the ring keeps absorbing records meanwhile.
         */
        void catchUp() {
            byte[] last = null;
            String outcome = null;
            for (int attempt = 0; attempt < 10 && outcome == null && running && nc != null; attempt++) {
                try {
                    if (!nc.getServerInfo().isJetStreamAvailable()) { outcome = "no JetStream"; break; }
                    final JetStreamManagement jsm = nc.jetStreamManagement();
                    final List<String> streams = jsm.getStreamNames(subject);
                    if (streams.isEmpty()) {
                        outcome = "no stream captures the subject";
                    } else {
                        try {
                            // Settle: the server may still be storing what an earlier connection sent.
                            MessageInfo m = jsm.getLastMessage(streams.get(0), subject);
                            for (int s = 0; s < 10; s++) {
                                Thread.sleep(200);
                                final MessageInfo again = jsm.getLastMessage(streams.get(0), subject);
                                if (again.getSeq() == m.getSeq()) { break; }
                                m = again;
                            }
                            last = lastRecord(m.getData());
                            outcome = "after the stream's last";
                        } catch (final JetStreamApiException e) {
                            if (e.getApiErrorCode() != 10037) { throw e; } // 10037: no message found
                            outcome = "subject empty";
                        }
                    }
                } catch (final InterruptedException e) {
                    return;
                } catch (final Exception e) {
                    try { Thread.sleep(500); } catch (final InterruptedException ie) { return; }
                }
            }
            if (outcome == null) {
                if (nc != null) { reconnect("could not read the stream's last message"); }
                return;
            }
            final long oldest = Math.max(0, head - retain);
            ToIntFunction<R> c = last == null ? null : catchup.cut(last);
            final boolean comparable = last == null ? "subject empty".equals(outcome) : c != null;
            expectReceipts = !"no JetStream".equals(outcome) && !"no stream captures the subject".equals(outcome);
            caughtUp = true;
            next = oldest;
            if (!comparable) {
                // No position to compare: publish from the first record never sent while leading, as before the
                // catch-up existed; what was in flight or applied while following may be missing.
                long followed = 0, inFlight = 0;
                for (long k = oldest; k < head; k++) {
                    final R r = ring[(int) (k % retain)];
                    if (!r.leader) { followed++; } else if (r.sent) { inFlight++; }
                }
                cut = r -> r.leader && !r.sent ? 1 : -1;
                // FR-DRS-40: a stream is there but its last message has no position (a build before FR-DRS-40, or
                // not a bridge's message): whether it holds what this lane kept cannot be told, so it is a gap.
                final boolean gap = last != null && followed + inFlight != 0;
                if (gap) { gaps.incrementAndGet(); }
                System.out.println((gap || followed != 0 ? "ALERT " : "INFO ") + name + "-nats-bridge partition " + i
                    + " (" + subject + ") no catch-up (" + (last == null ? outcome : "last message carries no position")
                    + "); " + followed + " record(s) applied while following are not republished"
                    + (gap ? ", " + inFlight + " sent earlier may not be stored (replay from the archive to repair)" : ""));
                return;
            }
            long republish = 0, skipped = 0;
            ToIntFunction<R> scan = c;
            for (long k = oldest; k < head; k++) {
                final R r = ring[(int) (k % retain)];
                if (scan != null) {
                    final int at = scan.applyAsInt(r);
                    if (at <= 0) { skipped++; if (at == 0) { scan = null; } continue; }
                    scan = null;
                }
                republish++;
            }
            cut = c;
            // A record the full queue refused never reached the ring: after the stream's last, it is a hole too.
            final R refused = lastDropped.getAndSet(i, null);
            final boolean gap = (lastEvicted != null && (c == null || c.applyAsInt(lastEvicted) > 0))
                || (refused != null && (c == null || c.applyAsInt(refused) > 0));
            if (gap) { gaps.incrementAndGet(); }
            System.out.println((gap ? "ALERT " : "INFO ") + name + "-nats-bridge partition " + i + " (" + subject
                + ") catching up: publishing " + republish + " record(s) " + outcome + ", skipped " + skipped
                + " already stored" + (gap ? "; records this lane no longer keeps (older than its " + retain
                + ", or refused by its full queue) were never stored - the stream is missing them (replay from the"
                + " archive to repair)" : ""));
        }
    }

    /** A stream reply: PubAck JSON, or a 429 status (also an "error" JSON body) when ingest dropped it. The reply
     *  subject's first token past the inbox is the message's record count (FR-DRS-41), then its first~last key. */
    void onReceipt(final int lane, final Message m) {
        final String subj = m.getSubject();
        final String tail = subj != null && subj.length() > replyPrefix[lane] ? subj.substring(replyPrefix[lane]) : "";
        final int dot = tail.indexOf('.');
        int n = 1;
        try { n = Math.max(1, Integer.parseInt(dot < 0 ? tail : tail.substring(0, dot))); } catch (final NumberFormatException ignore) { }
        final boolean tooMany = (m.getStatus() != null && m.getStatus().getCode() == 429) || isError(m.getData());
        if (!tooMany) {
            acked.addAndGet(lane, n);
            return;
        }
        final long before = rejected.getAndAdd(lane, n), after = before + n;
        if (key != null && dot >= 0) {
            synchronized (rejectedIds[lane]) {
                rejectedIds[lane].append(rejectedIds[lane].length() == 0 ? "" : " ").append(tail, dot + 1, tail.length());
            }
            if (before / ID_BATCH != after / ID_BATCH) { flushRejectedIds(lane, true); }
        }
        if (before == 0 || before / 10_000 != after / 10_000) {
            System.out.println("ALERT " + name + "-nats-bridge partition " + lane + " (" + subjects[lane]
                + "): NATS dropped " + after + " record(s) at stream ingest (429 Too Many Requests); the projection"
                + " is missing them - recon will name them MISSING; replay from the archive to repair");
        }
    }

    /** Log the ids rejected since the last flush: every ID_BATCH, or once idle for a second. */
    private void flushRejectedIds(final int lane, final boolean now) {
        if (key == null) { return; }
        final String ids;
        synchronized (rejectedIds[lane]) {
            if (rejectedIds[lane].length() == 0) { return; }
            if (!now && System.nanoTime() - rejectedFlushedAt[lane] < 1_000_000_000L) { return; }
            ids = rejectedIds[lane].toString();
            rejectedIds[lane].setLength(0);
            rejectedFlushedAt[lane] = System.nanoTime();
        }
        System.out.println("ALERT " + name + "-nats-bridge partition " + lane + " (" + subjects[lane]
            + ") NATS-rejected ids (first~last of each message): " + ids);
    }

    /** A top-level-unique JSON field's raw value (a string without its quotes, or a number), or null. */
    static String field(final byte[] json, final String name) {
        final String s = new String(json, StandardCharsets.UTF_8);
        int i = s.indexOf("\"" + name + "\":");
        if (i < 0) { return null; }
        i += name.length() + 3;
        if (i < s.length() && s.charAt(i) == '"') { final int end = s.indexOf('"', i + 1); return end < 0 ? null : s.substring(i + 1, end); }
        int end = i;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '-')) { end++; }
        return end == i ? null : s.substring(i, end);
    }

    private static boolean isError(final byte[] data) {
        if (data == null || data.length == 0) { return false; }
        return new String(data, StandardCharsets.UTF_8).contains("\"error\"");
    }

    long published() { return sum(published); }
    long rejected() { return sum(rejected); }
    long acked() { return sum(acked); }
    long failed() { return sum(failed); }
    /** NATS messages published (FR-DRS-41: each holds one or more records). */
    long messages() { return sum(messages); }
    long rejected(final int lane) { return rejected.get(lane); }
    /** Records published at a promotion that the previous leader applied but the stream did not hold. */
    long replayed() { return replayed.get(); }
    /** Catch-ups that found records the stream is missing and the lane no longer kept (FR-DRS-38). */
    long gaps() { return gaps.get(); }

    /** {"published":..,"messages":..,"acked":..,"rejected":..,"failed":..,"replayed":..,"gaps":..,"rejectedByPartition":[..],
     *  "queuedByPartition":[..]} for /health; every count but messages is in records. */
    String json() {
        final StringBuilder b = new StringBuilder("{\"published\":").append(published()).append(",\"messages\":").append(messages())
            .append(",\"acked\":").append(acked())
            .append(",\"rejected\":").append(rejected()).append(",\"failed\":").append(failed())
            .append(",\"replayed\":").append(replayed()).append(",\"gaps\":").append(gaps()).append(",\"rejectedByPartition\":[");
        for (int i = 0; i < subjects.length; i++) { b.append(i == 0 ? "" : ",").append(rejected.get(i)); }
        b.append("],\"queuedByPartition\":[");
        for (int i = 0; i < subjects.length; i++) { b.append(i == 0 ? "" : ",").append(queues[i].size()); }
        return b.append("]}").toString();
    }

    private static long sum(final AtomicLongArray a) {
        long s = 0;
        for (int i = 0; i < a.length(); i++) { s += a.get(i); }
        return s;
    }

    void stop() {
        running = false;
        for (final Thread t : threads) { if (t != null) { t.interrupt(); } }
        for (final Connection c : connections) {
            if (c != null) { try { c.close(); } catch (final Exception ignore) { } }
        }
    }
}
