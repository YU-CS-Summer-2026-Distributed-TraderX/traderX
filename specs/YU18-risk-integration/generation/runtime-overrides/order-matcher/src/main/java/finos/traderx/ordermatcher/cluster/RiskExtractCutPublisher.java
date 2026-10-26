package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.Nats;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leader-side position-cut → NATS bridge (YU15, ADR-055) — the same shape ADR-048 established for
 * booked trades, for the same reason: the deterministic apply thread renders the cut (it is the
 * only thread allowed to read engine state) and hands it to a daemon thread, which does the I/O.
 * The state machine never blocks on NATS, not even for a batch job.
 *
 * <p>Why NATS and not cluster egress: egress is best-effort and reaches only the submitting session
 * (ADR-048), so a truncated cut would arrive looking complete.
 *
 * <p>Only the leader publishes (role check at the call site) so followers never duplicate — but
 * every member renders and hashes the cut, which is what makes cross-member byte-identity provable.
 *
 * <p>YU18 (EOD-RISK item 2): the cut travels in PARTS. One message capped it at the server's
 * max_payload (1 MB default; ~30K positions at 34 bytes a row), and a cut over it was refused by the client, so the
 * extract simply never arrived. Each part is {@link #PART_PREFIX} + one header line naming the
 * cut's sequence, its part number and count, its total byte length and its SHA-256, then a slice of
 * the cut's bytes. {@link Assembly} accepts only a complete set whose concatenation has exactly that
 * length and that hash, so a missing, duplicated-different or truncated part is a refusal, never a
 * short extract — and the {@code rows=} self-count inside the cut still backs that up. Parts are
 * sized from the server's own max_payload, so no manifest has to raise it.
 */
final class RiskExtractCutPublisher {

    static final String PART_PREFIX = "#cutpart ";
    private static final int HEADER_ROOM = 256;

    private record Cut(long seq, byte[] bytes, String sha256) { }

    private final String url;
    private final String subject;
    private final int partBytesOverride;
    private final OneToOneConcurrentArrayQueue<Cut> queue = new OneToOneConcurrentArrayQueue<>(8);
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;
    private Thread thread;
    private volatile Connection nats;

    RiskExtractCutPublisher(final String url, final String subject) {
        this(url, subject, Integer.parseInt(System.getenv().getOrDefault("RISK_EXTRACT_PART_BYTES", "0")));
    }

    /** {@code partBytes} > 0 forces that part size (tests); 0 sizes parts from the server's max_payload. */
    RiskExtractCutPublisher(final String url, final String subject, final int partBytes) {
        this.url = url;
        this.subject = subject;
        this.partBytesOverride = partBytes;
    }

    void start() {
        thread = new Thread(this::run, "risk-extract-cut-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    /** Service (apply) thread — non-blocking, never throws. */
    void offer(final long seq, final byte[] cut, final String sha256) {
        if (!queue.offer(new Cut(seq, cut, sha256))) {
            dropped.incrementAndGet();
        }
    }

    private void run() {
        while (running && nats == null) {
            try {
                nats = Nats.connect(url); // the client owns reconnection from here
            } catch (final Exception e) {
                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException ie) {
                    return;
                }
            }
        }
        while (running) {
            final Cut cut = queue.poll();
            if (cut == null) {
                try {
                    Thread.sleep(5);
                } catch (final InterruptedException e) {
                    return;
                }
                continue;
            }
            try {
                final long max = nats.getServerInfo().getMaxPayload();
                final int partBytes = partBytesOverride > 0 ? partBytesOverride
                    : (int) Math.max(1024, Math.min(Integer.MAX_VALUE, max) - HEADER_ROOM);
                for (final byte[] part : parts(cut.seq, cut.bytes, cut.sha256, partBytes)) {
                    nats.publish(subject, part);
                }
                nats.flush(java.time.Duration.ofSeconds(30));
                published.incrementAndGet();
                System.out.println("RISK-EXTRACT-CUT-PUBLISHED seq=" + cut.seq + " bytes=" + cut.bytes.length
                    + " parts=" + partCount(cut.bytes.length, partBytes));
            } catch (final Exception e) {
                dropped.incrementAndGet();
                System.err.println("RISK-EXTRACT-CUT-DROPPED seq=" + cut.seq + " " + e);
            }
        }
    }

    static int partCount(final int bytes, final int partBytes) {
        return Math.max(1, (bytes + partBytes - 1) / partBytes);
    }

    /** The wire messages for one cut, in order. */
    static java.util.List<byte[]> parts(final long seq, final byte[] cut, final String sha256, final int partBytes) {
        final int count = partCount(cut.length, partBytes);
        final java.util.List<byte[]> out = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int from = i * partBytes;
            final int len = Math.min(partBytes, cut.length - from);
            final byte[] head = (PART_PREFIX + "seq=" + seq + " part=" + (i + 1) + " parts=" + count
                + " bytes=" + cut.length + " sha256=" + sha256 + "\n").getBytes(StandardCharsets.US_ASCII);
            final byte[] msg = new byte[head.length + len];
            System.arraycopy(head, 0, msg, 0, head.length);
            System.arraycopy(cut, from, msg, head.length, len);
            out.add(msg);
        }
        return out;
    }

    /**
     * Consumer side: collects the parts of the cut at one sequence and yields it only when the set is
     * complete and its length and SHA-256 match what every part declared. Parts of other sequences
     * are ignored (a stale redelivery of yesterday's cut is not this cut). A pre-YU18 member sends the
     * whole cut as one {@code #cut } message; that is accepted as is, and is self-counted by
     * {@code rows=} exactly as before.
     */
    static final class Assembly {
        private final long seq;
        private byte[][] parts;
        private int have;
        private long bytes = -1;
        private String sha;

        Assembly(final long seq) {
            this.seq = seq;
        }

        /** @return the whole cut once complete, else null. @throws IllegalStateException on any inconsistency. */
        String accept(final byte[] msg) {
            final String probe = new String(msg, 0, Math.min(msg.length, 512), StandardCharsets.US_ASCII);
            if (probe.startsWith("#cut ")) {
                return probe.contains("seq=" + seq + " ") ? new String(msg, StandardCharsets.US_ASCII) : null;
            }
            if (!probe.startsWith(PART_PREFIX)) {
                return null;
            }
            final int nl = probe.indexOf('\n');
            if (nl < 0) {
                throw new IllegalStateException("risk extract: cut part has no header line");
            }
            final String head = probe.substring(0, nl);
            if (Long.parseLong(field(head, "seq")) != seq) {
                return null;
            }
            final int part = Integer.parseInt(field(head, "part"));
            final int count = Integer.parseInt(field(head, "parts"));
            final long total = Long.parseLong(field(head, "bytes"));
            final String hash = field(head, "sha256");
            if (parts == null) {
                if (count < 1 || total < 0 || total > Integer.MAX_VALUE) {
                    throw new IllegalStateException("risk extract: cut part header out of range: " + head);
                }
                parts = new byte[count][];
                bytes = total;
                sha = hash;
            } else if (count != parts.length || total != bytes || !hash.equals(sha)) {
                throw new IllegalStateException("risk extract: cut parts at seq " + seq + " disagree: " + head);
            }
            if (part < 1 || part > count) {
                throw new IllegalStateException("risk extract: cut part number out of range: " + head);
            }
            final byte[] body = java.util.Arrays.copyOfRange(msg, nl + 1, msg.length);
            if (parts[part - 1] != null) {
                if (!java.util.Arrays.equals(parts[part - 1], body)) {
                    throw new IllegalStateException("risk extract: cut part " + part + " arrived twice, different");
                }
                return null;
            }
            parts[part - 1] = body;
            if (++have < count) {
                return null;
            }
            final ByteArrayOutputStream whole = new ByteArrayOutputStream((int) bytes);
            for (final byte[] p : parts) {
                whole.writeBytes(p);
            }
            final byte[] cut = whole.toByteArray();
            if (cut.length != bytes || !RiskExtractCut.sha256(cut).equals(sha)) {
                throw new IllegalStateException("risk extract: reassembled cut at seq " + seq + " is " + cut.length
                    + " bytes / sha256 " + RiskExtractCut.sha256(cut) + ", declared " + bytes + " / " + sha);
            }
            return new String(cut, StandardCharsets.US_ASCII);
        }

        private static String field(final String head, final String key) {
            for (final String token : head.split(" ")) {
                if (token.startsWith(key + "=")) {
                    return token.substring(key.length() + 1);
                }
            }
            throw new IllegalStateException("risk extract: cut part header lacks " + key + ": " + head);
        }
    }

    long published() {
        return published.get();
    }

    long dropped() {
        return dropped.get();
    }

    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        final Connection c = nats;
        if (c != null) {
            try {
                c.close();
            } catch (final Exception ignore) {
                // shutting down
            }
        }
    }
}
