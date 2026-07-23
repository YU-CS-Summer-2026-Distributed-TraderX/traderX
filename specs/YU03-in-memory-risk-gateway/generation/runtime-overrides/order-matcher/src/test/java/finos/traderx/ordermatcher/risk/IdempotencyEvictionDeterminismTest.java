package finos.traderx.ordermatcher.risk;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Correctness and zero-allocation proof for backward-shift idempotency eviction (brief 06).
 *
 * <p>Backward-shift deletion is a deterministic-core change: it reorganises the open-addressed hash
 * table in place on every eviction. These tests pin the two invariants that make it safe to roll —
 * findability and snapshot/replay identity — which the structural probe gate
 * ({@link IdempotencyProbeGateTest}) and the general pipeline test do not cover:
 *
 * <ul>
 *   <li><b>Every retained key stays findable after heavy eviction churn.</b> After many full
 *   retention turnovers, exactly {@code capacity} keys are live and each is still reachable by its
 *   probe (a dropped/misplaced key would vanish from {@code idempotencyTuples}).</li>
 *   <li><b>Snapshot round-trips identically.</b> The snapshot serialises the retention ring, not the
 *   hash slots, and rebuilds the table on restore — so removing tombstones cannot change the
 *   serialised form. A churned table and a fresh table restored from its snapshot must be
 *   observationally identical, which is exactly the cross-member / replay determinism guarantee.</li>
 *   <li><b>The eviction path allocates nothing</b> — in-place array moves only (NGC-01).</li>
 * </ul>
 */
class IdempotencyEvictionDeterminismTest {

    private static BlpRiskState rejectState(int idempotencyCapacity) {
        // Unseeded accounts => every order takes the UNKNOWN_ACCOUNT reject, which still runs the
        // full idempotency hot path (previousDecision miss-scan + remember evict-and-insert).
        return new BlpRiskState(8, 8, 64, idempotencyCapacity,
            Long.MAX_VALUE / 4, Integer.MAX_VALUE, Long.MAX_VALUE / 4, Long.MAX_VALUE, new RiskMetrics());
    }

    private static void drive(BlpRiskState risk, long firstKey, int count) {
        long key = firstKey;
        for (int i = 0; i < count; i++) {
            risk.decideAndReserve(key++, 0L, 0, 999, 0, (byte) 0, 0, 1, 100L, 0L, null);
        }
    }

    /** Re-decide {@code key}; true iff it replayed a stored decision (was still retained). */
    private static boolean replayedAsDuplicate(BlpRiskState risk, long key) {
        risk.decideAndReserve(key, 0L, 0, 999, 0, (byte) 0, 0, 1, 100L, 0L, null);
        return risk.duplicateReplay();
    }

    @Test
    void retainedKeysStayFindableAndSnapshotRestoresIdenticallyAfterEviction() {
        int cap = 4096;
        BlpRiskState churned = rejectState(cap);
        drive(churned, 1L, 6 * cap);   // 6 turnovers => ~5 full retention rings evicted via backward-shift

        List<long[]> churnedTuples = churned.idempotencyTuples();
        // Backward-shift kept every one of the last `cap` keys findable: none dropped or misplaced.
        assertEquals(cap, churnedTuples.size(),
            "retention should hold exactly capacity live keys; fewer => backward-shift lost a retained key");

        // Snapshot -> restore into a fresh state, in retention order (the real member/replay path).
        BlpRiskState restored = rejectState(cap);
        for (long[] t : churnedTuples) {
            restored.bootstrapIdempotency(t[0], (int) t[1], (byte) t[2]);
        }
        List<long[]> restoredTuples = restored.idempotencyTuples();
        assertEquals(churnedTuples.size(), restoredTuples.size(), "restore changed the live key count");
        for (int i = 0; i < churnedTuples.size(); i++) {
            assertArrayEquals(churnedTuples.get(i), restoredTuples.get(i),
                "retention tuple " + i + " diverged across snapshot/restore");
        }

        // Semantics: the newest key replays (retained); a long-evicted key misses. Probe last —
        // an evicted-key probe re-inserts it.
        long newest = 6L * cap;   // last key driven was (6*cap)
        assertTrue(replayedAsDuplicate(churned, newest), "newest key must replay as a retained duplicate");
        assertTrue(replayedAsDuplicate(restored, newest), "restored state must agree the newest key is retained");
        assertFalse(replayedAsDuplicate(churned, 1L), "the first key must be long evicted (a miss)");
        assertFalse(replayedAsDuplicate(restored, 1L), "restored state must agree the first key is evicted");
    }

    @Test
    void evictionPathIsAllocationFree() {
        var base = ManagementFactory.getThreadMXBean();
        assumeTrue(base instanceof ThreadMXBean, "com.sun.management.ThreadMXBean unavailable");
        ThreadMXBean mx = (ThreadMXBean) base;
        assumeTrue(mx.isThreadAllocatedMemorySupported(), "thread allocation accounting unavailable");
        if (!mx.isThreadAllocatedMemoryEnabled()) {
            mx.setThreadAllocatedMemoryEnabled(true);
        }

        int cap = 8192;
        BlpRiskState risk = rejectState(cap);
        long nextKey = 1L;
        // Warm: fill + several turnovers so decideAndReserve / evict / backward-shift are all compiled.
        drive(risk, nextKey, 4 * cap);
        nextKey += 4L * cap;

        long tid = Thread.currentThread().threadId();
        mx.getThreadAllocatedBytes(tid);   // lazy-init the accounting call site on this thread

        int measured = 4 * cap;            // table is full => every order evicts (backward-shift runs)
        long before = mx.getThreadAllocatedBytes(tid);
        drive(risk, nextKey, measured);
        long allocated = mx.getThreadAllocatedBytes(tid) - before;

        // In-place array moves allocate nothing. Allow tiny slack for JIT/measurement artifacts; a
        // real per-order allocation would be >= 16 B * measured = >= 512 KB here, far above the slack.
        assertTrue(allocated < 64_000L, "eviction/backward-shift path allocated " + allocated
            + " bytes over " + measured + " evicting orders (~" + (allocated / measured) + " B/order)");
    }
}
