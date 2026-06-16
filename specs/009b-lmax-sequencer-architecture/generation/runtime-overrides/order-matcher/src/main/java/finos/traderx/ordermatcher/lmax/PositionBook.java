package finos.traderx.ordermatcher.lmax;

/**
 * BLP-private net-position store (state 009b, FR-09B08/FR-09B10): the single in-memory,
 * single-writer record of {@code (accountId, securityId) -> (net quantity, average cost
 * basis)}. Booking and position-keeping are fused into the BLP, so a fill or market trade
 * updates the position — quantity and weighted average cost — with one in-memory call on the
 * BLP thread: no second writer, no DB round-trip, no lock.
 *
 * Open-addressing primitive hash map with linear probing: the long key and the parallel
 * {@code int} quantity / {@code long} cost-basis-in-Px-ticks values live in pre-allocated
 * arrays, so steady-state updates allocate nothing (NGC-01). The only allocation is the rare
 * amortized-doubling resize, a cold event, in line with {@link MatchingEngine}'s order index.
 * No {@code HashMap}/autoboxing/streams/BigDecimal — this is a hot-path class scanned by the
 * banned-API gate (SC-09B13); the average is kept in long fixed-point and converted to
 * BigDecimal only at the read-model/NATS edge.
 */
public final class PositionBook {
    private static final long EMPTY = Long.MIN_VALUE;
    private static final float LOAD_FACTOR = 0.7f;

    private long[] keys;
    private int[] values;             // net quantity
    private long[] avgCostTicks;      // weighted average cost basis, Px ticks (Px.NONE/0 when flat)
    private int mask;
    private int size;
    private int resizeThreshold;
    private long lastAvgCostTicks;    // average produced by the most recent bookTrade (BLP-thread read)

    public PositionBook(int initialCapacity) {
        int capacity = Math.max(16, Integer.highestOneBit(Math.max(1, initialCapacity - 1)) << 1);
        allocate(capacity);
    }

    private void allocate(int capacity) {
        this.keys = new long[capacity];
        this.values = new int[capacity];
        this.avgCostTicks = new long[capacity];
        java.util.Arrays.fill(this.keys, EMPTY);
        this.mask = capacity - 1;
        this.resizeThreshold = (int) (capacity * LOAD_FACTOR);
    }

    private static long key(int accountId, int securityId) {
        return ((long) accountId << 32) | (securityId & 0xFFFFFFFFL);
    }

    /**
     * Book a trade: apply the signed quantity delta and fold the execution price into the
     * weighted average cost basis (009 trade-processor's running-average formula, in long
     * fixed-point). Returns the new net quantity; the new average cost basis (Px ticks) is
     * read right after with {@link #lastAvgCostTicks()} on the same BLP thread.
     */
    public int bookTrade(int accountId, int securityId, int signedQty, long execPxTicks) {
        long k = key(accountId, securityId);
        int idx = indexOf(k);
        boolean fresh = keys[idx] == EMPTY;
        int oldQty = fresh ? 0 : values[idx];
        long oldAvgTicks = fresh ? 0L : avgCostTicks[idx];
        int newQty = oldQty + signedQty;
        // newNotional = oldAvg*oldQty + execPx*signedQty (Px ticks), re-averaged over newQty;
        // a flat (zero) net position resets the basis to zero, exactly as 009's processTrade.
        long newAvgTicks = newQty == 0 ? 0L
            : divRoundHalfUp(oldAvgTicks * (long) oldQty + execPxTicks * (long) signedQty, newQty);
        // Write all three parallel arrays before any resize so the rehash copies this entry.
        keys[idx] = k;
        values[idx] = newQty;
        avgCostTicks[idx] = newAvgTicks;
        if (fresh && ++size >= resizeThreshold) {
            resize();
        }
        lastAvgCostTicks = newAvgTicks;
        return newQty;
    }

    /** Average cost basis (Px ticks) produced by the most recent {@link #bookTrade} call. */
    public long lastAvgCostTicks() {
        return lastAvgCostTicks;
    }

    /** Absolute set, used for warm-start bootstrap from the persisted POSITIONS read-model. */
    public void put(int accountId, int securityId, int quantity, long avgCostTicks) {
        long k = key(accountId, securityId);
        int idx = indexOf(k);
        boolean fresh = keys[idx] == EMPTY;
        if (fresh) {
            keys[idx] = k;
        }
        values[idx] = quantity;
        this.avgCostTicks[idx] = avgCostTicks;
        if (fresh && ++size >= resizeThreshold) {
            resize();
        }
    }

    public int get(int accountId, int securityId) {
        int idx = indexOf(key(accountId, securityId));
        return keys[idx] == EMPTY ? 0 : values[idx];
    }

    /** Average cost basis (Px ticks) currently stored for the position; 0 when flat/unknown. */
    public long avgCostTicks(int accountId, int securityId) {
        int idx = indexOf(key(accountId, securityId));
        return keys[idx] == EMPTY ? 0L : avgCostTicks[idx];
    }

    public int size() {
        return size;
    }

    private int indexOf(long k) {
        int idx = (int) (mix(k) & mask);
        while (keys[idx] != EMPTY && keys[idx] != k) {
            idx = (idx + 1) & mask;
        }
        return idx;
    }

    private void resize() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        long[] oldAvg = avgCostTicks;
        allocate(oldKeys.length << 1);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                int idx = indexOf(oldKeys[i]);
                keys[idx] = oldKeys[i];
                values[idx] = oldValues[i];
                avgCostTicks[idx] = oldAvg[i];
                size++;
            }
        }
    }

    /** Fibonacci-style mix so sequential account/security ids scatter across buckets. */
    private static long mix(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 32);
    }

    /**
     * Round-half-away-from-zero integer division for the weighted average. The 6dp tick
     * scale is three decimals finer than the 3dp the edge renders, so this is exact for every
     * value the read-model emits; primitive-only (no BigDecimal) to stay banned-API clean.
     */
    private static long divRoundHalfUp(long num, long den) {
        if (den < 0) {
            num = -num;
            den = -den;
        }
        long half = den >> 1;
        return num >= 0 ? (num + half) / den : -((-num + half) / den);
    }
}
