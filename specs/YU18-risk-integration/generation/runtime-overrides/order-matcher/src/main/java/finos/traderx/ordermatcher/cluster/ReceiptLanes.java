package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Function;
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
 */
final class ReceiptLanes<R extends ReceiptLanes.Record> {
    interface Encoder<R> { byte[] encode(StringBuilder sb, R r); }

    /** A bridged record; {@code leader} is the member's role when the record was applied. */
    abstract static class Record { boolean leader; }

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
    private final OneToOneConcurrentArrayQueue<R>[] queues;
    private final AtomicLongArray published, rejected, acked, failed;
    private final Thread[] threads;
    private final Connection[] connections;
    private final int[] replyPrefix;
    private final Catchup<R> catchup;
    private final int retain;
    private final AtomicLong replayed = new AtomicLong();
    private volatile boolean leader = true; // a caller that never sets a role keeps leader-only publishing
    private volatile boolean running = true;

    @SuppressWarnings("unchecked")
    ReceiptLanes(final String name, final String url, final String[] subjects, final int capacityPerLane,
                 final Encoder<R> encoder, final ToIntFunction<R> account) {
        this(name, url, subjects, capacityPerLane, encoder, account, null, null);
    }

    @SuppressWarnings("unchecked")
    ReceiptLanes(final String name, final String url, final String[] subjects, final int capacityPerLane,
                 final Encoder<R> encoder, final ToIntFunction<R> account, final Function<R, String> key,
                 final Catchup<R> catchup) {
        this.key = key;
        this.catchup = catchup;
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
        this.threads = new Thread[n];
        this.connections = new Connection[n];
        this.replyPrefix = new int[n];
    }

    int lanes() { return subjects.length; }

    /** Apply thread: the member's role, from onStart and every onRoleChange. */
    void role(final boolean isLeader) { leader = isLeader; }

    /** Apply thread: queue the record on its account's lane, tagged with the role it was applied in.
     *  False = that lane is full (caller counts). */
    boolean offer(final R r) {
        r.leader = leader;
        return queues[BridgePartitions.index(account.applyAsInt(r), subjects.length)].offer(r);
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
        Connection nc = null;
        while (running && nc == null) {
            try {
                // noNoResponders: a reply-subject publish to a subject nobody subscribes (core feed,
                // projection down) would otherwise draw a 503 per message; only the stream's
                // replies are wanted here.
                nc = Nats.connect(new Options.Builder().server(url).noNoResponders()
                    .maxMessagesInOutgoingQueue(1 << 20).build());
            } catch (final Exception e) {
                try { Thread.sleep(1000); } catch (final InterruptedException ie) { return; }
            }
        }
        if (nc == null) { return; }
        connections[lane] = nc;
        final String inbox = nc.createInbox();
        replyPrefix[lane] = inbox.length() + 1;   // set before the dispatcher thread exists
        final Dispatcher receipts = nc.createDispatcher(m -> onReceipt(lane, m));
        receipts.setPendingLimits(-1, -1);
        receipts.subscribe(key == null ? inbox : inbox + ".>");
        final String subject = subjects[lane];
        final OneToOneConcurrentArrayQueue<R> q = queues[lane];
        final StringBuilder sb = new StringBuilder(512);
        final ArrayDeque<R> kept = new ArrayDeque<>();
        long evicted = 0;
        boolean leading = false;
        java.util.function.ToIntFunction<R> cut = null; // non-null until a record past the cut is published
        while (running) {
            final R r = q.poll();
            if (r == null) {
                if (!leading && leader) { // promoted with nothing new applied yet: publish what is kept now
                    leading = true;
                    cut = promote(lane, nc, inbox, sb, kept, evicted);
                    evicted = 0;
                }
                flushRejectedIds(lane, false);
                try { Thread.sleep(1); } catch (final InterruptedException e) { return; }
                continue;
            }
            if (!r.leader) {
                leading = false;
                if (catchup != null) {
                    if (kept.size() == retain) { kept.pollFirst(); evicted++; }
                    kept.addLast(r);
                }
                continue;
            }
            if (!leading) {
                leading = true;
                cut = promote(lane, nc, inbox, sb, kept, evicted);
                evicted = 0;
            }
            if (cut != null) {
                final int c = cut.applyAsInt(r);
                if (c <= 0) { if (c == 0) { cut = null; } continue; } // stored by the previous leader
                cut = null;
            }
            publish(lane, nc, inbox, sb, r);
        }
    }

    private void publish(final int lane, final Connection nc, final String inbox, final StringBuilder sb, final R r) {
        try {
            nc.publish(subjects[lane], key == null ? inbox : inbox + "." + key.apply(r), encoder.encode(sb, r));
            published.incrementAndGet(lane);
        } catch (final Exception e) {
            final long n = failed.incrementAndGet(lane);
            if (n == 1 || n % 10_000 == 0) {
                System.out.println("WARN " + name + "-nats-bridge partition " + lane + " (" + subjects[lane]
                    + ") publish failed: failed=" + n + " " + e);
            }
        }
    }

    /**
     * The lane's member became leader: publish the kept records the stream does not hold, in order, and return
     * the cut still to apply to the records queued after them (null = publish everything from here).
     */
    private java.util.function.ToIntFunction<R> promote(final int lane, final Connection nc, final String inbox,
                                                         final StringBuilder sb, final ArrayDeque<R> kept,
                                                         final long evicted) {
        if (catchup == null) { kept.clear(); return null; }
        final String subject = subjects[lane];
        byte[] last = null;
        String outcome = null;
        for (int attempt = 0; attempt < 10 && outcome == null && running; attempt++) {
            try {
                final JetStreamManagement jsm = nc.jetStreamManagement();
                final List<String> streams = jsm.getStreamNames(subject);
                if (streams.isEmpty()) {
                    outcome = "no stream captures the subject";
                } else {
                    try {
                        last = jsm.getLastMessage(streams.get(0), subject).getData();
                        outcome = "after the stream's last";
                    } catch (final JetStreamApiException e) {
                        if (e.getApiErrorCode() != 10037) { throw e; } // 10037: no message found
                        outcome = "subject empty";
                    }
                }
            } catch (final Exception e) {
                try { Thread.sleep(500); } catch (final InterruptedException ie) { return null; }
            }
        }
        java.util.function.ToIntFunction<R> cut = null;
        final boolean comparable = last == null ? "subject empty".equals(outcome) : (cut = catchup.cut(last)) != null;
        if (!comparable) {
            System.out.println((kept.isEmpty() ? "INFO " : "ALERT ") + name + "-nats-bridge partition " + lane + " (" + subject + ") promoted:"
                + " no catch-up (" + (outcome == null ? "stream unreachable" : last == null ? outcome
                : "last message carries no position") + "); " + kept.size() + " record(s) applied while following"
                + " are not republished");
            kept.clear();
            return null;
        }
        long sent = 0, skipped = 0;
        // Evicted records were unpublished if the oldest kept one is already past the cut (or there is no cut).
        boolean gap = evicted > 0 && (cut == null || (!kept.isEmpty() && cut.applyAsInt(kept.peekFirst()) > 0));
        for (R r; (r = kept.pollFirst()) != null; ) {
            if (cut != null) {
                final int c = cut.applyAsInt(r);
                if (c <= 0) { skipped++; if (c == 0) { cut = null; } continue; }
                cut = null;
            }
            publish(lane, nc, inbox, sb, r);
            sent++;
        }
        replayed.addAndGet(sent);
        System.out.println((gap ? "ALERT " : "INFO ") + name + "-nats-bridge partition " + lane + " (" + subject
            + ") promoted: republished " + sent + " record(s) " + outcome + ", skipped " + skipped + " already stored"
            + (gap ? "; " + evicted + " older record(s) were evicted unpublished - the stream is missing them" : ""));
        return cut;
    }

    /** A stream reply: PubAck JSON, or a 429 status (also an "error" JSON body) when ingest dropped it. */
    void onReceipt(final int lane, final Message m) {
        final boolean tooMany = (m.getStatus() != null && m.getStatus().getCode() == 429) || isError(m.getData());
        if (!tooMany) {
            acked.incrementAndGet(lane);
            return;
        }
        final long n = rejected.incrementAndGet(lane);
        if (key != null && m.getSubject() != null && m.getSubject().length() > replyPrefix[lane]) {
            synchronized (rejectedIds[lane]) {
                rejectedIds[lane].append(rejectedIds[lane].length() == 0 ? "" : " ").append(m.getSubject().substring(replyPrefix[lane]));
            }
            if (n % ID_BATCH == 0) { flushRejectedIds(lane, true); }
        }
        if (n == 1 || n % 10_000 == 0) {
            System.out.println("ALERT " + name + "-nats-bridge partition " + lane + " (" + subjects[lane]
                + "): NATS dropped " + n + " message(s) at stream ingest (429 Too Many Requests); the projection"
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
            + ") NATS-rejected ids: " + ids);
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
    long rejected(final int lane) { return rejected.get(lane); }
    /** Records published at a promotion that the previous leader applied but the stream did not hold. */
    long replayed() { return replayed.get(); }

    /** {"published":..,"acked":..,"rejected":..,"failed":..,"rejectedByPartition":[..]} for /health. */
    String json() {
        final StringBuilder b = new StringBuilder("{\"published\":").append(published()).append(",\"acked\":").append(acked())
            .append(",\"rejected\":").append(rejected()).append(",\"failed\":").append(failed())
            .append(",\"replayed\":").append(replayed()).append(",\"rejectedByPartition\":[");
        for (int i = 0; i < subjects.length; i++) { b.append(i == 0 ? "" : ",").append(rejected.get(i)); }
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
