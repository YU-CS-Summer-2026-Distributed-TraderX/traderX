package finos.traderx.ordermatcher.cluster;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;

/**
 * FR-DRS-57 (ADR-106): one lane's overflow on the member's disk. A lane that falls more than half its ring behind the
 * stream has the ring's older half copied here before the apply thread overwrites it, so what the stream has not
 * stored outlives the ring. Records keep their ring index: the spool is the contiguous run {@code [start, end)}, in
 * apply order, in segment files of {@code segmentRecords} each.
 *
 * <p>Threads: the bridge's spooler thread appends, trims and clears; the lane thread reads. The segment list is
 * guarded by this object's lock, file reads happen outside it. The lane only reads at or above its own {@code low},
 * and the spooler only deletes below a {@code low} the lane has published, so a file is never deleted under a read.
 *
 * <p>Process-lifetime only: written to the page cache, never fsynced, wiped at start. All of a member's spools share
 * one byte budget ({@code BRIDGE_SPOOL_BYTES}); past it {@link #append} refuses and the caller freezes the spool.
 */
final class LaneSpool<R extends ReceiptLanes.Record> {
    /** A bridge's record as fixed binary fields: about a third of its JSON, which is what the spooler's rate rides on. */
    interface Codec<R> {
        void write(R r, ByteBuffer b);
        R read(ByteBuffer b);
    }

    private static final class Segment<R> {
        final long start;
        final Path file;
        int count;
        long bytes;
        R first;
        Segment(final long start, final Path file) { this.start = start; this.file = file; }
    }

    private static final class Decoded { Object seg; Object[] recs; int n; long bytes; }

    private static final int WRITE_BUFFER = 1 << 20, RECORD_ROOM = 8192;
    private static final AtomicLong HELD = new AtomicLong();
    private static final AtomicLong INSTANCES = new AtomicLong();
    /** Bytes all of this member's spools may hold; -1 until first read from the environment (tests set it). */
    static volatile long budget = -1;
    private static Path root;

    private final Path dir;
    private final String lane;
    private final Codec<R> codec;
    private final int segmentRecords;
    private final ArrayList<Segment<R>> segments = new ArrayList<>();
    private final ByteBuffer wb = ByteBuffer.allocate(WRITE_BUFFER);
    private FileChannel out;          // the last segment's file, open for append
    private int pending;              // records in wb, not yet in the file
    private R pendingFirst;
    private volatile long start, end; // [start, end) is readable; both 0 when empty
    private long appended;            // end + pending
    volatile boolean frozen;          // stopped growing until the lane has passed its end
    long alertedAt;                   // spooler: when its freeze was last logged
    private Decoded c0 = new Decoded(), c1 = new Decoded();   // lane thread only

    LaneSpool(final Path dir, final String lane, final Codec<R> codec, final int segmentRecords) {
        this.dir = dir;
        this.lane = lane;
        this.codec = codec;
        this.segmentRecords = segmentRecords;
    }

    /** {@code BRIDGE_SPOOL_DIR}, else {@code <CLUSTER_BASE_DIR>/bridge-spool}, else a per-process temp directory; wiped
     *  once per process. Each bridge gets its own subdirectory. Null when it cannot be made: the bridge runs unspooled. */
    static synchronized Path directory(final String bridge) {
        try {
            if (root == null) {
                final String explicit = System.getenv("BRIDGE_SPOOL_DIR"), base = System.getenv("CLUSTER_BASE_DIR");
                final Path r = explicit != null && !explicit.isBlank() ? Path.of(explicit)
                    : base != null && !base.isBlank() ? Path.of(base, "bridge-spool")
                    : Path.of(System.getProperty("java.io.tmpdir"), "bridge-spool-" + ProcessHandle.current().pid());
                wipe(r);
                Files.createDirectories(r);
                root = r;
            }
            if (budget < 0) {
                final String v = System.getenv("BRIDGE_SPOOL_BYTES");
                long b = Math.min(64L << 30, Files.getFileStore(root).getUsableSpace() / 2);
                if (v != null && !v.isBlank()) { try { b = Math.max(0, Long.parseLong(v.trim())); } catch (final NumberFormatException keepDefault) { } }
                budget = b;
            }
            return Files.createDirectories(root.resolve(bridge + "-" + INSTANCES.incrementAndGet()));
        } catch (final IOException | RuntimeException e) {
            System.out.println("WARN " + bridge + "-nats-bridge has no spool directory (" + e + "): a lane more than its"
                + " ring behind loses records, as before FR-DRS-57");
            return null;
        }
    }

    private static void wipe(final Path p) throws IOException {
        if (!Files.exists(p)) { return; }
        try (var walk = Files.walk(p)) {
            for (final Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(f); }
        }
    }

    /** Bytes this member's spools hold on disk now. */
    static long held() { return HELD.get(); }

    long start() { return start; }
    long end() { return end; }
    /** Spooler: the index the next {@link #append} takes, or -1 when empty. */
    long next() { return appended; }
    boolean isEmpty() { return end == 0 && pending == 0; }

    /** Spooler: buffer record {@code k}; false when the budget is spent or the disk refuses it (nothing is kept of it). */
    boolean append(final R r, final long k) {
        if (isEmpty()) { appended = k; }
        if (pending > 0 && (wb.remaining() < RECORD_ROOM || (appended - segmentStart()) % segmentRecords == 0) && !flush()) { return false; }
        if (pending == 0) { pendingFirst = r; }
        try {
            final int at = wb.position();
            wb.putInt(0).put((byte) (r.leader ? 1 : 0));
            codec.write(r, wb);
            wb.putInt(at, wb.position() - at - 4);
        } catch (final RuntimeException tooLarge) {
            return false;
        }
        pending++;
        appended++;
        return true;
    }

    private long segmentStart() {
        return segments.isEmpty() ? appended - pending : segments.get(0).start;
    }

    /** Spooler: write what {@link #append} buffered and make it readable. False (and the buffered records dropped) when
     *  the budget is spent or the write fails. */
    boolean flush() {
        if (pending == 0) { return true; }
        final long first = appended - pending;
        wb.flip();
        final int bytes = wb.remaining();
        Segment<R> seg = null;
        try {
            if (HELD.get() + bytes > budget) { throw new IOException("BRIDGE_SPOOL_BYTES " + budget + " spent"); }
            synchronized (this) { seg = segments.isEmpty() ? null : segments.get(segments.size() - 1); }
            if (seg == null || seg.count == segmentRecords) {
                if (out != null) { out.close(); }
                seg = new Segment<>(first, dir.resolve(lane + "-" + first + ".seg"));
                seg.first = pendingFirst;
                out = FileChannel.open(seg.file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                synchronized (this) { segments.add(seg); }
            }
            while (wb.hasRemaining()) { out.write(wb); }
            HELD.addAndGet(bytes);
            synchronized (this) {
                seg.count += pending;
                seg.bytes += bytes;
                if (end == 0) { start = first; }
                end = appended;
            }
            return true;
        } catch (final IOException e) {
            if (System.nanoTime() - alertedAt > 10_000_000_000L) { System.out.println("ALERT bridge spool " + lane + " cannot grow: " + e); }
            appended = first;
            if (seg != null && seg.count == 0) {   // a segment made for this write: it must not stay, empty, as the last
                synchronized (this) { segments.remove(seg); }
                try { if (out != null) { out.close(); } Files.deleteIfExists(seg.file); } catch (final IOException ignore) { }
                out = null;
            }
            return false;
        } finally {
            wb.clear();
            pending = 0;
            pendingFirst = null;
        }
    }

    /** Spooler: delete the segments wholly below {@code low}; all of them once {@code low} has passed the end. */
    void trim(final long low) {
        if (end == 0) { return; }
        if (low >= end) { clear(); return; }
        while (true) {
            final Segment<R> seg;
            synchronized (this) {
                if (segments.size() < 2 || segments.get(0).start + segments.get(0).count > low) { return; }
                seg = segments.remove(0);
                start = segments.get(0).start;
            }
            delete(seg);
        }
    }

    /** Spooler: empty the spool. */
    void clear() {
        final ArrayList<Segment<R>> gone;
        synchronized (this) {
            gone = new ArrayList<>(segments);
            segments.clear();
            start = 0;
            end = 0;
        }
        if (out != null) { try { out.close(); } catch (final IOException ignore) { } out = null; }
        for (final Segment<R> seg : gone) { delete(seg); }
        wb.clear();
        pending = 0;
        pendingFirst = null;
        frozen = false;
    }

    private void delete(final Segment<R> seg) {
        HELD.addAndGet(-seg.bytes);
        try { Files.deleteIfExists(seg.file); } catch (final IOException ignore) { }
    }

    /** Lane: record {@code k}, or null when the spool does not hold it or cannot read it. */
    @SuppressWarnings("unchecked")
    R at(final long k) {
        final Segment<R> seg;
        final int count;
        final long bytes;
        synchronized (this) {
            if (k < start || k >= end) { return null; }
            seg = segments.get((int) ((k - start) / segmentRecords));
            count = seg.count;
            bytes = seg.bytes;
        }
        final int off = (int) (k - seg.start);
        Decoded d = c0.seg == seg ? c0 : c1.seg == seg ? c1 : null;
        if (d == null || off >= d.n) {
            // A segment not in the cache is decoded whole; the growing last segment only from where the cache stopped.
            if (d == null) { d = c1; c1 = c0; c0 = d; d.seg = null; d.recs = new Object[segmentRecords]; d.n = 0; d.bytes = 0; }
            try (FileChannel in = FileChannel.open(seg.file, StandardOpenOption.READ)) {
                final ByteBuffer b = ByteBuffer.allocate((int) (bytes - d.bytes));
                in.position(d.bytes);
                while (b.hasRemaining() && in.read(b) >= 0) { }
                b.flip();
                for (int n = d.n; n < count; n++) {
                    final int len = b.getInt(), next = b.position() + len;
                    final boolean leader = b.get() != 0;
                    final R r = codec.read(b);
                    r.leader = leader;
                    r.at = seg.start + n;
                    d.recs[n] = r;
                    b.position(next);
                }
                d.n = count;
                d.bytes = bytes;
                d.seg = seg;
            } catch (final IOException | RuntimeException e) {
                System.out.println("ALERT bridge spool " + lane + " cannot read " + seg.file + ": " + e);
                d.seg = null;
                return null;
            }
        }
        return (R) d.recs[off];
    }

    /** Lane: {start of the last segment whose first record is at or before the cut (the spool's start if none), start
     *  of the first segment whose first record is after it (the spool's end if none)}. Records are in apply order, so
     *  the first record after the cut lies between the two. */
    synchronized long[] bracket(final ToIntFunction<R> cut) {
        long lastAtOrBefore = start, firstAfter = end;
        for (final Segment<R> seg : segments) {
            if (cut.applyAsInt(seg.first) <= 0) { lastAtOrBefore = seg.start; } else { firstAfter = seg.start; break; }
        }
        return new long[] {lastAtOrBefore, firstAfter};
    }

    static void putString(final ByteBuffer b, final String s) {
        if (s == null) { b.putShort((short) -1); return; }
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        b.putShort((short) bytes.length).put(bytes);
    }

    static String getString(final ByteBuffer b) {
        final int n = b.getShort();
        if (n < 0) { return null; }
        final String s = new String(b.array(), b.arrayOffset() + b.position(), n, StandardCharsets.UTF_8);
        b.position(b.position() + n);
        return s;
    }
}
