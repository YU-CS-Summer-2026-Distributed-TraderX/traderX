package finos.traderx.ordermatcher.lmax;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fixed-capacity NATS-authoritative checksum window used by the Aeron shadow consumer. The NATS
 * follower publishes the checksum before the release-store sequence tag; the Aeron consumer only
 * compares an exact tag match, so a lagging shadow detects overwrite instead of comparing the
 * wrong slot.
 */
public final class ShadowSequenceMap {
    private final int mask;
    private final long[] checksums;
    private final AtomicLongArray inputSeqs;

    public ShadowSequenceMap(int requestedCapacity) {
        int capacity = 1;
        while (capacity < Math.max(2, requestedCapacity)) capacity <<= 1;
        mask = capacity - 1;
        checksums = new long[capacity];
        inputSeqs = new AtomicLongArray(capacity);
        for (int i = 0; i < capacity; i++) inputSeqs.set(i, Long.MIN_VALUE);
    }

    public void put(long inputSeq, long checksum) {
        int slot = (int) inputSeq & mask;
        checksums[slot] = checksum;
        inputSeqs.lazySet(slot, inputSeq);
    }

    public boolean contains(long inputSeq) {
        return inputSeqs.get((int) inputSeq & mask) == inputSeq;
    }

    public long checksum(long inputSeq) {
        return checksums[(int) inputSeq & mask];
    }

    public int capacity() { return checksums.length; }
}
