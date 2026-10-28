package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-089 / FR-LOB04: a market order reserves at the worse of the mark and the opposite best. The observable is
 * the order-notional cap, set between qty x mark and qty x best: the price the gate sized the order at decides
 * which side of the cap it lands on. Its own class, not LimitOrderBookTest: that file is the YU17 layer, which
 * also renders YU17, whose engine keeps ADR-051's mark-first rule.
 */
class MarketOrderReservationTest {
    private static final int ACCT = 22214, ACCT2 = 44044, SEC = 2, QTY = 100;
    private static final long CENT = 10_000L, PX150 = 150_000_000L;
    private RingBuffer<OutputEvent> ring;
    private MatchingEngine engine;
    private long seq = 1, drainedTo = -1;
    private int refs;

    @Test void buyAgainstAnAskAboveTheMarkIsSizedAtTheAsk() {
        engine(QTY * (PX150 + 10 * CENT));                // cap: $150.10 a share for 100
        tick(PX150);
        rests(limit(ACCT2, InputEvent.SIDE_SELL, QTY / 2, PX150 + 20 * CENT));   // half, so the ask fits the cap
        var end = last(market(ACCT, InputEvent.SIDE_BUY, QTY));
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, end.kind,
            "sized at the $150.00 mark it passes the cap and fills 50 at $150.20, above it");
        assertEquals((byte) RiskReason.ORDER_NOTIONAL.ordinal(), end.riskReason);
    }

    @Test void sellAgainstABidBelowTheMarkIsSizedAtTheBid() {
        engine(QTY * (PX150 - 10 * CENT));                // cap: $149.90 a share
        tick(PX150);
        rests(limit(ACCT2, InputEvent.SIDE_BUY, QTY, PX150 - 20 * CENT));
        var end = last(market(ACCT, InputEvent.SIDE_SELL, QTY));
        assertEquals(OutputEvent.KIND_ORDER_FILLED, end.kind,
            "sized at the $149.80 bid it fills; sized at the $150.00 mark it was refused ORDER_NOTIONAL");
        assertEquals(PX150 - 20 * CENT, end.lastExecPx);
    }

    @Test void emptyOppositeSideStillUsesTheMark() {
        for (byte side : new byte[] {InputEvent.SIDE_BUY, InputEvent.SIDE_SELL}) {
            engine(QTY * PX150);                          // exactly qty x mark: passes at the mark, nothing above
            tick(PX150);
            var end = last(market(ACCT, side, QTY));
            assertEquals(OutputEvent.KIND_ORDER_CANCELED, end.kind, "side " + side + ": validated at the mark, never rests");
            assertEquals(0, end.lastFillQty);
        }
    }

    @Test void anAskBelowTheMarkLeavesTheBuyAtTheMark() {
        engine(QTY * PX150 - 1);                          // a cent-less hair under qty x mark
        tick(PX150);
        rests(limit(ACCT2, InputEvent.SIDE_SELL, QTY / 2, PX150 - 20 * CENT));
        var end = last(market(ACCT, InputEvent.SIDE_BUY, QTY));
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, end.kind, "max(mark, ask) is the mark");
        assertEquals((byte) RiskReason.ORDER_NOTIONAL.ordinal(), end.riskReason);
    }

    // ----- the cluster's apply path in miniature (as LimitOrderBookTest) --------------------------------------

    private void engine(long maxOrderNotional) {
        ring = RingBuffer.createSingleProducer(OutputEvent::newInstance, 1 << 12, new BlockingWaitStrategy());
        BlpRiskState risk = new BlpRiskState(64, 16, 1024, 128, Long.MAX_VALUE / 4, 1_000_000,
            maxOrderNotional, Long.MAX_VALUE / 4, new RiskMetrics());
        risk.putAccount(ACCT, true);
        risk.putAccount(ACCT2, true);
        risk.putSecurity(SEC, true);
        risk.putLimits(50_000_000, Long.MAX_VALUE / 4);
        engine = new MatchingEngine(new OutputPublisher(ring), new HotPathMetrics(), 16, 0, 1024, 64, 1024, risk);
        engine.setBookGeometry(64, CENT);                 // a 64-cent band on a cent grid (LimitOrderBookTest.bandGeometry)
        engine.overrideBookTickPx(SEC, CENT);
        seq = 1; drainedTo = -1;
    }

    private void tick(long px) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK; e.securityId = SEC; e.priceTicks = px; e.eventTimeMillis = 1_000 + seq;
        engine.onEvent(e, seq++, true);
    }

    private int limit(int account, byte side, int qty, long limitPx) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW; e.orderRef = ++refs; e.accountId = account; e.securityId = SEC;
        e.side = side; e.qty = qty; e.limitPx = limitPx; e.eventTimeMillis = 1_000 + seq;
        engine.onEvent(e, seq++, true);
        return e.orderRef;
    }

    private int market(int account, byte side, int qty) { return limit(account, side, qty, Px.NONE); }

    /** Precondition: the opposite side really rests. A refused resting order empties the book, and every case
     *  here would then measure the mark-only fallback, on either engine. */
    private void rests(int orderRef) {
        var u = last(orderRef);
        assertTrue(u.kind != OutputEvent.KIND_ORDER_REJECTED && u.lastFillQty == 0, "resting order did not rest: " + u);
    }

    private record Update(byte kind, byte riskReason, long lastExecPx, int lastFillQty) {}

    private Update last(int orderRef) {
        Update found = null;
        for (long s = drainedTo + 1; s <= ring.getCursor(); s++) {
            OutputEvent e = ring.get(s);
            if (e.orderRef == orderRef && OutputEvent.isOrderLifecycleKind(e.kind)) {
                found = new Update(e.kind, e.riskReason, e.lastExecPx, e.lastFillQty);
            }
        }
        drainedTo = ring.getCursor();
        assertTrue(found != null, "no order update for ref " + orderRef);
        return found;
    }
}
