package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RI-01 baseline (YU18 order-types component, spec Support Matrix): market-order behaviour that stop
 * and stop-limit orders will build on, pinned BEFORE the milestone changes the engine. A triggered
 * STOP becomes a market order (FR-OT07) and the recommended trigger reference is the venue's last
 * trade (FR-OT06, OPEN-2), so both must hold exactly as below today.
 *
 * <p>Additive: overrides no inherited test. Harness mirrors {@code LimitOrderBookTest} (YU17).
 */
class OrderTypesBaselineTest {
    private static final int ACCT = 22214;
    private static final int ACCT2 = 44044;
    private static final int ACCT3 = 62654;
    private static final int SEC = 2;
    private static final long PX150 = 150_000_000L;
    private static final long CENT = 10_000L;

    private RingBuffer<OutputEvent> ring;
    private MatchingEngine engine;
    private long nextSeq = 1;
    private long drainedTo = -1;
    private int orderRefCounter = 0;

    private void newEngine() {
        ring = RingBuffer.createSingleProducer(OutputEvent::newInstance, 1 << 14, new BlockingWaitStrategy());
        engine = new MatchingEngine(new OutputPublisher(ring), new HotPathMetrics(), 16, 0, 1024,
            64, 1024, null);
        nextSeq = 1;
        drainedTo = -1;
    }

    private int order(int accountId, byte side, int qty, long limitPx) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.orderRef = ++orderRefCounter;
        e.accountId = accountId;
        e.securityId = SEC;
        e.side = side;
        e.qty = qty;
        e.limitPx = limitPx;
        e.eventTimeMillis = 1_000 + nextSeq;
        engine.onEvent(e, nextSeq++, true);
        return e.orderRef;
    }

    private int market(int accountId, byte side, int qty) {
        return order(accountId, side, qty, Px.NONE);
    }

    private void tick(long px) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK;
        e.securityId = SEC;
        e.priceTicks = px;
        e.eventTimeMillis = 1_000 + nextSeq;
        engine.onEvent(e, nextSeq++, true);
    }

    record Emitted(byte kind, int orderRef, int remainingQty, long lastExecPx, int lastFillQty,
                   long tradePx, int tradeQty) {}

    private List<Emitted> drain() {
        List<Emitted> out = new ArrayList<>();
        long cursor = ring.getCursor();
        for (long s = drainedTo + 1; s <= cursor; s++) {
            OutputEvent e = ring.get(s);
            out.add(new Emitted(e.kind, e.orderRef, e.remainingQty, e.lastExecPx, e.lastFillQty,
                e.tradePx, e.tradeQty));
        }
        drainedTo = cursor;
        return out;
    }

    private static Emitted lastOrderUpdate(List<Emitted> events, int orderRef) {
        Emitted found = null;
        for (Emitted e : events) {
            if (e.orderRef() == orderRef && OutputEvent.isOrderLifecycleKind(e.kind())) {
                found = e;
            }
        }
        assertTrue(found != null, "no order update for ref " + orderRef);
        return found;
    }

    /**
     * G3: the mark after a multi-level sweep is the LAST fill's level, not the first, the average
     * or the aggressor's limit. This is the value a stop compares against under FR-OT06's
     * recommendation, so a sweep through a stop level must leave the mark at the deepest print.
     */
    @Test
    void aSweepLeavesTheMarkAtTheDeepestLevelItPrinted() {
        newEngine();
        tick(PX150);                                              // seed only; no trade yet
        order(ACCT, InputEvent.SIDE_SELL, 5, PX150 + CENT);       // 150.01
        order(ACCT, InputEvent.SIDE_SELL, 5, PX150 + 2 * CENT);   // 150.02
        order(ACCT, InputEvent.SIDE_SELL, 5, PX150 + 3 * CENT);   // 150.03, left partly unfilled
        drain();

        final int mkt = market(ACCT2, InputEvent.SIDE_BUY, 12);
        final List<Emitted> events = drain();

        assertEquals(PX150 + 3 * CENT, engine.markPx(SEC),
            "mark = the last level printed (150.03), not the first (150.01)");
        final Emitted end = lastOrderUpdate(events, mkt);
        assertEquals(OutputEvent.KIND_ORDER_FILLED, end.kind(), "12 of 15 available: fully filled");
        assertEquals(PX150 + 3 * CENT, end.lastExecPx());
        assertEquals(2, end.lastFillQty(), "5 + 5 + 2");
        assertEquals(1, engine.book(SEC).openOrders(), "the 150.03 ask keeps its 3 remaining");

        tick(PX150 + 50 * CENT);
        assertEquals(PX150 + 3 * CENT, engine.markPx(SEC),
            "once a trade has printed, a market-data tick does not move the mark (ADR-051)");
    }

    /**
     * G4: a market aggressor that meets its OWN account's resting order at the top of book
     * cancels that order (STP cancel-oldest, ADR-057) and keeps going to genuine counterparties
     * behind it. A triggered STOP becomes exactly this aggressor, so FR-OT13's claim that STP
     * never cancels the triggered order rests on this.
     */
    @Test
    void aMarketAggressorCancelsItsOwnRestingOrderAndFillsBehindIt() {
        newEngine();
        final int own = order(ACCT, InputEvent.SIDE_SELL, 10, PX150);           // best ask: same account
        final int other = order(ACCT3, InputEvent.SIDE_SELL, 10, PX150 + CENT); // behind it
        drain();

        final int mkt = market(ACCT, InputEvent.SIDE_BUY, 10);
        final List<Emitted> events = drain();

        assertEquals(OutputEvent.KIND_ORDER_CANCELED, lastOrderUpdate(events, own).kind(),
            "the OLDER, resting self order is the one cancelled");
        final Emitted aggressor = lastOrderUpdate(events, mkt);
        assertEquals(OutputEvent.KIND_ORDER_FILLED, aggressor.kind(),
            "the market aggressor survives STP and fills against the other account");
        assertEquals(PX150 + CENT, aggressor.lastExecPx());
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, other).kind());
        assertEquals(1L, engine.countSelfTradesPrevented());
        assertEquals(PX150 + CENT, engine.markPx(SEC), "the cancelled self order printed nothing");
    }

    /**
     * G3b: a market order with NO opposite depth prints nothing and leaves the mark where it was,
     * so a stop can never be triggered by an order that did not trade.
     */
    @Test
    void aMarketOrderThatCannotTradeLeavesTheMarkUnmoved() {
        newEngine();
        order(ACCT, InputEvent.SIDE_SELL, 1, PX150);
        order(ACCT2, InputEvent.SIDE_BUY, 1, PX150);      // prints 150.00
        drain();
        order(ACCT, InputEvent.SIDE_BUY, 5, PX150 - 5 * CENT);   // bids only now

        final int mkt = market(ACCT3, InputEvent.SIDE_BUY, 5);   // no asks at all
        final Emitted end = lastOrderUpdate(drain(), mkt);

        assertEquals(OutputEvent.KIND_ORDER_CANCELED, end.kind(), "market never rests (FR-LOB04)");
        assertEquals(0, end.lastFillQty());
        assertEquals(PX150, engine.markPx(SEC), "no print, no mark change");
    }
}
