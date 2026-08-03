package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
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
 * branch (create, in-the-money auto-fill with paired trade emit, cancel of open and
 * terminal orders, force-fill republish, order-not-found, in/out-of-the-money price
 * ticks, unknown event type), and asserts the producer, journaler, and BLP threads
 * allocate exactly ZERO bytes during the measured phase
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
 * of the gated hot path. Steady-state order creates draw from the pre-allocated
 * RestingOrder pool, which this harness sizes for the configured event budget exactly as
 * a production book is sized for the trading day (`blp.book.pool-size`); terminal-order
 * recycling arrives with T09B14.
 *
 * Workload mix per 16 events: 1 in-the-money create (immediate full fill + paired
 * TradeBooked), 1 terminal-cancel republish, 1 terminal-force-fill republish,
 * 1 unknown-order command (not-found path), 12 out-of-the-money price ticks scanning a
 * resting book of 256 orders per security across 4 securities.
 */
class AllocationGateTest {
    private static final byte SIDE_BUY = InputEvent.SIDE_BUY;
    private static final long T0 = 1_750_000_000_000L; // fixed event-carried wall clock
    private static final int SECURITIES = 4;
    private static final int RESTING_PER_SECURITY = 256;
    private static final long RESTING_LIMIT_PX = 50_000_000L;  // Buy @50.000: ticks at 60+ never match
    private static final long CREATE_LIMIT_PX = 200_000_000L;  // Buy @200.000: always in the money

    @TempDir
    Path journalDir;

    @Test
    void hotPathIsAllocationFreeInSteadyState() throws Exception {
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

        // Pre-size every hot-path structure for the full configured budget (startup
        // allocation is the only allowed allocation, NGC-01).
        int warmupCreates = SECURITIES * (RESTING_PER_SECURITY + 320) + 64 + 32 + 32 + warmupEvents / 16 + 16;
        int steadyCreates = steadyEvents / 16 + 16;
        int poolSize = warmupCreates + steadyCreates + 1024;
        int refCeiling = poolSize + 1024;
        int missingRef = refCeiling - 2; // never created: exercises the not-found path

        HotPathMetrics metrics = new HotPathMetrics();
        Disruptor<OutputEvent> outputDisruptor = new Disruptor<>(OutputEvent::newInstance, 65536,
            DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, new YieldingWaitStrategy());
        DrainHandler drain = new DrainHandler();
        outputDisruptor.handleEventsWith(drain);
        outputDisruptor.start();

        MatchingEngine blp = new MatchingEngine(new OutputPublisher(outputDisruptor.getRingBuffer()),
            metrics, 16, 1000, poolSize, 1024);
        Journaler journaler = new Journaler(true, journalDir, metrics);
        ReplicatorStub replicator = new ReplicatorStub();
        Disruptor<InputEvent> inputDisruptor = new Disruptor<>(InputEvent::newInstance, 65536,
            DaemonThreadFactory.INSTANCE, ProducerType.MULTI, new YieldingWaitStrategy());
        inputDisruptor.handleEventsWith(journaler, replicator).then(blp);
        inputDisruptor.start();
        RingBuffer<InputEvent> ring = inputDisruptor.getRingBuffer();

        try {
            int nextRef = 1;

            // ----- warm-up: startup allocation allowed; covers every branch and pre-grows
            // ----- every index past anything the measured phase can reach.
            publishOrder(ring, refCeiling - 1, 0, RESTING_LIMIT_PX, 100); // grows ordersByRef once
            int terminalRef = nextRef;
            for (int i = 0; i < 64; i++) {
                publishOrder(ring, nextRef++, 1, RESTING_LIMIT_PX, 10);
            }
            for (int ref = terminalRef; ref < terminalRef + 64; ref++) {
                publish(ring, InputEvent.TYPE_ORDER_CANCEL, ref, 0, 0, (byte) 0, 0, 0L, 0L); // open cancel
                publish(ring, InputEvent.TYPE_ORDER_CANCEL, ref, 0, 0, (byte) 0, 0, 0L, 0L); // terminal republish
            }
            for (int i = 0; i < 32; i++) { // force-fill with no tick seen: limit-price fallback
                int ref = nextRef++;
                publishOrder(ring, ref, 2, RESTING_LIMIT_PX, 700);
                publish(ring, InputEvent.TYPE_FORCE_FILL, ref, 0, 0, (byte) 0, 0, 0L, 0L);
            }
            for (int sec = 0; sec < SECURITIES; sec++) { // the permanent resting book
                for (int i = 0; i < RESTING_PER_SECURITY; i++) {
                    publishOrder(ring, nextRef++, sec, RESTING_LIMIT_PX, 100);
                }
            }
            for (int sec = 0; sec < SECURITIES; sec++) { // stretch list depth, then fill back down
                for (int i = 0; i < 320; i++) {
                    publishOrder(ring, nextRef++, sec, 100_000_000L, 1800);
                }
            }
            for (int round = 0; round < 2; round++) { // partial fill, then full fill (900 < 1000)
                for (int sec = 0; sec < SECURITIES; sec++) {
                    publishTick(ring, sec, 99_500_000L);
                }
            }
            for (int i = 0; i < 32; i++) { // force-fill at last market price
                int ref = nextRef++;
                publishOrder(ring, ref, 3, 100_000_000L, 2500);
                publish(ring, InputEvent.TYPE_FORCE_FILL, ref, 0, 0, (byte) 0, 0, 0L, 0L);
            }
            publish(ring, InputEvent.TYPE_ORDER_CANCEL, missingRef, 0, 0, (byte) 0, 0, 0L, 0L);
            publish(ring, InputEvent.TYPE_FORCE_FILL, missingRef, 0, 0, (byte) 0, 0, 0L, 0L);
            publish(ring, (byte) 99, 0, 0, 0, (byte) 0, 0, 0L, 0L); // unknown type
            publishTick(ring, 5, 88_000_000L); // security with no open-order list
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

            // Sanity: the measured phase really drove the full mix through the BLP.
            assertTrue(blp.countPriceTicks() >= steadyEvents / 16L * 12L, "tick mix did not run");
            assertTrue(blp.countOrdersNew() >= steadyCreates - 16L, "create mix did not run");
            assertTrue(blp.autoFillSuccess() >= steadyCreates - 16L, "fill mix did not run");
            assertTrue(journaler.journaledSeq() >= blp.blpSeq(), "journal gating violated");
        } finally {
            inputDisruptor.shutdown(10, TimeUnit.SECONDS);
            outputDisruptor.shutdown(10, TimeUnit.SECONDS);
            journaler.close();
        }
    }

    /**
     * The shared warm-up/steady-state mix; identical branch profile in both phases so the
     * JIT compiles exactly the code the measurement runs. Per 16 events: 1 create that
     * fully fills on arrival, 1 terminal cancel, 1 terminal force-fill, 1 unknown-order
     * command, 12 resting-book price ticks.
     */
    private static int runMix(RingBuffer<InputEvent> ring, int nextRef, int events,
                              int terminalRef, int missingRef) {
        for (int i = 0; i < events; i++) {
            int m = i & 15;
            int sec = i & 3;
            if (m == 0) {
                publishOrder(ring, nextRef++, sec, CREATE_LIMIT_PX, 500);
            } else if (m == 4) {
                publish(ring, InputEvent.TYPE_ORDER_CANCEL, terminalRef, 0, 0, (byte) 0, 0, 0L, 0L);
            } else if (m == 8) {
                publish(ring, InputEvent.TYPE_FORCE_FILL, terminalRef, 0, 0, (byte) 0, 0, 0L, 0L);
            } else if (m == 12) {
                publish(ring, InputEvent.TYPE_ORDER_CANCEL, missingRef, 0, 0, (byte) 0, 0, 0L, 0L);
            } else {
                publishTick(ring, sec, 60_000_000L + ((i & 7) * 1_000_000L));
            }
        }
        return nextRef;
    }

    private static void publishOrder(RingBuffer<InputEvent> ring, int orderRef, int securityId,
                                     long limitPx, int qty) {
        publish(ring, InputEvent.TYPE_ORDER_NEW, orderRef, 42, securityId, SIDE_BUY, qty, limitPx, 0L);
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
