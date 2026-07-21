package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crossing limit-order book (YU13, FR-LOB01..07): price-time priority, genuine
 * two-sided crossing, partial fills, market orders, cancel unlink, grid/band admission,
 * last-trade-price output, resting-update flagging, and replay determinism — all driven
 * through the engine's sequenced input path exactly as the cluster applies it.
 */
class LimitOrderBookTest {
    private static final int ACCT = 22214;
    private static final int ACCT2 = 44044;
    private static final int SEC = 2;
    private static final long PX150 = 150_000_000L;   // $150.00 — on the cent grid
    private static final long CENT = 10_000L;

    private RingBuffer<OutputEvent> ring;
    private MatchingEngine engine;
    private long nextSeq = 1;
    private long drainedTo = -1;

    private MatchingEngine newEngine(boolean withRisk) {
        ring = RingBuffer.createSingleProducer(OutputEvent::newInstance, 1 << 14, new BlockingWaitStrategy());
        BlpRiskState risk = null;
        if (withRisk) {
            risk = new BlpRiskState(64, 16, 1024, 128, Long.MAX_VALUE / 4, 1_000_000,
                Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, new RiskMetrics());
            risk.putAccount(ACCT, true);
            risk.putAccount(ACCT2, true);
            risk.putSecurity(SEC, true);
            risk.putLimits(50_000_000, Long.MAX_VALUE / 4);
        }
        engine = new MatchingEngine(new OutputPublisher(ring), new HotPathMetrics(), 16, 0, 1024,
            64, 1024, risk);
        nextSeq = 1;
        drainedTo = -1;
        return engine;
    }

    // ----- sequenced-input helpers (the cluster's apply path in miniature) --------------------

    private int orderRefCounter = 0;

    private int limit(int accountId, byte side, int qty, long limitPx) {
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
        return limit(accountId, side, qty, Px.NONE);
    }

    private void cancel(int orderRef) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_CANCEL;
        e.orderRef = orderRef;
        e.eventTimeMillis = 1_000 + nextSeq;
        engine.onEvent(e, nextSeq++, true);
    }

    private void tick(long px) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK;
        e.securityId = SEC;
        e.priceTicks = px;
        e.eventTimeMillis = 1_000 + nextSeq;
        engine.onEvent(e, nextSeq++, true);
    }

    /** Field-copy of an emitted slot (slots are reused ring storage). */
    record Emitted(byte kind, int orderRef, int flags, byte status, int remainingQty,
                   long lastExecPx, int lastFillQty, byte riskReason, long tradePx, int tradeQty,
                   long marketPx) {}

    private List<Emitted> drain() {
        List<Emitted> out = new ArrayList<>();
        long cursor = ring.getCursor();
        for (long s = drainedTo + 1; s <= cursor; s++) {
            OutputEvent e = ring.get(s);
            out.add(new Emitted(e.kind, e.orderRef, e.flags, e.status, e.remainingQty,
                e.lastExecPx, e.lastFillQty, e.riskReason, e.tradePx, e.tradeQty, e.marketPx));
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

    // ----- crossing fundamentals --------------------------------------------------------------

    @Test
    void marketableLimitCrossesRestingOppositeAtRestingPrice() {
        newEngine(false);
        int sell = limit(ACCT, InputEvent.SIDE_SELL, 100, PX150);
        drain();
        int buy = limit(ACCT2, InputEvent.SIDE_BUY, 100, PX150 + 2 * CENT); // limit 150.02 crosses 150.00
        List<Emitted> events = drain();

        Emitted restingFill = lastOrderUpdate(events, sell);
        assertEquals(OutputEvent.KIND_ORDER_FILLED, restingFill.kind());
        assertEquals(PX150, restingFill.lastExecPx(), "executes at the RESTING price");
        assertTrue((restingFill.flags() & OutputEvent.FLAG_RESTING_UPDATE) != 0,
            "counterparty update carries the resting flag");

        Emitted aggressorFill = lastOrderUpdate(events, buy);
        assertEquals(OutputEvent.KIND_ORDER_FILLED, aggressorFill.kind());
        assertEquals(PX150, aggressorFill.lastExecPx(), "aggressor gets price improvement");
        assertEquals(0, aggressorFill.flags() & OutputEvent.FLAG_RESTING_UPDATE,
            "direct response never carries the resting flag");

        // Both sides booked a trade at the execution price.
        long trades = events.stream().filter(e -> e.kind() == OutputEvent.KIND_TRADE_BOOKED).count();
        assertEquals(2, trades);
        assertEquals(2, engine.tradeCounter());
    }

    @Test
    void nonMarketableLimitRestsWithoutFilling() {
        newEngine(false);
        limit(ACCT, InputEvent.SIDE_SELL, 100, PX150);
        int buy = limit(ACCT2, InputEvent.SIDE_BUY, 100, PX150 - CENT); // 149.99 < best ask 150.00
        List<Emitted> events = drain();
        Emitted buyAck = lastOrderUpdate(events, buy);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, buyAck.kind());
        assertEquals(RestingOrder.STATUS_NEW, buyAck.status());
        assertEquals(2, engine.book(SEC).openOrders());
        assertEquals(0, engine.tradeCounter());
    }

    @Test
    void bestPriceFirstThenFifoWithinLevel() {
        newEngine(false);
        int sellHigh = limit(ACCT, InputEvent.SIDE_SELL, 100, PX150 + CENT);  // 150.01
        int sellLowFirst = limit(ACCT, InputEvent.SIDE_SELL, 100, PX150);     // 150.00, arrives 1st
        int sellLowSecond = limit(ACCT2, InputEvent.SIDE_SELL, 100, PX150);   // 150.00, arrives 2nd
        drain();

        int buy = limit(ACCT2, InputEvent.SIDE_BUY, 250, PX150 + CENT);
        List<Emitted> events = drain();

        // Price priority: both 150.00 orders fill before the 150.01 order.
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, sellLowFirst).kind());
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, sellLowSecond).kind());
        // Time priority (FIFO) within 150.00: first arrival filled first.
        int firstFillRef = events.stream()
            .filter(e -> (e.flags() & OutputEvent.FLAG_RESTING_UPDATE) != 0)
            .findFirst().orElseThrow().orderRef();
        assertEquals(sellLowFirst, firstFillRef);
        // The 150.01 order fills only the remainder (50 of 100).
        Emitted highFill = lastOrderUpdate(events, sellHigh);
        assertEquals(OutputEvent.KIND_ORDER_PARTIALLY_FILLED, highFill.kind());
        assertEquals(50, highFill.lastFillQty());
        assertEquals(PX150 + CENT, highFill.lastExecPx());
        // Aggressor: exhausted, filled across two price levels.
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, buy).kind());
    }

    @Test
    void partialFillLeavesRemainderAtLevelHead() {
        newEngine(false);
        int sell = limit(ACCT, InputEvent.SIDE_SELL, 300, PX150);
        int buy1 = limit(ACCT2, InputEvent.SIDE_BUY, 100, PX150);
        List<Emitted> events = drain();
        Emitted partial = lastOrderUpdate(events, sell);
        assertEquals(OutputEvent.KIND_ORDER_PARTIALLY_FILLED, partial.kind());
        assertEquals(200, partial.remainingQty());
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, buy1).kind());

        // Remainder keeps queue priority: the next buy fills the SAME order.
        int buy2 = limit(ACCT2, InputEvent.SIDE_BUY, 200, PX150);
        events = drain();
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, sell).kind());
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, buy2).kind());
        assertEquals(0, engine.book(SEC).openOrders());
    }

    @Test
    void aggressorRemainderRestsOnItsOwnSide() {
        newEngine(false);
        limit(ACCT, InputEvent.SIDE_SELL, 100, PX150);
        int buy = limit(ACCT2, InputEvent.SIDE_BUY, 250, PX150);
        List<Emitted> events = drain();
        Emitted after = lastOrderUpdate(events, buy);
        assertEquals(OutputEvent.KIND_ORDER_PARTIALLY_FILLED, after.kind());
        assertEquals(150, after.remainingQty());
        // The remainder is now the best bid.
        long[] px = new long[4];
        long[] qty = new long[4];
        assertEquals(1, engine.bookDepth(SEC, InputEvent.SIDE_BUY, px, qty, 4));
        assertEquals(PX150, px[0]);
        assertEquals(150, qty[0]);
    }

    // ----- market orders ----------------------------------------------------------------------

    @Test
    void marketOrderFillsThenCancelsRemainder() {
        newEngine(false);
        int sell = limit(ACCT, InputEvent.SIDE_SELL, 100, PX150);
        int mkt = market(ACCT2, InputEvent.SIDE_BUY, 250);
        List<Emitted> events = drain();
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, sell).kind());
        Emitted end = lastOrderUpdate(events, mkt);
        assertEquals(OutputEvent.KIND_ORDER_CANCELED, end.kind(), "market never rests (FR-LOB04)");
        assertEquals(0, end.remainingQty());
        assertEquals(100, end.lastFillQty());
        assertEquals(PX150, end.lastExecPx());
        assertEquals(0, engine.book(SEC).openOrders());
    }

    @Test
    void marketOrderOnUnpricedEmptyMarketFailsClosed() {
        newEngine(true);
        int mkt = market(ACCT, InputEvent.SIDE_BUY, 100);
        List<Emitted> events = drain();
        Emitted rejected = lastOrderUpdate(events, mkt);
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, rejected.kind());
        assertEquals((byte) RiskReason.PRICE_MISSING.ordinal(), rejected.riskReason());
    }

    @Test
    void marketOrderValidatesAgainstOppositeBestWhenNoTradeYet() {
        newEngine(true);
        limit(ACCT, InputEvent.SIDE_SELL, 100, PX150);
        int mkt = market(ACCT2, InputEvent.SIDE_BUY, 100);
        List<Emitted> events = drain();
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, mkt).kind());
    }

    // ----- admission: grid + band -------------------------------------------------------------

    @Test
    void offGridLimitRejectsInvalid() {
        newEngine(false);
        int ref = limit(ACCT, InputEvent.SIDE_BUY, 100, PX150 + 1);  // sub-cent
        Emitted rejected = lastOrderUpdate(drain(), ref);
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, rejected.kind());
        assertEquals((byte) RiskReason.INVALID.ordinal(), rejected.riskReason());
    }

    @Test
    void outOfBandLimitRejectsPriceCollar() {
        newEngine(false);
        engine.setBookGeometry(64, CENT);   // band = 64 cents around the anchor
        limit(ACCT, InputEvent.SIDE_BUY, 100, PX150);   // anchors mid-band at 150.00
        int far = limit(ACCT, InputEvent.SIDE_BUY, 100, PX150 + 64 * CENT); // beyond band top
        Emitted rejected = lastOrderUpdate(drain(), far);
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, rejected.kind());
        assertEquals((byte) RiskReason.PRICE_COLLAR.ordinal(), rejected.riskReason());
        // The in-band order is untouched.
        assertEquals(1, engine.book(SEC).openOrders());
    }

    // ----- cancel ----------------------------------------------------------------------------

    @Test
    void cancelUnlinksMidLevelAndCrossSkipsIt() {
        newEngine(false);
        int first = limit(ACCT, InputEvent.SIDE_SELL, 100, PX150);
        int middle = limit(ACCT, InputEvent.SIDE_SELL, 100, PX150);
        int lastOrder = limit(ACCT2, InputEvent.SIDE_SELL, 100, PX150);
        cancel(middle);
        drain();

        int buy = limit(ACCT2, InputEvent.SIDE_BUY, 200, PX150);
        List<Emitted> events = drain();
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, first).kind());
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, lastOrder).kind());
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, buy).kind());
        // The canceled order never traded.
        assertTrue(events.stream().noneMatch(e -> e.orderRef() == middle
            && (e.kind() == OutputEvent.KIND_ORDER_FILLED
                || e.kind() == OutputEvent.KIND_ORDER_PARTIALLY_FILLED)));
        assertEquals(0, engine.book(SEC).openOrders());
    }

    // ----- price semantics (ADR-051) ---------------------------------------------------------

    @Test
    void ticksNeverTriggerFills() {
        newEngine(false);
        int buy = limit(ACCT, InputEvent.SIDE_BUY, 100, PX150);
        tick(PX150 - CENT);   // "in the money" under the retired price-trigger policy
        tick(PX150 - CENT);
        List<Emitted> events = drain();
        Emitted state = lastOrderUpdate(events, buy);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, state.kind());
        assertEquals(RestingOrder.STATUS_NEW, state.status());
        assertEquals(1, engine.book(SEC).openOrders());
        assertEquals(0, engine.tradeCounter());
    }

    @Test
    void lastPriceIsTheLastTradeAndTicksOnlySeedIt() {
        newEngine(false);
        tick(PX150 + 5 * CENT);                 // seeds the mark: no trade yet
        limit(ACCT, InputEvent.SIDE_SELL, 100, PX150);
        limit(ACCT2, InputEvent.SIDE_BUY, 100, PX150);
        drain();
        tick(PX150 + 9 * CENT);                 // market data no longer moves the mark
        int probe = limit(ACCT, InputEvent.SIDE_SELL, 10, PX150 + CENT);
        Emitted ack = lastOrderUpdate(drain(), probe);
        assertEquals(PX150, ack.marketPx(), "mark is the last TRADE price, not the last tick");
    }

    // ----- replay determinism (FR-LOB06) -----------------------------------------------------

    @Test
    void identicalInputSequenceReproducesIdenticalBook() {
        long[] digestA = runScriptedSession();
        long[] digestB = runScriptedSession();
        assertEquals(digestA[0], digestB[0], "order hash");
        assertEquals(digestA[1], digestB[1], "position hash");
        assertEquals(digestA[2], digestB[2], "open orders");
        assertEquals(digestA[3], digestB[3], "trade counter");
        assertNotEquals(0, digestA[2], "script must leave a resting book");
    }

    private long[] runScriptedSession() {
        newEngine(true);
        orderRefCounter = 0;
        tick(PX150);
        limit(ACCT, InputEvent.SIDE_SELL, 300, PX150 + CENT);
        limit(ACCT, InputEvent.SIDE_SELL, 200, PX150 + 2 * CENT);
        limit(ACCT2, InputEvent.SIDE_BUY, 250, PX150 + CENT);      // crosses 250 of the 300
        int toCancel = limit(ACCT2, InputEvent.SIDE_BUY, 400, PX150 - CENT); // rests
        market(ACCT2, InputEvent.SIDE_BUY, 100);                    // 50 remaining @150.01 + 50 @150.02
        cancel(toCancel);
        limit(ACCT, InputEvent.SIDE_BUY, 75, PX150 - 3 * CENT);     // rests
        MatchingEngine.RecoveryDigest d = engine.recoveryDigest();
        return new long[] { d.orderHash(), d.positionHash(), d.openOrders(), d.tradeCounter() };
    }
}
