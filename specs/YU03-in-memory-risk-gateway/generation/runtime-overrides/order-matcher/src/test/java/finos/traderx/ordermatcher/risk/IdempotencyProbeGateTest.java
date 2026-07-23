package finos.traderx.ordermatcher.risk;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Steady-state idempotency-probe regression gate (brief 06 — the per-order ceiling).
 *
 * <p>Drives UNIQUE clientOrderKeys — the production reality, every real ClOrdID is fresh — through
 * the real {@link BlpRiskState} at real retention capacity (256Ki) across several full retention
 * turnovers, and asserts the open-addressed key table stays healthy: no tombstone saturation, empty
 * slots never collapse, so the miss-path probe that {@code previousDecision} runs on <em>every</em>
 * order stays bounded by the 0.5 load factor forever.
 *
 * <p>This is the falsifiable proof. It <b>FAILS on the pre-fix (tombstone) engine</b> — eviction
 * writes {@code TOMBSTONE_KEY} and never compacts, the miss probe stops only at {@code EMPTY_KEY},
 * so under unique keys empty% collapses toward 0 (2.55% by turnover 3, 0.05% by turnover 7) and
 * ns/order hockey-sticks ~220x. It <b>PASSES under backward-shift deletion</b> — no tombstone is
 * ever written, empty% holds ~50%, ns/order stays flat. Account 999 is unseeded so every order
 * takes the UNKNOWN_ACCOUNT reject, which still runs the full idempotency hot path
 * (previousDecision miss-scan + remember evict-and-insert) — a conservative floor vs an accepted
 * order, which additionally probes in canRemember.
 */
class IdempotencyProbeGateTest {
    /** The sentinel the pre-fix engine wrote on eviction; the fix must never produce it. */
    private static final long TOMBSTONE_SENTINEL = Long.MIN_VALUE + 1L;
    private static final long EMPTY_SENTINEL = Long.MIN_VALUE;
    private static final int CAPACITY = 262_144;   // real IDEMPOTENCY_CAPACITY (256Ki)
    private static final int TURNOVERS = 5;         // > 3: well past where the pre-fix table degrades

    @Test
    void missProbeStaysBoundedAcrossRetentionTurnovers() throws Exception {
        BlpRiskState risk = new BlpRiskState(8, 8, 64, CAPACITY,
            Long.MAX_VALUE / 4, Integer.MAX_VALUE, Long.MAX_VALUE / 4, Long.MAX_VALUE, new RiskMetrics());

        long[] keys = readKeys(risk);
        int slots = keys.length;
        long firstTurnoverNs = 0, lastTurnoverNs = 0;
        long key = 1;
        for (int t = 0; t < TURNOVERS; t++) {
            long start = System.nanoTime();
            for (int i = 0; i < CAPACITY; i++) {
                // unseeded account => UNKNOWN_ACCOUNT; target unused on the reject path.
                risk.decideAndReserve(key++, 0L, 0, 999, 0, (byte) 0, 0, 1, 100L, 0L, null);
            }
            long ns = System.nanoTime() - start;
            if (t == 0) firstTurnoverNs = ns;
            lastTurnoverNs = ns;

            keys = readKeys(risk);
            int empty = 0, tomb = 0;
            for (long v : keys) {
                if (v == EMPTY_SENTINEL) empty++;
                else if (v == TOMBSTONE_SENTINEL) tomb++;
            }
            double emptyFrac = (double) empty / slots;
            int longestRun = longestOccupiedRun(keys);

            // After >= 3 turnovers the miss probe must still terminate fast. These flip pre-fix.
            if (t >= 3) {
                assertEquals(0, tomb, "tombstones present at turnover " + t
                    + " — the miss probe no longer terminates on EMPTY (pre-fix degradation)");
                assertTrue(emptyFrac >= 0.40, "empty slots collapsed to "
                    + String.format("%.2f%%", 100 * emptyFrac) + " at turnover " + t
                    + " (miss probe ~" + (int) (1 / Math.max(emptyFrac, 1e-9)) + " slots) — tombstone saturation");
                assertTrue(longestRun <= 4096, "longest occupied run " + longestRun + " at turnover " + t
                    + " — worst-case miss probe unbounded");
            }
        }
        // Coarse hockey-stick backstop; huge margin (pre-fix ~220x, fixed ~1-2x incl. JIT warmup).
        assertTrue(lastTurnoverNs <= 20 * firstTurnoverNs, "ns/order hockey-stick: last turnover "
            + lastTurnoverNs + "ns vs first " + firstTurnoverNs + "ns");
    }

    /** Longest wrap-around run of occupied (non-EMPTY) slots — the worst-case miss probe length. */
    private static int longestOccupiedRun(long[] keys) {
        int n = keys.length;
        int anchor = -1;
        for (int i = 0; i < n; i++) {
            if (keys[i] == EMPTY_SENTINEL) { anchor = i; break; }
        }
        if (anchor < 0) return n;   // no empty slot: the whole table is one run
        int best = 0, run = 0;
        for (int off = 0; off < n; off++) {
            if (keys[(anchor + off) % n] == EMPTY_SENTINEL) {
                if (run > best) best = run;
                run = 0;
            } else {
                run++;
            }
        }
        return Math.max(best, run);
    }

    private static long[] readKeys(BlpRiskState st) throws Exception {
        Field f = BlpRiskState.class.getDeclaredField("idempotencyKeys");
        f.setAccessible(true);
        return (long[]) f.get(st);
    }
}
