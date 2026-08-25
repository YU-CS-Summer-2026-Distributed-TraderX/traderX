package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The no-GC allocation gate (NGC-01/NGC-02, SC-09B05, task T09B18).
 *
 * Drives the real hot-path topology — input ring -> journaler + replicator -> BLP ->
 * output ring — through a deterministic steady-state workload that exercises every BLP
 * branch of the YU13 crossing book (create-and-rest, full cross with paired dual-side
 * trade emits, partial market-order cross with remainder cancel, cancel of open and
 * terminal orders, force-fill republish, order-not-found, price ticks, terminal-retention
 * eviction/recycling, unknown event type), and asserts the producer, journaler, and BLP
 * threads allocate exactly ZERO bytes during the measured phase
 * (ThreadMXBean.getThreadAllocatedBytes, exact in HotSpot).
 *
 * Two enforcement layers:
 *  - This test runs in the regular `test` task under any collector: the per-thread
 *    byte deltas make a single steady-state allocation fail with `expected 0`.
 *  - The Gradle `noGcTest` task re-runs it under
 *    `-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms256m -Xmx256m
 *    -XX:+AlwaysPreTouch` with a larger event budget (Epsilon never reclaims, so any
 *    steady-state allocation exhausts the heap and crashes the run) — invoked by
 *    `pipeline/validate-no-gc-conformance.sh` (SC-NGC-01).
 *
 * Boundaries (documented NGC-01 scope): edge handlers (marshaller/NATS/projector) are
 * replaced by a no-op drain — edges are explicitly allowed to allocate and are not part
 * of the gated hot path. The terminal-retention cap is set low (8192) so eviction —
 * production's steady-state recycling — is active and hot throughout the measured phase
 * instead of being an unexercised branch.
 *
 * Workload mix per 16 events: 1 resting-sell create, 1 exactly-crossing buy (full cross,
 * both sides terminal, level empties), 1 resting-sell create + 1 partially-crossing
 * market buy (remainder canceled in place), 1 terminal-cancel republish,
 * 1 terminal-force-fill republish, 1 unknown-order command (not-found path), and 9
 * price ticks. A permanent deep-bid resting book (256 per security, 4 securities) sits
 * far below the crossing level; the ask side is deliberately empty outside each cycle so
 * market-order remainder cancel and empty-side best-price rescans stay hot. (No permanent
 * asks: a market buy has no price bound and would drain them, shifting behavior
 * mid-measurement.)
 */
class AllocationGateTest {
    private static final byte SIDE_BUY = InputEvent.SIDE_BUY;
    private static final byte SIDE_SELL = InputEvent.SIDE_SELL;
    private static final long T0 = 1_750_000_000_000L; // fixed event-carried wall clock
    private static final int SECURITIES = 4;
    private static final int RESTING_PER_SECURITY = 256;
    private static final long RESTING_LIMIT_PX = 50_000_000L;   // deep bids: never crossed
    // Inside the default band anchored by the $50 deep bids (base clamps to 0; ±$65.5 covers
    // $0..$131.07 at the 0.001 grid): the crossing level must stay in-band or every mix order
    // rejects PRICE_COLLAR and the gate measures nothing.
    private static final long CROSS_PX = 100_000_000L;
    // Two-account flow (ADR-057). The gate used ONE account for both sides, so every cross in it
    // was a self-trade: under cancel-oldest STP it booked zero trades and the gate's own
    // "paired trade booking did not run" sanity assertion caught it — which is exactly what that
    // assertion is for. Maker and taker are now distinct, and ACCT_SELF drives the STP branch on
    // purpose so the new code path is inside the measured window rather than outside it.
    private static final int ACCT_MAKER = 43;
    private static final int ACCT_TAKER = 42;
    private static final int ACCT_SELF = 44;
    // Above the largest ref the biggest (noGc 3M-event) budget can issue. ordersByRef is a
    // bounded map now (sized by open+retained, not the highest ref), so a huge ref costs one
    // entry — the warm-up publish at REF_CEILING-1 keeps the sparse-high-ref path exercised.
    private static final int REF_CEILING = 2_000_000;
    private static final int TERMINAL_RETAIN = 8_192;
    private static final int POOL_SIZE = 16_384;

    @TempDir
    Path journalDir;

    @Test
    void hotPathIsAllocationFreeInSteadyState() throws Exception {
        runSteadyStateGate(null);
    }

    /**
     * Same steady-state workload and zero-allocation invariant as
     * {@link #hotPathIsAllocationFreeInSteadyState()}, but with a real {@link BlpRiskState} wired
     * into the BLP so every ORDER_NEW runs the authoritative decide+reserve path (ADR-018) instead
     * of being skipped via the {@code risk == null} guard — closes the T-23 gap: the risk decision
     * pipeline must be exactly as allocation-free as the rest of the hot path (NGC-01/02 extend to
     * FR-IMRG13). The crossing mix additionally keeps consume (fill), release (market remainder
     * cancel), and reserve hot in the measured phase.
     */
    @Test
    void hotPathIsAllocationFreeInSteadyStateWithRiskGating() throws Exception {
        BlpRiskState risk = new BlpRiskState(64, SECURITIES + 8, 16_384, 1024,
            Long.MAX_VALUE / 4, Integer.MAX_VALUE, Long.MAX_VALUE / 4, 30_000L, new RiskMetrics());
        risk.putAccount(ACCT_TAKER, true);
        risk.putAccount(ACCT_MAKER, true);
        risk.putAccount(ACCT_SELF, true);
        for (int sec = 0; sec < SECURITIES + 2; sec++) {
            risk.putSecurity(sec, true); // 0..SECURITIES-1 (resting book) + 4,5 (warm-up edge cases)
        }
        risk.putLimits(Integer.MAX_VALUE, Long.MAX_VALUE / 4);
        runSteadyStateGate(risk);
    }

    private void runSteadyStateGate(BlpRiskState risk) throws Exception {
        var threadMxBase = ManagementFactory.getThreadMXBean();
        assumeTrue(threadMxBase instanceof com.sun.management.ThreadMXBean,
            "com.sun.management.ThreadMXBean unavailable");
        var threadMx = (com.sun.management.ThreadMXBean) threadMxBase;
        assumeTrue(threadMx.isThreadAllocatedMemorySupported(), "thread allocation accounting unavailable");
        if (!threadMx.isThreadAllocatedMemoryEnabled()) {
            threadMx.setThreadAllocatedMemoryEnabled(true);
        }

        int steadyEvents = Integer.getInteger("gate.steadyStateEvents", 1_000_000);
        int warmupEvents = Integer.getInteger("gate.warmupEvents", 250_000);

        // Pre-size every hot-path structure (startup allocation is the only allowed
        // allocation, NGC-01). With bounded terminal retention the pool reaches recycling
        // equilibrium during warm-up: permanent book + retained terminals + in-flight, so it
        // does not scale with the event budget.
        int missingRef = REF_CEILING - 2; // never created: exercises the not-found path

        HotPathMetrics metrics = new HotPathMetrics();
        Disruptor<OutputEvent> outputDisruptor = new Disruptor<>(OutputEvent::newInstance, 65536,
            DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, new YieldingWaitStrategy());
        DrainHandler drain = new DrainHandler();
        outputDisruptor.handleEventsWith(drain);
        outputDisruptor.start();

        MatchingEngine blp = new MatchingEngine(new OutputPublisher(outputDisruptor.getRingBuffer()),
            metrics, 16, 0, POOL_SIZE, 1024, TERMINAL_RETAIN, risk);
        // YU17 (format-8 price-derived grid): PIN the measured securities to the global grid.
        //
        // This gate's price constants are load-bearing geometry — $50 deep bids and a $100
        // crossing level both have to sit inside ONE band, which they do at the 0.001 grid
        // (+/-$65.5) and do not at the +/-$6.55 the map derives for a $50-$100 instrument. Left
        // underived, the map re-grids a book the moment it empties with a reference in hand: the
        // sec-2 force-fill loop below prints its own fill as a mark, empties the book, and the next
        // admission lands the whole security on a band that then refuses the mix's $100 ask — at
        // which point the gate's own "self-trade-prevention branch did not run" sanity assertion
        // fires. That assertion working is the reason this comment exists rather than a silently
        // narrower measurement.
        //
        // Pinned through the ADR-060 CATEGORY channel, which outranks the map by design — the same
        // mechanism bonds use and the same one LimitOrderBookTest pins with. Nothing here is about
        // the derivation; this gate measures ALLOCATION, and the derivation's own behaviour is
        // proved in BookGridDerivationTest. The empty-admission branch still executes on every
        // order in the measured window (it is an openOrders() compare plus tickPxForBook), and the
        // retick arm itself is JIT-warmed on an unpinned scratch security in warm-up below, so a
        // heap allocation on either path would still be caught here.
        for (int sec = 0; sec < SECURITIES; sec++) {
            blp.overrideBookTickPx(sec, MatchingEngine.DEFAULT_BOOK_TICK_PX);
        }
        Journaler journaler = new Journaler(true, journalDir, metrics);
        ReplicatorStub replicator = new ReplicatorStub();
        Disruptor<InputEvent> inputDisruptor = new Disruptor<>(InputEvent::newInstance, 65536,
            DaemonThreadFactory.INSTANCE, ProducerType.MULTI, new YieldingWaitStrategy());
        inputDisruptor.handleEventsWith(journaler, replicator).then(blp);
        inputDisruptor.start();
        RingBuffer<InputEvent> ring = inputDisruptor.getRingBuffer();

        try {
            int nextRef = 1;

            // ----- warm-up: startup allocation allowed; covers every branch, pre-grows every
            // ----- index, and reaches terminal-retention eviction equilibrium.
            publishOrder(ring, REF_CEILING - 1, 0, SIDE_BUY, RESTING_LIMIT_PX, 100); // sparse high ref: one map entry
            int terminalRef = nextRef;
            for (int i = 0; i < 64; i++) {
                publishOrder(ring, nextRef++, 1, SIDE_BUY, RESTING_LIMIT_PX, 10);
            }
            for (int ref = terminalRef; ref < terminalRef + 64; ref++) {
                publish(ring, InputEvent.TYPE_ORDER_CANCEL, ref, 0, 0, (byte) 0, 0, 0L, 0L); // open cancel (unlink)
                publish(ring, InputEvent.TYPE_ORDER_CANCEL, ref, 0, 0, (byte) 0, 0, 0L, 0L); // terminal republish
            }
            for (int i = 0; i < 32; i++) { // force-fill with no tick seen: limit-price fallback
                int ref = nextRef++;
                publishOrder(ring, ref, 2, SIDE_BUY, RESTING_LIMIT_PX, 700);
                publish(ring, InputEvent.TYPE_FORCE_FILL, ref, 0, 0, (byte) 0, 0, 0L, 0L);
            }
            for (int sec = 0; sec < SECURITIES; sec++) { // the permanent deep-bid resting book
                for (int i = 0; i < RESTING_PER_SECURITY; i++) {
                    publishOrder(ring, nextRef++, sec, SIDE_BUY, RESTING_LIMIT_PX, 100);
                }
            }
            for (int round = 0; round < 2; round++) { // seed the marks (ticks no longer fill)
                for (int sec = 0; sec < SECURITIES; sec++) {
                    publishTick(ring, sec, 99_500_000L);
                }
            }
            for (int i = 0; i < 32; i++) { // force-fill at last trade/tick price
                int ref = nextRef++;
                publishOrder(ring, ref, 3, SIDE_BUY, 100_000_000L, 2500);
                publish(ring, InputEvent.TYPE_FORCE_FILL, ref, 0, 0, (byte) 0, 0, 0L, 0L);
            }
            for (int sec = 0; sec < SECURITIES; sec++) { // market-order branches: cross + remainder cancel
                publishOrder(ring, nextRef++, sec, SIDE_SELL, CROSS_PX, 300);
                publish(ring, InputEvent.TYPE_ORDER_NEW, nextRef++, ACCT_TAKER, sec, SIDE_BUY, 400, Px.NONE, 0L);
            }
            // Every atomic-replace REJECT branch (ADR-058), warmed here rather than left cold. The
            // measured mix only ever takes the accepted path, and an uncommon-trap on a cold branch
            // deoptimises into a heap rematerialisation of an otherwise scalar-replaced object —
            // which shows up as a one-off ~72-byte "allocation" the gate cannot distinguish from a
            // real leak. Warming every branch is the gate's own doctrine; this handler was the gap.
            int replaceProbe = nextRef++;
            publishOrder(ring, replaceProbe, 0, SIDE_BUY, RESTING_LIMIT_PX, 100);
            publishReplace(ring, replaceProbe, 100, RESTING_LIMIT_PX);        // accepted: unlink + re-add
            publishReplace(ring, replaceProbe, 60, RESTING_LIMIT_PX);         // accepted: size-down keeps priority
            publishReplace(ring, replaceProbe, 0, RESTING_LIMIT_PX);          // reject: quantity
            publishReplace(ring, replaceProbe, 60, Px.NONE);                  // reject: no limit price
            publishReplace(ring, replaceProbe, 60, RESTING_LIMIT_PX + 1);     // reject: off grid
            publishReplace(ring, replaceProbe, 60, 200_000_000L);             // reject: outside the band
            // Rejected INSIDE the risk gate, so the release-then-restore path is warmed too: the
            // shape checks above all fire before anything is released and would leave it cold.
            publishReplace(ring, replaceProbe, Integer.MAX_VALUE, RESTING_LIMIT_PX);
            publishReplace(ring, missingRef, 10, RESTING_LIMIT_PX);           // reject: unknown ref
            publish(ring, InputEvent.TYPE_ORDER_CANCEL, replaceProbe, 0, 0, (byte) 0, 0, 0L, 0L);
            publishReplace(ring, replaceProbe, 10, RESTING_LIMIT_PX);         // reject: already terminal
            publish(ring, InputEvent.TYPE_ORDER_CANCEL, missingRef, 0, 0, (byte) 0, 0, 0L, 0L);
            publish(ring, InputEvent.TYPE_FORCE_FILL, missingRef, 0, 0, (byte) 0, 0, 0L, 0L);
            publish(ring, (byte) 99, 0, 0, 0, (byte) 0, 0, 0L, 0L); // unknown type
            publishTick(ring, 5, 88_000_000L); // security with no book
            // YU17: warm the empty-book RE-DERIVATION arm (format-8 design 2.3) on an UNPINNED
            // scratch security, so an allocation on that branch shows up in the measured window
            // rather than hiding behind a cold path. Security 4 has no category override: the
            // order below creates its book on the provisional global grid with no reference, the
            // cancel empties it, the tick gives it one, and the next admission re-derives 1000 ->
            // 100 and re-anchors. Kept off securities 0..3 on purpose — those are the measured
            // mix and their geometry must not move.
            int retickProbe = nextRef++;
            publishOrder(ring, retickProbe, 4, SIDE_BUY, RESTING_LIMIT_PX, 10);
            publish(ring, InputEvent.TYPE_ORDER_CANCEL, retickProbe, 0, 0, (byte) 0, 0, 0L, 0L);
            publishTick(ring, 4, RESTING_LIMIT_PX);
            int retickProbe2 = nextRef++;
            publishOrder(ring, retickProbe2, 4, SIDE_BUY, RESTING_LIMIT_PX, 10);
            publish(ring, InputEvent.TYPE_ORDER_CANCEL, retickProbe2, 0, 0, (byte) 0, 0, 0L, 0L);
            nextRef = runMix(ring, nextRef, warmupEvents, terminalRef, missingRef);
            awaitDrained(ring, outputDisruptor.getRingBuffer(), blp, drain);

            long blpThread = blp.blpThreadId();
            long journalThread = journaler.journalThreadId();
            assertTrue(blpThread != 0 && journalThread != 0, "consumer threads not started");
            // Lazy-init the accounting call sites on this thread before measuring.
            threadMx.getThreadAllocatedBytes(Thread.currentThread().threadId());
            threadMx.getThreadAllocatedBytes(blpThread);

            // ----- measured steady state: zero allocation allowed anywhere on the path.
            long producerBefore = threadMx.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long blpBefore = threadMx.getThreadAllocatedBytes(blpThread);
            long journalBefore = threadMx.getThreadAllocatedBytes(journalThread);

            runMix(ring, nextRef, steadyEvents, terminalRef, missingRef);

            long producerAfter = threadMx.getThreadAllocatedBytes(Thread.currentThread().threadId());
            awaitDrained(ring, outputDisruptor.getRingBuffer(), blp, drain);
            long blpAfter = threadMx.getThreadAllocatedBytes(blpThread);
            long journalAfter = threadMx.getThreadAllocatedBytes(journalThread);

            assertEquals(0L, blpAfter - blpBefore, "BLP thread allocated in steady state (NGC-01)");
            assertEquals(0L, journalAfter - journalBefore, "journaler thread allocated in steady state (NGC-01)");
            assertEquals(0L, producerAfter - producerBefore, "producer claim/write/publish allocated in steady state (NGC-01)");

            // Sanity: the measured phase really drove the full crossing mix through the BLP.
            long steadyCycles = steadyEvents / 16L;
            assertTrue(blp.countPriceTicks() >= steadyCycles * 6L, "tick mix did not run");
            assertTrue(blp.countOrdersReplace() >= steadyCycles - 16L,
                "atomic-replace branch did not run inside the measured window");
            assertTrue(blp.countSelfTradesPrevented() >= steadyCycles - 16L,
                "self-trade-prevention branch did not run inside the measured window");
            assertTrue(blp.countOrdersNew() >= steadyCycles * 4L - 16L, "create mix did not run");
            assertTrue(blp.autoFillSuccess() >= steadyCycles * 2L - 16L, "crossing mix did not run");
            assertTrue(blp.tradeCounter() >= steadyCycles * 4L - 16L, "paired trade booking did not run");
            assertTrue(journaler.journaledSeq() >= blp.blpSeq(), "journal gating violated");

            if (risk != null) {
                // NFR-IMRG01 p99 CI gate: 5us is ~5-8x the observed p99 on dev hardware
                // (600-950ns across repeated runs) -- tight enough to catch a real regression,
                // loose enough to tolerate JIT/GC warmup noise and slower/shared CI runners.
                long riskDecisionP99Nanos = metrics.riskDecisionHistogram().copy().getValueAtPercentile(99.0);
                assertTrue(riskDecisionP99Nanos <= 5_000L,
                    "BLP risk decision p99 exceeded 5us gate (NFR-IMRG01): " + riskDecisionP99Nanos + "ns");
            }
        } finally {
            inputDisruptor.shutdown(10, TimeUnit.SECONDS);
            outputDisruptor.shutdown(10, TimeUnit.SECONDS);
            journaler.close();
        }
    }

    /**
     * The shared warm-up/steady-state mix; identical branch profile in both phases so the
     * JIT compiles exactly the code the measurement runs. Per 16 events: 1 resting sell,
     * 1 exactly-crossing buy, 1 resting sell + 1 partially-crossing market buy (remainder
     * cancel), 1 atomic replace of the resting sell (ADR-058), 1 terminal cancel, 1 terminal
     * force-fill, 1 unknown-order command, 1 self-crossing sell + market buy pair (the ADR-057
     * cancel-oldest branch), 6 price ticks. The book is level-neutral per cycle: every rested order is fully consumed
     * within its own cycle.
     */
    private static int runMix(RingBuffer<InputEvent> ring, int nextRef, int events,
                              int terminalRef, int missingRef) {
        int lastSellRef = 0;
        for (int i = 0; i < events; i++) {
            int m = i & 15;
            // Per-CYCLE security rotation: all four order events of one 16-event cycle must hit
            // the SAME book, or the sell and its crossing buy land on different securities and
            // the "level-neutral per cycle" property silently breaks (the book then grows without
            // bound and drains the pool — caught by this very gate).
            int sec = (i >> 4) & 3;
            if (m == 0) {
                lastSellRef = nextRef;
                publishOrder(ring, nextRef++, sec, SIDE_SELL, CROSS_PX, 500);
            } else if (m == 1) {
                // Atomic replace (ADR-058) on the measured path: same size, same price, so it
                // unlinks and re-appends at the level tail and stays level-neutral — the ask is at
                // CROSS_PX and the permanent book is bids at RESTING_LIMIT_PX, so it cannot cross.
                publish(ring, InputEvent.TYPE_ORDER_REPLACE, lastSellRef, 0, 0, (byte) 0, 500,
                    CROSS_PX, 0L);
            } else if (m == 2) {
                publishOrder(ring, nextRef++, sec, SIDE_BUY, CROSS_PX, 500);   // full cross
            } else if (m == 4) {
                publish(ring, InputEvent.TYPE_ORDER_CANCEL, terminalRef, 0, 0, (byte) 0, 0, 0L, 0L);
            } else if (m == 6) {
                publishOrder(ring, nextRef++, sec, SIDE_SELL, CROSS_PX, 300);
            } else if (m == 8) {
                // market buy: fills the 300 resting, cancels the 100 remainder in place
                publish(ring, InputEvent.TYPE_ORDER_NEW, nextRef++, ACCT_TAKER, sec, SIDE_BUY, 400, Px.NONE, 0L);
            } else if (m == 10) {
                publish(ring, InputEvent.TYPE_FORCE_FILL, terminalRef, 0, 0, (byte) 0, 0, 0L, 0L);
            } else if (m == 12) {
                publish(ring, InputEvent.TYPE_ORDER_CANCEL, missingRef, 0, 0, (byte) 0, 0, 0L, 0L);
            } else if (m == 14) {
                // STP pair, level-neutral by construction: this ask is the only one at CROSS_PX...
                publish(ring, InputEvent.TYPE_ORDER_NEW, nextRef++, ACCT_SELF, sec, SIDE_SELL, 500,
                    CROSS_PX, 0L);
            } else if (m == 15) {
                // ...and this MARKET buy from the same account cancels it (cancel-oldest) and then
                // finds no asks left, so its own remainder is cancelled in place and it never rests.
                publish(ring, InputEvent.TYPE_ORDER_NEW, nextRef++, ACCT_SELF, sec, SIDE_BUY, 500,
                    Px.NONE, 0L);
            } else {
                publishTick(ring, sec, 60_000_000L + ((i & 7) * 1_000_000L));
            }
        }
        return nextRef;
    }

    private static void publishOrder(RingBuffer<InputEvent> ring, int orderRef, int securityId,
                                     byte side, long limitPx, int qty) {
        publish(ring, InputEvent.TYPE_ORDER_NEW, orderRef,
            side == SIDE_SELL ? ACCT_MAKER : ACCT_TAKER, securityId, side, qty, limitPx, 0L);
    }

    private static void publishReplace(RingBuffer<InputEvent> ring, int orderRef, int qty, long limitPx) {
        publish(ring, InputEvent.TYPE_ORDER_REPLACE, orderRef, 0, 0, (byte) 0, qty, limitPx, 0L);
    }

    private static void publishTick(RingBuffer<InputEvent> ring, int securityId, long priceTicks) {
        publish(ring, InputEvent.TYPE_PRICE_TICK, 0, 0, securityId, (byte) 0, 0, 0L, priceTicks);
    }

    /** Mirrors LmaxEngine.execute's slot write: claim, overwrite every field, publish. */
    private static void publish(RingBuffer<InputEvent> ring, byte type, int orderRef, int accountId,
                                int securityId, byte side, int qty, long limitPx, long priceTicks) {
        long seq = ring.next();
        try {
            InputEvent e = ring.get(seq);
            e.seq = seq;
            e.type = type;
            e.orderRef = orderRef;
            e.accountId = accountId;
            e.securityId = securityId;
            e.side = side;
            e.qty = qty;
            e.limitPx = limitPx;
            e.priceTicks = priceTicks;
            e.ingressNanos = System.nanoTime();
            e.eventTimeMillis = T0;
        } finally {
            ring.publish(seq);
        }
    }

    private static void awaitDrained(RingBuffer<InputEvent> in, RingBuffer<OutputEvent> out,
                                     MatchingEngine blp, DrainHandler drain) {
        awaitSeq(blp::blpSeq, in.getCursor(), "BLP to consume the input ring");
        awaitSeq(drain::lastSeq, out.getCursor(), "drain to consume the output ring");
    }

    private static void awaitSeq(LongSupplier actual, long target, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (actual.getAsLong() < target) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.onSpinWait();
        }
    }

    /** Output-edge stand-in: consumes events, allocates nothing. */
    private static final class DrainHandler implements EventHandler<OutputEvent> {
        private volatile long lastSeq = -1;

        @Override
        public void onEvent(OutputEvent event, long sequence, boolean endOfBatch) {
            lastSeq = sequence;
        }

        long lastSeq() {
            return lastSeq;
        }
    }
}
