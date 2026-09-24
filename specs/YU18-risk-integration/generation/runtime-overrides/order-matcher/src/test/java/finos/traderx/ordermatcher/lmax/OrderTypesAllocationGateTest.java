package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SC-OT34 / NFR-OT02: the typed-order hot path allocates nothing in steady state — admission,
 * latching, the trigger drain, trailing watermark updates, iceberg replenishment, peg repricing and
 * both FOK outcomes. Exact-zero on the apply thread, measured the way {@link AllocationGateTest}
 * measures (ThreadMXBean), and run in its own JVM by the isolated {@code orderTypesAllocationGateTest}
 * task with the same C2-only flags, for the same compiler-artifact reason documented there.
 *
 * <p>The cycle returns the book to empty every time, at fixed prices, with key-less orders, so the
 * steady state is genuinely steady: no band re-anchor, no idempotency growth, bounded retention.
 */
class OrderTypesAllocationGateTest {
    private static final int A = 1001;
    private static final int B = 1002;
    private static final int C = 1003;
    private static final int D = 1004;
    private static final int SEC = 2;
    private static final long CENT = 10_000L;
    private static final long P100 = 100_000_000L;
    private static final long HUGE = Long.MAX_VALUE / 4;

    private MatchingEngine engine;
    private final InputEvent e = new InputEvent();
    private long seq = 1;
    private int ref = 0;

    private int send(final byte type, final int acct, final byte side, final int qty, final long limit,
                     final byte orderType, final byte tif, final long stop, final int dq,
                     final byte pegRef, final byte trailMode, final long trailValue) {
        e.type = type;
        e.orderRef = type == InputEvent.TYPE_ORDER_NEW ? ++ref : e.orderRef;
        e.accountId = acct;
        e.securityId = SEC;
        e.side = side;
        e.qty = qty;
        e.limitPx = limit;
        e.priceTicks = 0L;
        e.orderType = orderType;
        e.tif = tif;
        e.stopPx = stop;
        e.displayQty = dq;
        e.pegRef = pegRef;
        e.pegOffset = 0;
        e.trailMode = trailMode;
        e.trailValue = trailValue;
        e.admissionSeq = 0L;
        e.eventTimeMillis = 1_000L + seq;
        e.ingressNanos = 0L;
        e.seq = seq;
        engine.onEvent(e, seq++, true);
        return e.orderRef;
    }

    private int limit(final int acct, final byte side, final int qty, final long px) {
        return send(InputEvent.TYPE_ORDER_NEW, acct, side, qty, px, OrderTypes.LEGACY, (byte) 0, 0L, 0,
            (byte) 0, (byte) 0, 0L);
    }

    private void cancel(final int orderRef) {
        e.orderRef = orderRef;
        send(InputEvent.TYPE_ORDER_CANCEL, 0, (byte) 0, 0, 0L, OrderTypes.LEGACY, (byte) 0, 0L, 0,
            (byte) 0, (byte) 0, 0L);
    }

    private void cycle() {
        final byte buy = InputEvent.SIDE_BUY;
        final byte sell = InputEvent.SIDE_SELL;
        // a) a print at 100
        limit(B, sell, 1, P100);
        limit(C, buy, 1, P100);
        // b) a STOP latched by a print at 101, drained into the ask, remainder cancelled
        send(InputEvent.TYPE_ORDER_NEW, A, buy, 2, 0L, OrderTypes.STOP, OrderTypes.GTC, P100 + CENT,
            0, (byte) 0, (byte) 0, 0L);
        limit(B, sell, 2, P100 + CENT);
        limit(C, buy, 1, P100 + CENT);
        // c) an iceberg swept through two replenishments
        send(InputEvent.TYPE_ORDER_NEW, B, sell, 6, P100, OrderTypes.ICEBERG, OrderTypes.GTC, 0L, 2,
            (byte) 0, (byte) 0, 0L);
        limit(C, buy, 6, P100);
        // d) a peg priced, repriced to its cap, then everything cancelled
        final int bid1 = limit(D, buy, 1, P100 - CENT);
        final int peg = send(InputEvent.TYPE_ORDER_NEW, A, buy, 1, P100, OrderTypes.PEGGED,
            OrderTypes.GTC, 0L, 0, OrderTypes.PEG_PRIMARY, (byte) 0, 0L);
        final int bid2 = limit(D, buy, 1, P100);
        cancel(peg);
        cancel(bid2);
        cancel(bid1);
        // e) FOK: infeasible, then feasible
        limit(B, sell, 1, P100);
        send(InputEvent.TYPE_ORDER_NEW, A, buy, 2, P100, OrderTypes.LIMIT, OrderTypes.FOK, 0L, 0,
            (byte) 0, (byte) 0, 0L);
        send(InputEvent.TYPE_ORDER_NEW, A, buy, 1, P100, OrderTypes.LIMIT, OrderTypes.FOK, 0L, 0,
            (byte) 0, (byte) 0, 0L);
        // f) a trailing stop: watermark update, latch, drain, remainder cancelled
        send(InputEvent.TYPE_ORDER_NEW, A, sell, 1, 0L, OrderTypes.TRAILING_STOP, OrderTypes.GTC, 0L, 0,
            (byte) 0, OrderTypes.TRAIL_AMOUNT, CENT);
        limit(B, buy, 1, P100 - CENT);
        limit(C, sell, 1, P100 - CENT);
    }

    @Test
    void typedOrderHotPathIsAllocationFreeInSteadyState() {
        final RingBuffer<OutputEvent> ring = RingBuffer.createSingleProducer(OutputEvent::newInstance,
            1 << 14, new BlockingWaitStrategy());
        final BlpRiskState risk = new BlpRiskState(64, 16, 4096, 1024, HUGE, 1_000_000, HUGE, HUGE,
            new RiskMetrics());
        for (final int acct : new int[] { A, B, C, D }) {
            risk.putAccount(acct, true);
        }
        risk.putSecurity(SEC, true);
        risk.putLimits(1_000_000_000, HUGE);
        engine = new MatchingEngine(new OutputPublisher(ring), new HotPathMetrics(), 16, 0, 8192, 64,
            1024, risk);
        engine.setBookGeometry(1 << 12, CENT);
        engine.overrideBookTickPx(SEC, CENT);

        final int warmup = Integer.getInteger("gate.warmupEvents", 250_000) / 20;
        final int measured = Integer.getInteger("gate.steadyStateEvents", 1_000_000) / 20;
        for (int i = 0; i < warmup; i++) {
            cycle();
        }
        assertEquals(0, engine.pendingCount(SEC), "the cycle returns to empty");
        assertEquals(0, engine.pegCount(SEC));
        final com.sun.management.ThreadMXBean mx =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long tid = Thread.currentThread().threadId();
        mx.getThreadAllocatedBytes(tid);
        final long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < measured; i++) {
            cycle();
        }
        final long allocated = mx.getThreadAllocatedBytes(tid) - before;
        final long triggered = engine.ordersTriggered();
        assertEquals(0L, allocated, "typed-order steady state must allocate nothing (" + measured + " cycles)");
        // non-vacuous: the measured window really exercised the typed paths
        assertEquals(2L * (warmup + measured), triggered, "stop + trailing trigger every cycle");
        assertEquals((long) (warmup + measured), engine.pegReprices(), "one peg reprice every cycle");
        assertEquals(0L, engine.fokInvariantBreaches());
    }
}
