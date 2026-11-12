package finos.traderx.ordermatcher.cluster;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * ADR-108 (FR-DRS-59): the recon's live window on a member, as a ring of primitives indexed by {@code tradeSeq}.
 * The engine numbers booked sides densely, so a side's slot is {@code tradeSeq & mask} and recording one is seven
 * array stores and an ordered write of the head: no allocation, no eviction walk. The skip list it replaces was 27%
 * of the leader's service thread at ~431K sides/s (run 7).
 *
 * <p>One writer (the service thread, from the read-side output tap); any number of readers (the HTTP threads that
 * serve {@code /recon/trades/blotter}). A reader copies slots up to the head it saw, then re-reads the head and
 * drops every slot the writer may have lapped meanwhile, so a torn slot is never served.
 *
 * <p>Not replicated, not in the snapshot, read by no decision: a restarted member starts empty and fills from the
 * log tail it replays.
 */
final class LiveTradeRing {
    /** One slot's copy. */
    record Slot(long tradeSeq, int accountId, int securityId, byte side, int qty, long px, long millis) { }

    interface Sink { void accept(long tradeSeq, int accountId, int securityId, byte side, int qty, long px, long millis); }

    private static final VarHandle HEAD;
    static {
        try { HEAD = MethodHandles.lookup().findVarHandle(LiveTradeRing.class, "head", long.class); }
        catch (ReflectiveOperationException ex) { throw new ExceptionInInitializerError(ex); }
    }

    private final int mask;
    private final long[] seq, px, millis;
    private final int[] account, security, qty;
    private final byte[] side;
    @SuppressWarnings("unused") private volatile long head;   // highest tradeSeq recorded; written with setRelease
    private volatile long first;                              // the first tradeSeq this process recorded

    /** {@code capacity} sides, rounded up to a power of two (at most 2^30). */
    LiveTradeRing(final int capacity) {
        if (capacity <= 0) { throw new IllegalArgumentException("capacity must be positive: " + capacity); }
        final int size = capacity == 1 ? 1 : Integer.highestOneBit(Math.min(capacity, 1 << 30) - 1) << 1;
        mask = size - 1;
        seq = new long[size]; px = new long[size]; millis = new long[size];
        account = new int[size]; security = new int[size]; qty = new int[size];
        side = new byte[size];
    }

    int capacity() { return mask + 1; }

    /** Service thread only. */
    void record(final long tradeSeq, final int accountId, final int securityId, final byte sideCode, final int quantity,
                final long priceTicks, final long timeMillis) {
        final int i = (int) (tradeSeq & mask);
        account[i] = accountId; security[i] = securityId; side[i] = sideCode; qty[i] = quantity;
        px[i] = priceTicks; millis[i] = timeMillis; seq[i] = tradeSeq;
        if (first == 0) { first = tradeSeq; }
        HEAD.setRelease(this, tradeSeq);
    }

    /** Sides held now: from the oldest the ring still has to the head. */
    int size() {
        final long h = (long) HEAD.getAcquire(this), f = first;
        return h == 0 ? 0 : (int) Math.min(mask + 1L, h - f + 1);
    }

    /**
     * Ascending by tradeSeq, strictly greater than {@code sinceSeq}, at most {@code limit}; only slots that were whole
     * for the entire copy. Returns the number handed to {@code out}.
     */
    int since(final long sinceSeq, final int limit, final Sink out) {
        final long h = (long) HEAD.getAcquire(this);
        if (h == 0 || limit <= 0) { return 0; }
        final long from = Math.max(Math.max(sinceSeq + 1, first), h - mask);
        final long to = Math.min(h, from + limit - 1);
        if (to < from) { return 0; }
        final int n = (int) (to - from + 1);
        final long[] s = new long[n], p = new long[n], t = new long[n];
        final int[] a = new int[n], c = new int[n], q = new int[n];
        final byte[] d = new byte[n];
        for (int k = 0; k < n; k++) {
            final int i = (int) ((from + k) & mask);
            s[k] = seq[i]; a[k] = account[i]; c[k] = security[i]; d[k] = side[i]; q[k] = qty[i]; p[k] = px[i]; t[k] = millis[i];
        }
        // The writer may have lapped the oldest of these while they were copied: anything at or below the new head
        // minus the capacity was being overwritten, and is not served. (Its side is gone from the window, as any
        // side older than the window is.)
        // One more than that: the slot of the side the writer is recording right now (head + 1) is being written
        // before the head moves.
        final long safeFrom = (long) HEAD.getAcquire(this) - mask + 1;
        int served = 0;
        for (int k = 0; k < n; k++) {
            final long want = from + k;
            if (want < safeFrom || s[k] != want) { continue; }   // lapped, or a number the engine never recorded here
            out.accept(s[k], a[k], c[k], d[k], q[k], p[k], t[k]);
            served++;
        }
        return served;
    }
}
