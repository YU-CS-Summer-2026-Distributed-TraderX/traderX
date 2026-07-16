package finos.traderx.ordermatcher.lmax;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Fixed-capacity SPSC mapping from the follower's local ring sequence to the primary epoch/input
 * sequence and Aeron recording position. The producer publishes a release-store watermark only
 * after every field is written; the durable ACK agent reads it with acquire semantics.
 */
public final class FollowerSequenceMap {
    private static final VarHandle PUBLISHED;
    private static final VarHandle CONSUMED;

    static {
        try {
            PUBLISHED = MethodHandles.lookup().findVarHandle(
                FollowerSequenceMap.class, "publishedLocalSeq", long.class);
            CONSUMED = MethodHandles.lookup().findVarHandle(
                FollowerSequenceMap.class, "consumedLocalSeq", long.class);
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final int mask;
    private final long[] localSeqs;
    private final long[] inputSeqs;
    private final long[] epochs;
    private final long[] recordingPositions;
    private final long[] checksums;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long publishedLocalSeq = -1;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long consumedLocalSeq = -1;

    public FollowerSequenceMap(int requestedCapacity) {
        int capacity = 1;
        while (capacity < Math.max(2, requestedCapacity)) capacity <<= 1;
        mask = capacity - 1;
        localSeqs = new long[capacity];
        inputSeqs = new long[capacity];
        epochs = new long[capacity];
        recordingPositions = new long[capacity];
        checksums = new long[capacity];
        java.util.Arrays.fill(localSeqs, Long.MIN_VALUE);
    }

    public boolean put(long localSeq, long epoch, long inputSeq,
                       long recordingPosition, long checksum) {
        long consumed = (long) CONSUMED.getAcquire(this);
        if (localSeq - consumed > localSeqs.length) return false;
        int slot = (int) localSeq & mask;
        localSeqs[slot] = localSeq;
        epochs[slot] = epoch;
        inputSeqs[slot] = inputSeq;
        recordingPositions[slot] = recordingPosition;
        checksums[slot] = checksum;
        PUBLISHED.setRelease(this, localSeq);
        return true;
    }

    public boolean read(long localSeq, Entry target) {
        if ((long) PUBLISHED.getAcquire(this) < localSeq) return false;
        int slot = (int) localSeq & mask;
        if (localSeqs[slot] != localSeq) return false;
        target.localSeq = localSeq;
        target.epoch = epochs[slot];
        target.inputSeq = inputSeqs[slot];
        target.recordingPosition = recordingPositions[slot];
        target.checksum = checksums[slot];
        return true;
    }

    public void consumed(long localSeq) {
        CONSUMED.setRelease(this, localSeq);
    }

    public int capacity() { return localSeqs.length; }

    public static final class Entry {
        public long localSeq;
        public long epoch;
        public long inputSeq;
        public long recordingPosition;
        public long checksum;
    }
}
