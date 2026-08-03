package finos.traderx.ordermatcher.lmax;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * Fixed-capacity map from a primary's local ring sequence to the exact committed Aeron frame
 * boundary. The replication handler is upstream of the BLP and can run ahead, so the BLP must not
 * sample publication.position() when it later processes a SNAPSHOT marker. It instead looks up the
 * marker's own immutable slot here.
 *
 * <p>The map deliberately overwrites by ring slot. The BLP cannot trail its upstream handler by
 * more than the input ring capacity, and a read validates the full local sequence before using a
 * slot. There is no backpressure or steady-state allocation.
 */
public final class AeronPublicationSequenceMap {
    private static final VarHandle PUBLISHED;

    static {
        try {
            PUBLISHED = MethodHandles.lookup().findVarHandle(
                AeronPublicationSequenceMap.class, "publishedLocalSeq", long.class);
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final int mask;
    private final long[] localSeqs;
    private final long[] epochs;
    private final long[] inputSeqs;
    private final long[] recordingPositions;
    private final long[] checksums;
    private final int[] dataSessionIds;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long publishedLocalSeq = -1L;

    public AeronPublicationSequenceMap(int requestedCapacity) {
        int capacity = 1;
        while (capacity < Math.max(2, requestedCapacity)) capacity <<= 1;
        mask = capacity - 1;
        localSeqs = new long[capacity];
        epochs = new long[capacity];
        inputSeqs = new long[capacity];
        recordingPositions = new long[capacity];
        checksums = new long[capacity];
        dataSessionIds = new int[capacity];
        Arrays.fill(localSeqs, Long.MIN_VALUE);
    }

    public void put(long localSeq, long epoch, long inputSeq, long recordingPosition,
                    long checksum, int dataSessionId) {
        int slot = (int) localSeq & mask;
        localSeqs[slot] = localSeq;
        epochs[slot] = epoch;
        inputSeqs[slot] = inputSeq;
        recordingPositions[slot] = recordingPosition;
        checksums[slot] = checksum;
        dataSessionIds[slot] = dataSessionId;
        PUBLISHED.setRelease(this, localSeq);
    }

    public boolean read(long localSeq, FollowerSequenceMap.Entry target) {
        if ((long) PUBLISHED.getAcquire(this) < localSeq) return false;
        int slot = (int) localSeq & mask;
        if (localSeqs[slot] != localSeq) return false;
        target.localSeq = localSeq;
        target.epoch = epochs[slot];
        target.inputSeq = inputSeqs[slot];
        target.recordingPosition = recordingPositions[slot];
        target.checksum = checksums[slot];
        target.dataSessionId = dataSessionIds[slot];
        return true;
    }

    public int capacity() {
        return localSeqs.length;
    }
}
