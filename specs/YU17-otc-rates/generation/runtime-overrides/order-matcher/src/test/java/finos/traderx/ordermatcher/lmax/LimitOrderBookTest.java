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
    // A third account for aggressors in scenarios that are about price/time priority rather than
    // self-trade prevention: with cancel-oldest STP (ADR-057) an aggressor sharing an account with
    // any resting order it meets now cancels it instead of filling it.
    private static final int ACCT3 = 62654;
    private static final int SEC = 2;
    private static final long PX150 = 150_000_000L;   // $150.00 — on the cent grid
    private static final long CENT = 10_000L;

    private RingBuffer<OutputEvent> ring;
    private MatchingEngine engine;
    private long nextSeq = 1;
    private long drainedTo = -1;

    /**
     * A 64-level band on a CENT grid, which is what every band case below is written against.
     *
     * <p>YU17 (format-8 price-derived grid): {@code setBookGeometry} alone no longer produces it.
     * The global grid became PROVISIONAL at the mint -- it applies only to a security with no
     * reference price -- and every case here ticks a reference, which the decade map then turns
     * into a 0.001 grid and a ten-times narrower band. Nothing in this file is about the map; they
     * are about {@code bandSlot}, {@code rebase} and stranded cancels, so the grid is PINNED with
     * the same ADR-060 category override bonds use, which outranks the map by design. Without the
     * pin these cases keep passing while silently measuring a $0.064 band where their constants
     * and comments say $0.64 -- coverage drift, not a failure, which is the reason for the pin
     * rather than a rescale of eight tests.
     */
    private void bandGeometry() {
        engine.setBookGeometry(64, CENT);
        engine.overrideBookTickPx(SEC, CENT);
    }

    private MatchingEngine newEngine(boolean withRisk) {
        ring = RingBuffer.createSingleProducer(OutputEvent::newInstance, 1 << 14, new BlockingWaitStrategy());
        BlpRiskState risk = null;
        if (withRisk) {
            risk = new BlpRiskState(64, 16, 1024, 128, Long.MAX_VALUE / 4, 1_000_000,
                Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, new RiskMetrics());
            risk.putAccount(ACCT, true);
            risk.putAccount(ACCT2, true);
            risk.putAccount(ACCT3, true);
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

    /** Sequenced atomic replace (ADR-058): new TOTAL quantity and new limit price, same orderRef. */
    private void replace(int orderRef, int newQty, long newLimitPx) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_REPLACE;
        e.orderRef = orderRef;
        e.qty = newQty;
        e.limitPx = newLimitPx;
        e.eventTimeMillis = 1_000 + nextSeq;
        engine.onEvent(e, nextSeq++, true);
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

        int buy = limit(ACCT3, InputEvent.SIDE_BUY, 250, PX150 + CENT);
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
        bandGeometry();   // band = 64 cents around the anchor
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

        int buy = limit(ACCT3, InputEvent.SIDE_BUY, 200, PX150);
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

    // ----- self-trade prevention (ADR-057: cancel-oldest) -------------------------------------

    @Test
    void selfMatchCancelsTheRestingOrderAndLetsTheAggressorReachRealLiquidity() {
        newEngine(false);
        // The ADR's worked scenario: A(other) 30 and S(self) 40 at 150.00, B(other) 50 at 150.02.
        int other30 = limit(ACCT2, InputEvent.SIDE_SELL, 30, PX150);
        int self40 = limit(ACCT, InputEvent.SIDE_SELL, 40, PX150);
        int other50 = limit(ACCT2, InputEvent.SIDE_SELL, 50, PX150 + 2 * CENT);
        drain();

        int aggressor = limit(ACCT, InputEvent.SIDE_BUY, 100, PX150 + 5 * CENT);
        List<Emitted> events = drain();

        // The self order is CANCELED, not filled, and says why.
        Emitted selfUpdate = lastOrderUpdate(events, self40);
        assertEquals(OutputEvent.KIND_ORDER_CANCELED, selfUpdate.kind());
        assertEquals((byte) RiskReason.SELF_TRADE_PREVENTED.ordinal(), selfUpdate.riskReason());
        assertTrue((selfUpdate.flags() & OutputEvent.FLAG_RESTING_UPDATE) != 0,
            "an unsolicited STP cancel must never complete the aggressor's own ack correlation");

        // This is the whole argument for cancel-oldest: the aggressor reaches BOTH genuine
        // counterparties, including the one queued behind its own stale quote.
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, other30).kind());
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, other50).kind());
        assertEquals(20, lastOrderUpdate(events, aggressor).remainingQty(), "30 + 50 filled, 20 rests");
        assertEquals(1, engine.countSelfTradesPrevented());
        // Nothing self-traded: 30 + 50 on each side.
        assertEquals(4, engine.tradeCounter());
    }

    @Test
    void selfMatchReleasesTheCancelledOrdersReservationExactlyOnce() {
        newEngine(true);
        int self = limit(ACCT, InputEvent.SIDE_SELL, 40, PX150);
        long reservedWhileResting = engine.riskState().reservedSellNotional(ACCT);
        assertNotEquals(0L, reservedWhileResting, "a resting sell holds a reservation");
        limit(ACCT, InputEvent.SIDE_BUY, 40, PX150);   // self-marketable: STP cancels `self`
        assertEquals(OutputEvent.KIND_ORDER_CANCELED, lastOrderUpdate(drain(), self).kind());
        assertEquals(0L, engine.riskState().reservedSellNotional(ACCT),
            "the STP cancel released the resting order's exposure, like any other cancel");
        assertEquals(0, engine.tradeCounter(), "a self-trade booked nothing");
    }

    @Test
    void aWholeSelfStackedLevelIsClearedInOneApplyWithoutWedging() {
        // The termination property: cross() re-reads headAt each iteration, so a policy that
        // skipped instead of removing would spin here forever on every member and on replay.
        newEngine(false);
        for (int i = 0; i < 50; i++) {
            limit(ACCT, InputEvent.SIDE_SELL, 10, PX150);
        }
        drain();
        limit(ACCT, InputEvent.SIDE_BUY, 500, PX150);
        drain();
        assertEquals(50, engine.countSelfTradesPrevented());
        assertEquals(0, engine.tradeCounter());
        assertEquals(1, engine.book(SEC).openOrders(), "only the aggressor is left resting");
    }

    // ----- atomic replace (ADR-058) -----------------------------------------------------------

    @Test
    void replaceRepricesInOneApplyKeepingTheOrderRefAndLosingQueuePriority() {
        newEngine(false);
        int mine = limit(ACCT, InputEvent.SIDE_BUY, 100, PX150);
        int behind = limit(ACCT2, InputEvent.SIDE_BUY, 100, PX150);   // queued behind `mine`
        drain();

        replace(mine, 100, PX150 + CENT);   // reprice up: leaves 150.00, joins 150.01
        Emitted ack = lastOrderUpdate(drain(), mine);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, ack.kind());
        assertEquals(mine, ack.orderRef(), "the order keeps its ref: identity is never absent");
        assertTrue((ack.flags() & OutputEvent.FLAG_REPLACE) != 0);
        assertEquals(2, engine.book(SEC).openOrders(), "one order in, one order out — not two orders");

        // Now the only 150.00 bid is the OTHER account's, so a seller hitting 150.00 meets it.
        limit(ACCT2, InputEvent.SIDE_SELL, 100, PX150 + CENT);
        List<Emitted> events = drain();
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, mine).kind(),
            "repriced to the better level, so it is hit first");
        assertTrue(events.stream().noneMatch(e -> e.orderRef() == behind),
            "the order queued behind is untouched: it emits nothing and still rests at 150.00");
        assertEquals(1, engine.book(SEC).openOrders());
    }

    @Test
    void strictSizeDownAtTheSamePriceKeepsQueuePriority() {
        newEngine(false);
        int first = limit(ACCT, InputEvent.SIDE_BUY, 100, PX150);
        int second = limit(ACCT2, InputEvent.SIDE_BUY, 100, PX150);
        drain();
        replace(first, 60, PX150);          // strict size-down, price unchanged
        drain();

        limit(ACCT2, InputEvent.SIDE_SELL, 60, PX150);
        List<Emitted> events = drain();
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, first).kind(),
            "size-down at an unchanged price keeps time priority, so `first` still fills first");
        assertEquals(60, lastOrderUpdate(events, first).lastFillQty());
        assertTrue(events.stream().noneMatch(e -> e.orderRef() == second),
            "the order behind never traded, so priority really was kept");
        assertEquals(1, engine.book(SEC).openOrders(), "only the untouched order is left resting");
    }

    // ----- the band follows the market (ADR-066) ---------------------------------------------

    private static final long PX180 = 180_000_000L;
    private static final long PX385 = 388_000_000L - 10 * CENT;   // inside a 64-cent band on 388
    private static final long PX388 = 388_000_000L;

    /** The MSFT shape from the 2026-08-19 record: a stray first order anchors the band far
     *  from the market; once the feed says where the market is, a realistic limit is accepted
     *  and the stray resting order is cancelled with the reason on its ack. */
    @Test
    void staleBandReanchorsOnTheFeedAndCancelsWhatItStrands() {
        newEngine(true);
        bandGeometry();
        int stray = limit(ACCT, InputEvent.SIDE_BUY, 100, PX180);   // anchors at 180 (no reference yet)
        drain();
        tick(PX388);                                                 // the feed: the market is at 388
        int real = limit(ACCT2, InputEvent.SIDE_SELL, 100, PX385);
        List<Emitted> events = drain();

        Emitted accepted = lastOrderUpdate(events, real);
        assertNotEquals(OutputEvent.KIND_ORDER_REJECTED, accepted.kind());
        assertEquals(RestingOrder.STATUS_NEW, accepted.status());
        Emitted cancelled = lastOrderUpdate(events, stray);
        assertEquals(OutputEvent.FLAG_CANCEL | OutputEvent.FLAG_RESTING_UPDATE, cancelled.flags());
        assertEquals(RestingOrder.STATUS_CANCELED, cancelled.status());
        assertEquals((byte) RiskReason.PRICE_COLLAR.ordinal(), cancelled.riskReason());
        // The cancel reaches the client BEFORE the new order's own ack (it is unsolicited).
        assertTrue(events.indexOf(cancelled) < events.indexOf(accepted));
        assertEquals(1, engine.bandReanchors());
        assertEquals(1, engine.bandStrandedCancels());
        assertEquals(1, engine.book(SEC).openOrders());
        assertEquals(PX388 / CENT - 32, engine.book(SEC).baseLevel());   // centred on the feed
        // The stray order's reservation was released: the account can take the whole limit again.
        assertNotEquals(OutputEvent.KIND_ORDER_REJECTED,
            lastOrderUpdate(drain_(limit(ACCT, InputEvent.SIDE_BUY, 100, PX388)), orderRefCounter).kind());
    }

    private List<Emitted> drain_(int ignored) {
        return drain();
    }

    /** With a reference already known, the first order anchors on the MARKET, not on itself —
     *  so the stray order is the one refused, and the book never needs repairing. */
    @Test
    void firstOrderAnchorsOnTheReferenceWhenOneExists() {
        newEngine(true);
        bandGeometry();
        tick(PX388);
        int stray = limit(ACCT, InputEvent.SIDE_BUY, 100, PX180);
        Emitted rejected = lastOrderUpdate(drain(), stray);
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, rejected.kind());
        assertEquals((byte) RiskReason.PRICE_COLLAR.ordinal(), rejected.riskReason());
        assertEquals(PX388 / CENT - 32, engine.book(SEC).baseLevel());
        assertEquals(0, engine.bandReanchors());
    }

    /** A limit outside the market's collar is a genuine refusal: no re-anchor, nothing cancelled. */
    @Test
    void limitOutsideTheMarketCollarIsRefusedWithoutMovingTheBand() {
        newEngine(true);
        bandGeometry();
        tick(PX388);
        limit(ACCT, InputEvent.SIDE_BUY, 100, PX388);
        drain();
        int far = limit(ACCT2, InputEvent.SIDE_SELL, 100, PX388 + 40 * CENT);   // > half-band away
        Emitted rejected = lastOrderUpdate(drain(), far);
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, rejected.kind());
        assertEquals((byte) RiskReason.PRICE_COLLAR.ordinal(), rejected.riskReason());
        assertEquals(0, engine.bandReanchors());
        assertEquals(1, engine.book(SEC).openOrders());
    }

    /** Orders that survive a re-anchor keep their level, their FIFO order and their matchability. */
    @Test
    void reanchorPreservesSurvivingLevelsAndTheirTimePriority() {
        newEngine(true);
        bandGeometry();
        int first = limit(ACCT, InputEvent.SIDE_SELL, 100, PX180 + 20 * CENT);    // anchors: band [179.88, 180.52)
        int second = limit(ACCT2, InputEvent.SIDE_SELL, 100, PX180 + 20 * CENT);  // same level, behind
        int stranded = limit(ACCT, InputEvent.SIDE_SELL, 100, PX180 - 10 * CENT); // in band now, not after
        drain();
        tick(PX180 + 30 * CENT);   // market drifted up: a band centred here is [179.98, 180.62)
        int taker = limit(ACCT3, InputEvent.SIDE_BUY, 150, PX180 + 60 * CENT);   // refused by the old band
        List<Emitted> events = drain();
        assertEquals(1, engine.bandReanchors());
        assertEquals(RestingOrder.STATUS_CANCELED, lastOrderUpdate(events, stranded).status());
        // The taker crossed the surviving level in FIFO order: first fully, second partially.
        assertEquals(RestingOrder.STATUS_FILLED, lastOrderUpdate(events, first).status());
        assertEquals(50, lastOrderUpdate(events, second).remainingQty());
        assertEquals(RestingOrder.STATUS_FILLED, lastOrderUpdate(events, taker).status());
        assertEquals(PX180 + 20 * CENT, lastOrderUpdate(events, taker).marketPx());
        assertEquals(1, engine.book(SEC).openOrders());
        long[] px = new long[4];
        long[] qty = new long[4];
        assertEquals(1, engine.book(SEC).depth(InputEvent.SIDE_SELL, px, qty, 4));
        assertEquals(PX180 + 20 * CENT, px[0]);
        assertEquals(50, qty[0]);
    }

    /** A replace may not strand the order it is replacing: refused, the order stands where it was. */
    @Test
    void replaceThatWouldStrandItselfIsRefusedAndTheBandStays() {
        newEngine(true);
        bandGeometry();
        int mine = limit(ACCT, InputEvent.SIDE_BUY, 100, PX180);
        drain();
        tick(PX388);
        replace(mine, 100, PX385);   // admissible on the market's band, which cannot hold 180
        Emitted rejected = lastOrderUpdate(drain(), mine);
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, rejected.kind());
        assertEquals((byte) RiskReason.PRICE_COLLAR.ordinal(), rejected.riskReason());
        assertEquals(RestingOrder.STATUS_NEW, rejected.status());
        assertEquals(0, engine.bandReanchors());
        assertEquals(PX180 / CENT - 32, engine.book(SEC).baseLevel());
        assertEquals(1, engine.book(SEC).openOrders());
    }

    /** Without a feed, the mark (a seeding tick, ADR-051) is the reference; without either the
     *  first limit still anchors — the pre-ADR-066 rule survives as the fallback. */
    @Test
    void markIsTheReferenceWhenThereIsNoFeed() {
        newEngine(false);
        bandGeometry();
        int stray = limit(ACCT, InputEvent.SIDE_BUY, 100, PX180);   // nothing known: anchors itself
        assertEquals(PX180 / CENT - 32, engine.book(SEC).baseLevel());
        tick(PX388);                                                 // seeds the mark (no print yet)
        int real = limit(ACCT2, InputEvent.SIDE_SELL, 100, PX385);
        List<Emitted> events = drain();
        assertNotEquals(OutputEvent.KIND_ORDER_REJECTED, lastOrderUpdate(events, real).kind());
        assertEquals(RestingOrder.STATUS_CANCELED, lastOrderUpdate(events, stray).status());
        assertEquals(PX388 / CENT - 32, engine.book(SEC).baseLevel());
    }

    @Test
    void rejectedReplaceLeavesTheOriginalOrderExactlyAsItWas() {
        newEngine(false);
        bandGeometry();
        int mine = limit(ACCT, InputEvent.SIDE_BUY, 100, PX150);   // anchors the band
        drain();

        replace(mine, 100, PX150 + 64 * CENT);   // outside the band
        Emitted rejected = lastOrderUpdate(drain(), mine);
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, rejected.kind());
        assertEquals((byte) RiskReason.PRICE_COLLAR.ordinal(), rejected.riskReason());
        // The point of atomicity: the order is not gone, it is untouched.
        assertEquals(RestingOrder.STATUS_NEW, rejected.status());
        assertEquals(100, rejected.remainingQty());
        assertEquals(1, engine.book(SEC).openOrders());

        // And it is still tradeable at its ORIGINAL price.
        limit(ACCT2, InputEvent.SIDE_SELL, 100, PX150);
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(drain(), mine).kind());
    }

    @Test
    void rejectedReplaceRestoresTheReservationBitForBit() {
        newEngine(true);
        int mine = limit(ACCT, InputEvent.SIDE_BUY, 100, PX150);
        drain();
        long before = engine.riskState().reservedBuyNotional(ACCT);

        // Must fail INSIDE the risk gate, after the release — a shape check would be rejected
        // before anything was released and would prove nothing about the restore.
        replace(mine, 2_000_000, PX150);   // above maxOrderQuantity -> ORDER_SIZE
        Emitted rejected = lastOrderUpdate(drain(), mine);
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, rejected.kind());
        assertEquals((byte) RiskReason.ORDER_SIZE.ordinal(), rejected.riskReason());
        assertEquals(before, engine.riskState().reservedBuyNotional(ACCT),
            "a rejected replace must leave the account's exposure bit-identical");
        assertEquals(100, rejected.remainingQty());
    }

    @Test
    void replaceOfAnUnknownOrTerminalOrderIsAlwaysARejectNeverASuccess() {
        newEngine(false);
        replace(999_999, 10, PX150);
        assertEquals(OutputEvent.KIND_ORDER_NOT_FOUND, drain().get(0).kind());

        int mine = limit(ACCT, InputEvent.SIDE_SELL, 10, PX150);
        limit(ACCT2, InputEvent.SIDE_BUY, 10, PX150);   // fills `mine`
        drain();
        replace(mine, 20, PX150);
        // A terminal FILLED order must NOT be republished as FILLED here: that is exactly what a
        // replace which crossed on the way in also emits, and the gateway could not tell them apart.
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, lastOrderUpdate(drain(), mine).kind());
    }

    @Test
    void replaceCrossesOnTheWayInAndStpFiresInsideTheSameApplyWithoutLosingTheReplacedOrder() {
        // The compound case neither ADR covered. The replaced order is unlinked before it crosses,
        // so it is an aggressor: cancel-oldest can only remove the participant's OTHER resting
        // orders, never the one they asked to modify.
        newEngine(false);
        int mine = limit(ACCT, InputEvent.SIDE_BUY, 100, PX150 - 5 * CENT);   // far from the market
        int myOtherSell = limit(ACCT, InputEvent.SIDE_SELL, 40, PX150);        // same account
        int realSell = limit(ACCT2, InputEvent.SIDE_SELL, 60, PX150);          // genuine counterparty
        drain();

        replace(mine, 100, PX150);   // now marketable into BOTH sells
        List<Emitted> events = drain();

        assertEquals(OutputEvent.KIND_ORDER_CANCELED, lastOrderUpdate(events, myOtherSell).kind());
        assertEquals((byte) RiskReason.SELF_TRADE_PREVENTED.ordinal(),
            lastOrderUpdate(events, myOtherSell).riskReason());
        assertEquals(OutputEvent.KIND_ORDER_FILLED, lastOrderUpdate(events, realSell).kind());
        // The replaced order survives, partially filled — never "left with nothing".
        Emitted mineFinal = lastOrderUpdate(events, mine);
        assertEquals(OutputEvent.KIND_ORDER_PARTIALLY_FILLED, mineFinal.kind());
        assertEquals(40, mineFinal.remainingQty(), "60 filled against the genuine counterparty");
        assertEquals(1, engine.countSelfTradesPrevented());
        assertEquals(1, engine.book(SEC).openOrders(), "the replaced remainder rests");
    }

    @Test
    void replayReproducesTheIdenticalBookThroughStpAndReplace() {
        long[] a = runStpReplaceSession();
        long[] b = runStpReplaceSession();
        assertEquals(a[0], b[0], "order hash");
        assertEquals(a[1], b[1], "position hash");
        assertEquals(a[2], b[2], "open orders");
        assertEquals(a[3], b[3], "trade counter");
        assertNotEquals(0, a[2], "script must leave a resting book");
    }

    private long[] runStpReplaceSession() {
        newEngine(true);
        orderRefCounter = 0;
        tick(PX150);
        int selfQuote = limit(ACCT, InputEvent.SIDE_SELL, 80, PX150 + CENT);
        limit(ACCT2, InputEvent.SIDE_SELL, 120, PX150 + CENT);
        int mine = limit(ACCT, InputEvent.SIDE_BUY, 200, PX150 - 2 * CENT);
        replace(mine, 200, PX150 + CENT);          // marketable: STP kills selfQuote, fills vs ACCT2
        limit(ACCT2, InputEvent.SIDE_BUY, 50, PX150 - CENT);
        replace(mine, 90, PX150 + CENT);           // size-down of the resting remainder
        cancel(selfQuote);                          // already STP-terminal: republished unchanged
        MatchingEngine.RecoveryDigest d = engine.recoveryDigest();
        return new long[] { d.orderHash(), d.positionHash(), d.openOrders(), d.tradeCounter() };
    }

    // ----- ADR-067: book-derived BBO and mark, the read-only export --------------------------

    @Test
    void bboReportsEachSideIndependentlyAndNeverInventsAMidpoint() {
        newEngine(false);
        assertEquals(Px.NONE, engine.bestBidPx(SEC));   // no book at all
        assertEquals(Px.NONE, engine.bestAskPx(SEC));
        assertEquals(Px.NONE, engine.markPx(SEC));

        limit(ACCT, InputEvent.SIDE_BUY, 5, PX150);     // one-sided: a bid and nothing else
        assertEquals(PX150, engine.bestBidPx(SEC));
        assertEquals(Px.NONE, engine.bestAskPx(SEC));   // the empty side stays absent (q2)

        limit(ACCT2, InputEvent.SIDE_SELL, 5, PX150 + 2 * CENT);
        assertEquals(PX150, engine.bestBidPx(SEC));
        assertEquals(PX150 + 2 * CENT, engine.bestAskPx(SEC));
        assertEquals(Px.NONE, engine.markPx(SEC));      // quoted is not traded

        limit(ACCT3, InputEvent.SIDE_BUY, 5, PX150 + 2 * CENT);   // cross: prints at the ask
        assertEquals(PX150 + 2 * CENT, engine.markPx(SEC));       // the mark is the print (ADR-051)
        assertEquals(Px.NONE, engine.bestAskPx(SEC));             // the ask side emptied
        assertEquals(PX150, engine.bestBidPx(SEC));               // the resting bid remains
    }
}
