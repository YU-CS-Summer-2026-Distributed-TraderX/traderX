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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YU18 order types, engine level (spec components/order-types, SC-OT06..47). Every fixture sets
 * its own grid (0.01), band, credit and accounts, so each case reaches the property it names.
 * Prices are written as P(100.00) = 100_000_000 Px. MECS-level cases (PRE_OPEN, snapshot, FIX,
 * REST) live in {@code cluster.OrderTypesServiceTest}.
 */
class OrderTypesEngineTest {
    static final int A = 1001;
    static final int B = 1002;
    static final int C = 1003;
    static final int D = 1004;
    static final int E = 1005;
    static final int F = 1006;
    static final int G = 1007;
    static final int SEC = 2;
    static final long CENT = 10_000L;
    static final byte BUY = InputEvent.SIDE_BUY;
    static final byte SELL = InputEvent.SIDE_SELL;
    static final long HUGE = Long.MAX_VALUE / 4;

    static long p(double px) {
        return Math.round(px * 100) * CENT;
    }

    RingBuffer<OutputEvent> ring;
    MatchingEngine engine;
    BlpRiskState risk;
    long nextSeq = 1;
    long drainedTo = -1;
    int nextRef = 0;
    final List<Out> all = new ArrayList<>();

    record Out(byte kind, int ref, int flags, byte status, byte reason, int remaining, long lastPx,
               int lastQty, long tradePx, int tradeQty, int account) {
        boolean triggered() {
            return (flags & OutputEvent.FLAG_TRIGGERED) != 0;
        }
    }

    void newEngine(long credit) {
        ring = RingBuffer.createSingleProducer(OutputEvent::newInstance, 1 << 16, new BlockingWaitStrategy());
        risk = new BlpRiskState(64, 16, 4096, 1024, credit, 1_000_000, HUGE, HUGE, new RiskMetrics());
        for (int acct : new int[] { A, B, C, D, E, F, G }) {
            risk.putAccount(acct, true);
        }
        risk.putSecurity(SEC, true);
        risk.putLimits(50_000_000, HUGE);
        engine = new MatchingEngine(new OutputPublisher(ring), new HotPathMetrics(), 16, 0, 1024,
            64, 0, risk);
        engine.setBookGeometry(1 << 15, CENT);
        engine.overrideBookTickPx(SEC, CENT);
        nextSeq = 1;
        drainedTo = -1;
        nextRef = 0;
        all.clear();
    }

    void newEngine() {
        newEngine(HUGE);
    }

    InputEvent ev(byte type) {
        final InputEvent e = new InputEvent();
        e.type = type;
        e.securityId = SEC;
        e.eventTimeMillis = 1_000 + nextSeq;
        return e;
    }

    void apply(InputEvent e) {
        e.seq = nextSeq;
        engine.onEvent(e, nextSeq++, true);
        drain();
    }

    List<Out> drain() {
        final List<Out> got = new ArrayList<>();
        final long cursor = ring.getCursor();
        for (long s = drainedTo + 1; s <= cursor; s++) {
            final OutputEvent o = ring.get(s);
            got.add(new Out(o.kind, o.orderRef, o.flags, o.status, o.riskReason, o.remainingQty,
                o.lastExecPx, o.lastFillQty, o.tradePx, o.tradeQty, o.accountId));
        }
        drainedTo = cursor;
        all.addAll(got);
        return got;
    }

    int order(int acct, byte side, int qty, long limitPx) {
        final InputEvent e = ev(InputEvent.TYPE_ORDER_NEW);
        e.orderRef = ++nextRef;
        e.accountId = acct;
        e.side = side;
        e.qty = qty;
        e.limitPx = limitPx;
        apply(e);
        return e.orderRef;
    }

    int typed(int acct, byte side, int qty, byte type, byte tif, long limit, long stop, int dq,
              byte pegRef, int pegOffset, byte trailMode, long trailValue, long key) {
        final InputEvent e = ev(InputEvent.TYPE_ORDER_NEW);
        e.orderRef = ++nextRef;
        e.accountId = acct;
        e.side = side;
        e.qty = qty;
        e.limitPx = limit;
        e.orderType = type;
        e.tif = tif;
        e.stopPx = stop;
        e.displayQty = dq;
        e.pegRef = pegRef;
        e.pegOffset = pegOffset;
        e.trailMode = trailMode;
        e.trailValue = trailValue;
        e.setClientOrderKey(key);
        apply(e);
        return e.orderRef;
    }

    int stop(int acct, byte side, int qty, long stopPx) {
        return typed(acct, side, qty, OrderTypes.STOP, OrderTypes.GTC, 0, stopPx, 0, (byte) 0, 0, (byte) 0, 0, 0);
    }

    int stopLimit(int acct, byte side, int qty, long stopPx, long limitPx) {
        return typed(acct, side, qty, OrderTypes.STOP_LIMIT, OrderTypes.GTC, limitPx, stopPx, 0, (byte) 0, 0, (byte) 0, 0, 0);
    }

    int trailing(int acct, byte side, int qty, byte mode, long value) {
        return typed(acct, side, qty, OrderTypes.TRAILING_STOP, OrderTypes.GTC, 0, 0, 0, (byte) 0, 0, mode, value, 0);
    }

    int iceberg(int acct, byte side, int qty, int display, long limitPx) {
        return typed(acct, side, qty, OrderTypes.ICEBERG, OrderTypes.GTC, limitPx, 0, display, (byte) 0, 0, (byte) 0, 0, 0);
    }

    int peg(int acct, byte side, int qty, byte ref, int offset, long cap) {
        return typed(acct, side, qty, OrderTypes.PEGGED, OrderTypes.GTC, cap, 0, 0, ref, offset, (byte) 0, 0, 0);
    }

    int limitTif(int acct, byte side, int qty, long limitPx, byte tif) {
        return typed(acct, side, qty, OrderTypes.LIMIT, tif, limitPx, 0, 0, (byte) 0, 0, (byte) 0, 0, 0);
    }

    void cancel(int ref) {
        final InputEvent e = ev(InputEvent.TYPE_ORDER_CANCEL);
        e.orderRef = ref;
        apply(e);
    }

    void replace(int ref, int qty, long limitPx, long stopPx, int dq, int pegOffset, byte trailMode,
                 long trailValue, byte type) {
        replace(ref, qty, limitPx, stopPx, dq, pegOffset, trailMode, trailValue, type, 0L);
    }

    void replace(int ref, int qty, long limitPx, long stopPx, int dq, int pegOffset, byte trailMode,
                 long trailValue, byte type, long key) {
        final InputEvent e = ev(InputEvent.TYPE_ORDER_REPLACE);
        e.setClientOrderKey(key);
        e.orderRef = ref;
        e.qty = qty;
        e.limitPx = limitPx;
        e.orderType = type;
        e.stopPx = stopPx;
        e.displayQty = dq;
        e.pegOffset = pegOffset;
        e.trailMode = trailMode;
        e.trailValue = trailValue;
        apply(e);
    }

    /** One print at {@code px} between two accounts that hold no order-type positions. */
    void trade(long px) {
        order(F, SELL, 1, px);
        order(G, BUY, 1, px);
    }

    void accountControl(int acct, boolean enabled) {
        final InputEvent e = ev(InputEvent.TYPE_ACCOUNT_CONTROL);
        e.accountId = acct;
        e.setControlEnabled(enabled);
        apply(e);
    }

    byte businessDay(byte kind, int date) {
        final InputEvent e = ev(InputEvent.TYPE_BUSINESS_DAY);
        e.side = kind;
        e.qty = date;
        apply(e);
        return engine.lastBusinessDayOutcome();
    }

    long[] st(int ref) {
        final long[] s = engine.orderState(ref);
        assertNotNull(s, "no order " + ref);
        return s;
    }

    byte status(int ref) {
        return (byte) st(ref)[0];
    }

    Out last(int ref) {
        Out found = null;
        for (Out o : all) {
            if (o.ref() == ref && OutputEvent.isOrderLifecycleKind(o.kind())) {
                found = o;
            }
        }
        assertNotNull(found, "no update for " + ref);
        return found;
    }

    List<Out> fills(int ref) {
        final List<Out> out = new ArrayList<>();
        for (Out o : all) {
            if (o.ref() == ref && OutputEvent.isOrderLifecycleKind(o.kind()) && o.lastQty() > 0
                && (o.kind() == OutputEvent.KIND_ORDER_FILLED || o.kind() == OutputEvent.KIND_ORDER_PARTIALLY_FILLED)) {
                out.add(o);
            }
        }
        return out;
    }

    int triggerIndex(int ref) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).ref() == ref && all.get(i).triggered()) {
                return i;
            }
        }
        return -1;
    }

    // ===== trade reference and triggers ==========================================================

    @Test
    void sc06_aTickOrBootstrapMarkIsNotATrade() {
        newEngine();
        final InputEvent t = ev(InputEvent.TYPE_PRICE_TICK);
        t.priceTicks = p(105);
        apply(t);
        final int s = stop(A, BUY, 1, p(101));
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, status(s), "the mark is not the reference");
        final InputEvent t2 = ev(InputEvent.TYPE_PRICE_TICK);
        t2.priceTicks = p(110);
        apply(t2);
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, status(s));
        assertFalse(engine.hasTraded(SEC));
    }

    @Test
    void sc07_aBuyStopTriggersOnAPrintAndSweeps() {
        newEngine();
        trade(p(100));
        final int s = stop(A, BUY, 10, p(101));
        order(B, SELL, 5, p(101));
        order(B, SELL, 10, p(102));
        order(C, BUY, 1, p(101));
        final List<Out> f = fills(s);
        assertEquals(2, f.size());
        assertEquals(p(101), f.get(0).lastPx());
        assertEquals(4, f.get(0).lastQty());
        assertEquals(p(102), f.get(1).lastPx());
        assertEquals(6, f.get(1).lastQty());
        assertEquals(OutputEvent.KIND_ORDER_FILLED, last(s).kind());
        assertTrue(triggerIndex(s) >= 0);
        assertEquals(0L, risk.reservedNotional(A));
    }

    @Test
    void sc08_aStopAlreadyThroughTheLastTradeIsRejected() {
        newEngine();
        trade(p(100));
        final int buy = stop(A, BUY, 1, p(99));
        final int sell = stop(A, SELL, 1, p(101));
        assertEquals(RiskReason.STOP_ALREADY_TRIGGERED.ordinal(), last(buy).reason());
        assertEquals(RestingOrder.STATUS_REJECTED, status(buy));
        assertEquals(RiskReason.STOP_ALREADY_TRIGGERED.ordinal(), last(sell).reason());
        assertEquals(0L, risk.reservedNotional(A));
    }

    @Test
    void sc09_aLatchSurvivesAReversalInTheSameSweep() {
        newEngine();
        trade(p(100));
        final int s = stop(A, SELL, 1, p(99));
        order(B, BUY, 5, p(98));
        order(C, SELL, 1, p(99));
        order(C, SELL, 1, p(101));
        order(D, BUY, 2, p(101));   // prints 99 then 101
        assertTrue(triggerIndex(s) >= 0, "latched on the 99 print");
        assertEquals(OutputEvent.KIND_ORDER_FILLED, last(s).kind());
        assertEquals(p(98), last(s).lastPx());
    }

    List<Out> mixedSides() {
        newEngine();
        final int buy = stop(A, BUY, 1, p(100));
        final int sell = stop(B, SELL, 1, p(100));
        order(C, SELL, 1, p(100));
        order(D, BUY, 1, p(100));
        assertTrue(triggerIndex(buy) >= 0 && triggerIndex(sell) >= 0);
        assertTrue(triggerIndex(buy) < triggerIndex(sell), "same print: admission order, both sides");
        return new ArrayList<>(all);
    }

    @Test
    void sc10_mixedSidesOnOnePrintLatchInAdmissionOrderIdenticallyOnTwoEngines() {
        final List<Out> first = mixedSides();
        final List<Out> second = mixedSides();
        assertEquals(first, second);
        newEngine();
        trade(p(100));
        assertEquals(RiskReason.STOP_ALREADY_TRIGGERED.ordinal(), last(stop(A, BUY, 1, p(100))).reason());
        assertEquals(RiskReason.STOP_ALREADY_TRIGGERED.ordinal(), last(stop(B, SELL, 1, p(100))).reason());
    }

    @Test
    void sc11_aCascadeFiresBothStopsInOneApply() {
        newEngine();
        trade(p(100));
        final int s99 = stop(A, SELL, 1, p(99));
        final int s98 = stop(A, SELL, 1, p(98));
        order(B, BUY, 1, p(99));
        order(B, BUY, 5, p(98));
        final long before = nextSeq;
        order(D, SELL, 1, p(99));
        assertEquals(before + 1, nextSeq);
        assertEquals(OutputEvent.KIND_ORDER_FILLED, last(s99).kind());
        assertEquals(OutputEvent.KIND_ORDER_FILLED, last(s98).kind());
        assertEquals(0, engine.pendingCount(SEC));
    }

    @Test
    void sc12_gaps() {
        newEngine();
        trade(p(100));
        final int sl = stopLimit(A, BUY, 5, p(101), p(101));
        order(B, SELL, 1, p(101));
        order(B, SELL, 10, p(103));
        order(C, BUY, 1, p(101));
        assertEquals(RestingOrder.STATUS_NEW, status(sl), "gapped past its limit: rests as LIMIT");
        assertEquals(p(101), engine.bestBidPx(SEC));

        newEngine();
        trade(p(100));
        final int s = stop(A, SELL, 10, p(99));
        order(B, BUY, 1, p(99));
        order(B, BUY, 3, p(97));
        order(D, SELL, 1, p(99));
        assertEquals(3, fills(s).stream().mapToInt(Out::lastQty).sum());
        assertEquals(OutputEvent.KIND_ORDER_CANCELED, last(s).kind(), "remainder cancelled");
    }

    // ===== risk revalidation and idempotency (FR-OT16, FR-OT38) =================================

    @Test
    void sc13_sc38_creditConsumedBetweenAcceptAndTriggerRejectsWithExactRelease() {
        newEngine(1_995_000_000L);   // A's credit: 1995.00
        trade(p(100));
        final int s = stop(A, BUY, 10, p(101));                   // reserves 1010.00
        assertEquals(1_010_000_000L, risk.reservedNotional(A));
        final int other = order(A, BUY, 10, p(98));               // reserves 980.00 -> 1990.00
        assertEquals(1_990_000_000L, risk.reservedNotional(A));
        final int keys = risk.idempotencyTuples().size();
        order(B, SELL, 1, p(102));
        order(C, BUY, 1, p(102));   // print 102: latch; re-decided at the mark 102 -> 980 + 1020 > 1995
        assertEquals(RestingOrder.STATUS_REJECTED, status(s));
        assertEquals(RiskReason.CREDIT_LIMIT.ordinal(), last(s).reason());
        assertEquals(980_000_000L, risk.reservedNotional(A), "exactly the other order's reservation");
        assertEquals(RestingOrder.STATUS_NEW, status(other));
        assertEquals(keys, risk.idempotencyTuples().size(), "an internal decision remembers nothing");
    }

    @Test
    void sc38_accountDisabledBetweenAcceptAndTrigger() {
        newEngine();
        trade(p(100));
        final int s = stop(A, BUY, 10, p(101));
        accountControl(A, false);
        order(B, SELL, 1, p(101));
        order(C, BUY, 1, p(101));
        assertEquals(RiskReason.ACCOUNT_DISABLED.ordinal(), last(s).reason());
        assertEquals(0L, risk.reservedNotional(A));
        assertEquals(0, fills(s).size());
    }

    @Test
    void sc38_unchangedLimitsAcceptAtTriggerWithoutTouchingIdempotency() {
        newEngine();
        trade(p(100));
        final int s = stop(A, BUY, 1, p(101));
        order(B, SELL, 2, p(101));
        final int keys = risk.idempotencyTuples().size();
        order(C, BUY, 1, p(101));
        assertEquals(OutputEvent.KIND_ORDER_FILLED, last(s).kind());
        assertEquals(keys, risk.idempotencyTuples().size());
    }

    @Test
    void sc39_duplicateRetriesReEmitTheOrderAsItNowStands() {
        newEngine(1_995_000_000L);
        trade(p(100));
        final long key = 0xABCDL;
        final int s = typed(A, BUY, 10, OrderTypes.STOP, OrderTypes.GTC, 0, p(101), 0, (byte) 0, 0, (byte) 0, 0, key);
        final long reserved = risk.reservedNotional(A);
        drain();
        typed(A, BUY, 10, OrderTypes.STOP, OrderTypes.GTC, 0, p(101), 0, (byte) 0, 0, (byte) 0, 0, key);
        final Out retry = all.get(all.size() - 1);
        assertEquals(s, retry.ref(), "the retry answers the ORIGINAL order");
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, retry.status());
        assertEquals(reserved, risk.reservedNotional(A), "no second reservation");
        order(A, BUY, 10, p(98));
        order(B, SELL, 1, p(102));
        order(C, BUY, 1, p(102));   // rejected at trigger (credit)
        typed(A, BUY, 10, OrderTypes.STOP, OrderTypes.GTC, 0, p(101), 0, (byte) 0, 0, (byte) 0, 0, key);
        final Out after = all.get(all.size() - 1);
        assertEquals(s, after.ref());
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, after.kind());
        assertEquals(980_000_000L, risk.reservedNotional(A));
    }

    @Test
    void sc37_theStopGapLimitationIsExactlyLegacyMarketBehaviour() {
        newEngine();
        trade(p(100));
        final int s = stop(A, BUY, 15, p(101));
        order(B, SELL, 5, p(101));
        order(B, SELL, 10, p(120));
        order(C, BUY, 1, p(101));
        final long stopExecuted = risk.executedNotional(A);
        assertEquals(4 * p(101) + 10 * p(120), stopExecuted, "1604.00 executed");
        assertEquals(0L, risk.reservedNotional(A));
        final List<long[]> stopFills = new ArrayList<>();
        for (Out f : fills(s)) {
            stopFills.add(new long[] { f.lastPx(), f.lastQty() });
        }
        assertEquals(OutputEvent.KIND_ORDER_CANCELED, last(s).kind(), "last 1 cancelled");

        // Same book, a legacy MARKET 15 at the same point: normalized fills and economics match.
        newEngine();
        trade(p(100));
        order(B, SELL, 5, p(101));
        order(B, SELL, 10, p(120));
        order(C, BUY, 1, p(101));
        final int m = order(A, BUY, 15, Px.NONE);
        final List<long[]> mktFills = new ArrayList<>();
        for (Out f : fills(m)) {
            mktFills.add(new long[] { f.lastPx(), f.lastQty() });
        }
        assertEquals(stopFills.size(), mktFills.size());
        for (int i = 0; i < stopFills.size(); i++) {
            assertEquals(stopFills.get(i)[0], mktFills.get(i)[0]);
            assertEquals(stopFills.get(i)[1], mktFills.get(i)[1]);
        }
        assertEquals(stopExecuted, risk.executedNotional(A));
    }

    // ===== trailing ==============================================================================

    @Test
    void sc15_aSellTrailFollowsTheHighAndLatchesOnTheReversal() {
        newEngine();
        trade(p(100));
        final int t = trailing(A, SELL, 1, OrderTypes.TRAIL_AMOUNT, p(2));
        assertEquals(p(98), st(t)[4]);
        order(D, BUY, 1, p(100));
        trade(p(103));
        assertEquals(p(103), st(t)[5], "watermark");
        assertEquals(p(101), st(t)[4], "stop");
        trade(p(101.5));
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, status(t));
        trade(p(100.9));
        assertEquals(OutputEvent.KIND_ORDER_FILLED, last(t).kind());
        assertEquals(p(100), last(t).lastPx());
    }

    @Test
    void sc16_percentageTrailRoundsPassivelyAndLatchesAtTheStop() {
        newEngine();
        trade(p(100.37));
        final int t = trailing(A, SELL, 1, OrderTypes.TRAIL_BPS, 150);
        assertEquals(p(98.86), st(t)[4], "floor(100.37 - 1.50555) to the cent");
        order(D, BUY, 1, p(98));
        trade(p(98.86));
        assertTrue(triggerIndex(t) >= 0);
        assertEquals(10_000L, OrderTypes.effectiveTrail(OrderTypes.TRAIL_BPS, 1, 500_000L, CENT),
            "1 bps of 0.50 is raised to one grid tick");
        assertEquals(490_000L, OrderTypes.trailingStop(SELL, OrderTypes.TRAIL_BPS, 1, 500_000L, CENT));
    }

    @Test
    void sc17_checkThenUpdateForOppositeTrailsOnOnePrint() {
        newEngine();
        trade(p(100));
        final int sellTrail = trailing(A, SELL, 1, OrderTypes.TRAIL_AMOUNT, p(2));   // stop 98
        final int buyTrail = trailing(D, BUY, 1, OrderTypes.TRAIL_AMOUNT, p(2));     // stop 102
        trade(p(102));
        assertEquals(p(100), st(sellTrail)[4], "new high: watermark 102, stop 100");
        assertTrue(triggerIndex(buyTrail) >= 0, "crossing: latched, watermark not updated first");
    }

    @Test
    void sc40_derivedPriceValidation() {
        newEngine();
        trade(p(5));
        assertEquals(RiskReason.TRAIL_INVALID.ordinal(),
            last(trailing(A, SELL, 1, OrderTypes.TRAIL_AMOUNT, p(5))).reason());
        assertEquals(RiskReason.TRAIL_INVALID.ordinal(),
            last(trailing(A, SELL, 1, OrderTypes.TRAIL_AMOUNT, p(6))).reason());
        assertEquals(RiskReason.INVALID.ordinal(),
            last(trailing(A, SELL, 1, OrderTypes.TRAIL_BPS, 0)).reason());
        assertEquals(RiskReason.INVALID.ordinal(),
            last(trailing(A, SELL, 1, OrderTypes.TRAIL_BPS, 5001)).reason());
        // A BUY peg with a permitted negative offset whose derived price is not positive.
        order(C, BUY, 1, p(4));
        assertEquals(RiskReason.PEG_PRICE_INVALID.ordinal(),
            last(peg(A, BUY, 1, OrderTypes.PEG_PRIMARY, -10_000, p(5))).reason());
        // Upper bound: a derived buy trail beyond MAX_PRICE_TICKS.
        assertEquals(-1L, OrderTypes.trailingStop(BUY, OrderTypes.TRAIL_AMOUNT,
            OrderTypes.MAX_PRICE_TICKS, OrderTypes.MAX_PRICE_TICKS, CENT));
        assertEquals(OrderTypes.BAD_PRICE, OrderTypes.validate(OrderTypes.LIMIT, OrderTypes.GTC, BUY, 1,
            OrderTypes.MAX_PRICE_TICKS + 1, 0, 0, (byte) 0, 0, (byte) 0, 0));
        assertEquals(OrderTypes.OK, OrderTypes.validate(OrderTypes.LIMIT, OrderTypes.GTC, BUY, 1,
            OrderTypes.MAX_PRICE_TICKS, 0, 0, (byte) 0, 0, (byte) 0, 0));
    }

    @Test
    void sc18_icebergReplenishesToTheTailOfItsLevel() {
        newEngine();
        final int ice = iceberg(B, SELL, 100, 10, p(100));
        final int other = order(C, SELL, 10, p(100));
        final long[] px = new long[1];
        final long[] qty = new long[1];
        engine.bookDepth(SEC, SELL, px, qty, 1);
        assertEquals(20L, qty[0], "depth shows only the displayed 10 + the other 10");
        order(A, BUY, 25, p(100));
        final List<Out> iceFills = fills(ice);
        assertEquals(2, iceFills.size());
        assertEquals(10, iceFills.get(0).lastQty());
        assertEquals(5, iceFills.get(1).lastQty());
        assertEquals(10, fills(other).get(0).lastQty());
        assertTrue(all.indexOf(fills(other).get(0)) < all.indexOf(iceFills.get(1)), "other order before the new tranche");
    }

    @Test
    void sc19_sizeDownKeepsPriorityAndABiggerDisplayLosesIt() {
        newEngine();
        final int ice = iceberg(B, SELL, 100, 10, p(100));
        final int other = order(C, SELL, 10, p(100));
        replace(ice, 60, p(100), 0, 10, 0, (byte) 0, 0, OrderTypes.ICEBERG);
        order(A, BUY, 5, p(100));
        assertEquals(1, fills(ice).size(), "still ahead");
        replace(ice, 60, p(100), 0, 20, 0, (byte) 0, 0, OrderTypes.ICEBERG);
        order(A, BUY, 5, p(100));
        assertEquals(1, fills(other).size(), "the larger display went to the tail");
    }

    @Test
    void sc20_stpCancelsTheWholeIceberg() {
        newEngine();
        final int ice = iceberg(B, SELL, 100, 10, p(100));
        assertEquals(100 * p(100), risk.reservedSellNotional(B));
        order(B, BUY, 5, p(100));
        assertEquals(RestingOrder.STATUS_CANCELED, status(ice));
        assertEquals(RiskReason.SELF_TRADE_PREVENTED.ordinal(), last(ice).reason());
        assertEquals(0L, risk.reservedSellNotional(B));
    }

    @Test
    void sc21_fokCountsHiddenQuantity() {
        newEngine();
        iceberg(B, SELL, 50, 10, p(100));
        final long trades = engine.tradeCounter();
        final int no = limitTif(A, BUY, 51, p(100), OrderTypes.FOK);
        assertEquals(RiskReason.FOK_UNFILLABLE.ordinal(), last(no).reason());
        assertEquals(trades, engine.tradeCounter(), "no prints");
        assertEquals(0L, risk.reservedNotional(A));
        final int yes = limitTif(A, BUY, 50, p(100), OrderTypes.FOK);
        assertEquals(OutputEvent.KIND_ORDER_FILLED, last(yes).kind());
        assertEquals(0L, engine.fokInvariantBreaches());
    }

    @Test
    void sc41_tightCreditFokAcrossTranchesWithInterveningAndOwnOrders() {
        newEngine(3_500_000_000L);
        final int ice = iceberg(B, SELL, 30, 10, p(100));
        final int c = order(C, SELL, 10, p(100));
        final int own = order(A, SELL, 5, p(100));   // reserves 500.00 of A's 3500.00
        final int fok = limitTif(A, BUY, 30, p(100), OrderTypes.FOK);   // 3000.00: exactly fits
        assertEquals(OutputEvent.KIND_ORDER_FILLED, last(fok).kind());
        final List<Out> f = fills(fok);
        assertEquals(3, f.size());
        assertEquals(RestingOrder.STATUS_CANCELED, status(own), "STP met before the new tranche");
        assertEquals(10, fills(c).get(0).lastQty());
        assertEquals(10, st(ice)[1], "B keeps 10 of 30 hidden+displayed");
        assertEquals(0L, risk.reservedNotional(A));
        assertEquals(0L, engine.fokInvariantBreaches());

        newEngine(3_500_000_000L - 1);
        iceberg(B, SELL, 30, 10, p(100));
        order(C, SELL, 10, p(100));
        order(A, SELL, 5, p(100));
        final long trades = engine.tradeCounter();
        final int short1 = limitTif(A, BUY, 30, p(100), OrderTypes.FOK);
        assertEquals(RiskReason.CREDIT_LIMIT.ordinal(), last(short1).reason());
        assertEquals(trades, engine.tradeCounter());

        newEngine();
        final int ice3 = iceberg(B, SELL, 30, 10, p(100));
        order(C, SELL, 10, p(100));
        final int own3 = order(A, SELL, 5, p(100));
        final long before = risk.reservedNotional(A);
        final int big = limitTif(A, BUY, 41, p(100), OrderTypes.FOK);
        assertEquals(RiskReason.FOK_UNFILLABLE.ordinal(), last(big).reason());
        assertEquals(RestingOrder.STATUS_NEW, status(own3), "no STP side effect");
        assertEquals(10L, st(ice3)[2], "no replenishment");
        assertEquals(before, risk.reservedNotional(A), "released exactly");
    }

    @Test
    void sc42_icebergConservation() {
        newEngine();
        final int ice = iceberg(B, SELL, 100, 10, p(100));
        order(A, BUY, 4, p(100));
        conserve(ice, 100, 4, 6);
        order(A, BUY, 6, p(100));
        conserve(ice, 100, 10, 10);
        order(A, BUY, 3, p(100));
        conserve(ice, 100, 13, 7);
        replace(ice, 70, p(100), 0, 10, 0, (byte) 0, 0, OrderTypes.ICEBERG);
        conserve(ice, 70, 13, 7);
        replace(ice, 90, p(100), 0, 20, 0, (byte) 0, 0, OrderTypes.ICEBERG);
        conserve(ice, 90, 13, 20);
    }

    void conserve(int ref, int quantity, int filled, int displayed) {
        final long[] s = st(ref);
        final int remaining = (int) s[1];
        assertEquals(quantity - filled, remaining);
        assertEquals(displayed, s[2]);
        assertEquals(quantity, filled + s[2] + (remaining - s[2]), "quantity = filled + displayed + hidden");
    }

    // ===== pegged ================================================================================

    @Test
    void sc22_primaryBuyPegTracksTheBidUpToItsCapWithoutRedeciding() {
        newEngine();
        order(C, BUY, 1, p(100));
        order(D, SELL, 1, p(105));
        final int pg = peg(A, BUY, 5, OrderTypes.PEG_PRIMARY, 0, p(101));
        assertEquals(p(100), st(pg)[3]);
        assertEquals(5 * p(101), risk.reservedNotional(A));
        order(C, BUY, 1, p(100.2));
        assertEquals(p(100.2), st(pg)[3]);
        order(C, BUY, 1, p(102));
        assertEquals(p(101), st(pg)[3], "capped");
        assertEquals(5 * p(101), risk.reservedNotional(A), "a buy cap is a ceiling: never re-decided");
    }

    @Test
    void sc23_midpointRoundsPassively() {
        newEngine();
        order(C, BUY, 1, p(100));
        order(D, SELL, 1, p(100.03));
        assertEquals(p(100.01), st(peg(A, BUY, 1, OrderTypes.PEG_MIDPOINT, 0, p(101)))[3]);
        assertEquals(p(100.02), st(peg(B, SELL, 1, OrderTypes.PEG_MIDPOINT, 0, p(99)))[3]);
    }

    @Test
    void sc24_sc25_referenceIgnoresPegsAndSuspendsAndReturnsInAdmissionOrder() {
        newEngine();
        final int bid = order(C, BUY, 1, p(100));
        order(D, SELL, 1, p(105));
        final int a = peg(A, BUY, 1, OrderTypes.PEG_PRIMARY, 0, p(101));
        final int b = peg(B, BUY, 1, OrderTypes.PEG_PRIMARY, 0, p(101));
        cancel(bid);
        assertEquals(RestingOrder.STATUS_SUSPENDED, status(a), "the other peg is not a reference");
        assertEquals(RestingOrder.STATUS_SUSPENDED, status(b));
        assertEquals(RestingOrder.SUSPEND_REFERENCE, st(a)[7]);
        final int mark = all.size();
        order(C, BUY, 1, p(99.5));
        assertEquals(p(99.5), st(a)[3]);
        int ia = -1;
        int ib = -1;
        for (int i = mark; i < all.size(); i++) {
            if (all.get(i).ref() == a && ia < 0 && all.get(i).status() == RestingOrder.STATUS_NEW) {
                ia = i;
            }
            if (all.get(i).ref() == b && ib < 0 && all.get(i).status() == RestingOrder.STATUS_NEW) {
                ib = i;
            }
        }
        assertTrue(ia >= 0 && ia < ib, "walk in admission order");
    }

    List<Out> midpointCascade() {
        newEngine();
        trade(p(100));
        order(C, BUY, 1, p(100));
        order(D, SELL, 1, p(100.02));
        final int st = stop(E, BUY, 1, p(100.01));
        final int a = peg(A, BUY, 2, OrderTypes.PEG_MIDPOINT, 0, p(101));
        peg(B, SELL, 1, OrderTypes.PEG_MIDPOINT, 0, p(99));   // meets A at 100.01: latches E
        assertEquals(OutputEvent.KIND_ORDER_FILLED, last(st).kind(), "E filled at the non-peg ask");
        // E's fill took the only non-peg ask: the reference moved again and A's MIDPOINT lost a side.
        assertEquals(2, engine.lastSettleRounds());
        assertEquals(RestingOrder.STATUS_SUSPENDED, status(a));
        return new ArrayList<>(all);
    }

    @Test
    void sc26_pegPrintLatchesAStopAndTheLoopTerminatesIdentically() {
        assertEquals(midpointCascade(), midpointCascade());
    }

    @Test
    void sc43_aSellPegRisingThroughCreditIsReReservedOrSuspended() {
        newEngine(1_009_000_000L);
        final int a50 = order(C, SELL, 1, p(100.5));
        final int a80 = order(C, SELL, 1, p(100.8));
        order(C, SELL, 1, p(101));
        final int pg = peg(A, SELL, 10, OrderTypes.PEG_PRIMARY, 0, p(100));
        assertEquals(p(100.5), st(pg)[3]);
        assertEquals(p(100.5), st(pg)[6], "riskPx");
        assertEquals(1_005_000_000L, risk.reservedNotional(A));

        cancel(a50);
        assertEquals(p(100.8), st(pg)[3]);
        assertEquals(1_008_000_000L, risk.reservedNotional(A), "re-reserved before the higher price");

        cancel(a80);   // reference 101.00: 10 x 101 = 1010 > 1009
        assertEquals(RestingOrder.STATUS_SUSPENDED, status(pg));
        assertEquals(RestingOrder.SUSPEND_RISK, st(pg)[7]);
        assertEquals(RiskReason.CREDIT_LIMIT.ordinal(), st(pg)[10]);
        assertEquals(1_008_000_000L, risk.reservedNotional(A), "prior tuple restored exactly");
        assertEquals(p(101), engine.bestAskPx(SEC), "not left at the stale, more aggressive price");

        order(C, SELL, 1, p(100.7));
        assertEquals(RestingOrder.STATUS_NEW, status(pg), "re-enters at 100.70 without a decision");
        assertEquals(p(100.7), st(pg)[3]);
        assertEquals(1_008_000_000L, risk.reservedNotional(A));

        order(B, BUY, 3, p(100.7));   // C's 1 first, then 2 from the peg
        assertEquals(8L, st(pg)[1]);
        // The non-peg best is 101.00 again: 8 x 101 + executed 201.40 > 1009 -> suspended, exact.
        assertEquals(RestingOrder.STATUS_SUSPENDED, status(pg));
        assertEquals(806_400_000L, risk.reservedNotional(A), "1008 x 8/10");

        replace(pg, 8, p(100.6), 0, 0, 0, (byte) 0, 0, OrderTypes.PEGGED);   // riskPx' = max(100.60, 101)
        assertEquals(606_000_000L, risk.reservedNotional(A), "6 x 101.00");
        assertEquals(p(101), st(pg)[3]);

        // Control: a buy peg is never re-decided.
        newEngine();
        order(C, BUY, 1, p(100));
        order(D, SELL, 1, p(105));
        peg(A, BUY, 10, OrderTypes.PEG_PRIMARY, 0, p(101));
        final int keys = risk.idempotencyTuples().size();
        order(C, BUY, 1, p(100.5));
        order(C, BUY, 1, p(100.9));
        assertEquals(10 * p(101), risk.reservedNotional(A));
        assertEquals(keys, risk.idempotencyTuples().size());
    }

    // ===== TIF and the trading day ===============================================================

    @Test
    void sc27_sc28_iocAndFok() {
        newEngine();
        order(B, SELL, 3, p(100));
        final int ioc = limitTif(A, BUY, 5, p(100), OrderTypes.IOC);
        assertEquals(OutputEvent.KIND_ORDER_CANCELED, last(ioc).kind());
        assertEquals(3, fills(ioc).stream().mapToInt(Out::lastQty).sum());
        assertEquals(Px.NONE, engine.bestBidPx(SEC), "nothing rests");

        newEngine();
        final int own = order(A, SELL, 5, p(100));
        order(B, SELL, 3, p(100));
        final int fok = limitTif(A, BUY, 5, p(100), OrderTypes.FOK);
        assertEquals(RiskReason.FOK_UNFILLABLE.ordinal(), last(fok).reason());
        assertEquals(RestingOrder.STATUS_NEW, status(own), "own orders untouched");
    }

    @Test
    void sc45_businessDateOwnsDayOrders() {
        newEngine();
        final int refused = limitTif(A, BUY, 1, p(90), OrderTypes.DAY);
        assertEquals(RiskReason.NO_TRADING_DAY.ordinal(), last(refused).reason());
        assertEquals(MatchingEngine.DAY_APPLIED, businessDay(InputEvent.BUSINESS_DAY_START, 20260921));
        final int d1Limit = limitTif(A, BUY, 1, p(90), OrderTypes.DAY);
        trade(p(100));
        final int d1Stop = typed(A, BUY, 1, OrderTypes.STOP, OrderTypes.DAY, 0, p(110), 0, (byte) 0, 0, (byte) 0, 0, 0);
        final int gtc = limitTif(B, BUY, 1, p(90), OrderTypes.GTC);
        assertEquals(MatchingEngine.DAY_APPLIED, businessDay(InputEvent.BUSINESS_DAY_START, 20260922));
        assertEquals(RiskReason.DAY_EXPIRED.ordinal(), last(d1Limit).reason());
        assertEquals(RiskReason.DAY_EXPIRED.ordinal(), last(d1Stop).reason());
        assertTrue(all.indexOf(last(d1Limit)) < all.indexOf(last(d1Stop)), "orderRef order");
        assertEquals(RestingOrder.STATUS_NEW, status(gtc));
        assertEquals(0L, risk.reservedNotional(A));
        final int d2 = limitTif(A, BUY, 1, p(90), OrderTypes.DAY);
        assertEquals(MatchingEngine.DAY_STALE, businessDay(InputEvent.BUSINESS_DAY_END, 20260921), "late DAY_END(D1)");
        assertEquals(RestingOrder.STATUS_NEW, status(d2), "a D2 order is untouched");
        assertEquals(MatchingEngine.DAY_APPLIED, businessDay(InputEvent.BUSINESS_DAY_END, 20260922));
        assertEquals(RiskReason.DAY_EXPIRED.ordinal(), last(d2).reason());
        assertEquals(MatchingEngine.DAY_STALE, businessDay(InputEvent.BUSINESS_DAY_END, 20260922));
        assertEquals(MatchingEngine.DAY_OUT_OF_SEQUENCE, businessDay(InputEvent.BUSINESS_DAY_END, 20260923));
        assertEquals(RiskReason.NO_TRADING_DAY.ordinal(), last(limitTif(A, BUY, 1, p(90), OrderTypes.DAY)).reason());
        assertEquals(RestingOrder.STATUS_NEW, status(limitTif(A, BUY, 1, p(90), OrderTypes.GTC)));
        assertEquals(MatchingEngine.DAY_STALE, businessDay(InputEvent.BUSINESS_DAY_START, 20260922));
    }

    // ===== cascades (FR-OT26, FR-OT40) ===========================================================

    /** Two-round cascade reachable from public commands: a print latches a stop, the stop's fill
     *  empties the non-peg best bid, the peg reprices twice. */
    int[] twoRoundCascade(int roundLimit) {
        newEngine();
        engine.setOrderTypeLimits(3, 2, roundLimit);
        trade(p(100.5));
        order(C, BUY, 1, p(100));
        order(F, BUY, 1, p(99));
        order(F, BUY, 1, p(98));
        final int pg = peg(A, BUY, 1, OrderTypes.PEG_PRIMARY, 0, p(101));
        final int st = stop(D, SELL, 1, p(100));
        final int far = stop(D, SELL, 1, p(90));
        order(E, SELL, 1, p(100));   // print 100 -> latch st; round 1: peg to 99, st sells into F@99;
                                     // round 2: peg to 98
        return new int[] { pg, st, far };
    }

    @Test
    void sc46_theCascadeCompletesWithinTheProvenBound() {
        final int[] refs = twoRoundCascade(0);
        assertEquals(2, engine.lastSettleRounds(), "non-vacuous: two rounds ran");
        assertTrue(engine.lastSettleRounds() <= 3 * 3 + 8 + 6);
        assertEquals(p(98), st(refs[0])[3]);
        assertEquals(OutputEvent.KIND_ORDER_FILLED, last(refs[1]).kind());
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, status(refs[2]));
        assertEquals(0, engine.triggerQueueDepth());
        assertEquals(0L, engine.cascadeLimitEvents());
        // capacities are enforced at admission, never by truncation
        stop(D, SELL, 1, p(89));
        stop(D, SELL, 1, p(88));
        assertEquals(RiskReason.CAPACITY.ordinal(), last(stop(D, SELL, 1, p(87))).reason());
    }

    @Test
    void sc47_exhaustionHasAnExactDeterministicOutcome() {
        final int[] refs = twoRoundCascade(1);
        assertEquals(1L, engine.cascadeLimitEvents());
        assertEquals(OutputEvent.KIND_ORDER_FILLED, last(refs[1]).kind(), "committed fills stand");
        assertEquals(RestingOrder.STATUS_CANCELED, status(refs[0]));
        assertEquals(RiskReason.CASCADE_LIMIT.ordinal(), last(refs[0]).reason());
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, status(refs[2]), "unlatched stops stay pending");
        assertEquals(0, engine.triggerQueueDepth());
        assertEquals(0L, risk.reservedNotional(A), "the cancelled peg released exactly");
        assertEquals(p(90), risk.reservedNotional(D) / 1, "only the far stop's 90.00 remains");
        final List<Out> first = new ArrayList<>(all);
        twoRoundCascade(1);
        assertEquals(first, all, "identical on replay");
    }

    // ===== duplicates (SC-OT32) ==================================================================

    @Test
    void sc32_aDuplicateKeyForEachTypeIsOneOrderAndOneReservation() {
        newEngine();
        trade(p(100));
        order(C, BUY, 1, p(99));
        order(D, SELL, 1, p(101));
        final long[][] shapes = {
            // type, side, limit, stop, display, pegRef, trailMode, trailValue, tif
            { OrderTypes.MARKET, BUY, 0, 0, 0, 0, 0, 0, OrderTypes.IOC },
            { OrderTypes.LIMIT, BUY, p(98), 0, 0, 0, 0, 0, OrderTypes.GTC },
            { OrderTypes.STOP, BUY, 0, p(102), 0, 0, 0, 0, OrderTypes.GTC },
            { OrderTypes.STOP_LIMIT, SELL, p(97), p(98), 0, 0, 0, 0, OrderTypes.GTC },
            { OrderTypes.ICEBERG, SELL, p(103), 0, 2, 0, 0, 0, OrderTypes.GTC },
            { OrderTypes.PEGGED, BUY, p(99.5), 0, 0, OrderTypes.PEG_PRIMARY, 0, 0, OrderTypes.GTC },
            { OrderTypes.TRAILING_STOP, SELL, 0, 0, 0, 0, OrderTypes.TRAIL_AMOUNT, p(1), OrderTypes.GTC },
        };
        long key = 0x5100;
        for (final long[] sh : shapes) {
            key++;
            final int first = typed(A, (byte) sh[1], 5, (byte) sh[0], (byte) sh[8], sh[2], sh[3], (int) sh[4],
                (byte) sh[5], 0, (byte) sh[6], sh[7], key);
            final long reserved = risk.reservedNotional(A);
            final int refsBefore = nextRef;
            typed(A, (byte) sh[1], 5, (byte) sh[0], (byte) sh[8], sh[2], sh[3], (int) sh[4],
                (byte) sh[5], 0, (byte) sh[6], sh[7], key);
            final Out retry = all.get(all.size() - 1);
            assertEquals(first, retry.ref(), "type " + sh[0] + ": the retry answers the original order");
            assertEquals(reserved, risk.reservedNotional(A), "type " + sh[0] + ": no second reservation");
            assertEquals(refsBefore + 1, nextRef);
            assertEquals(null, engine.orderState(nextRef), "type " + sh[0] + ": no second order exists");
        }
    }

    // ===== typed replace (SC-OT33) ===============================================================

    @Test
    void sc33_typedReplaces() {
        newEngine();
        trade(p(100));
        final int s = stop(A, BUY, 5, p(101));
        replace(s, 4, 0, p(102), 0, 0, (byte) 0, 0, OrderTypes.STOP);
        assertEquals(p(102), st(s)[4]);
        assertEquals(4 * p(102), risk.reservedNotional(A));
        final long[] before = st(s);
        replace(s, 4, 0, p(99), 0, 0, (byte) 0, 0, OrderTypes.STOP);   // through the last trade
        assertEquals(RiskReason.STOP_ALREADY_TRIGGERED.ordinal(), all.get(all.size() - 1).reason());
        assertTrue(java.util.Arrays.equals(before, st(s)), "bit-identical");
        replace(s, 4, p(103), p(102), 0, 0, (byte) 0, 0, OrderTypes.LIMIT);   // type change
        assertEquals(RiskReason.INVALID.ordinal(), all.get(all.size() - 1).reason());
        final int t = trailing(B, SELL, 1, OrderTypes.TRAIL_AMOUNT, p(2));
        replace(t, 1, 0, 0, 0, 0, OrderTypes.TRAIL_AMOUNT, p(3), OrderTypes.TRAILING_STOP);
        assertEquals(p(97), st(t)[4], "recomputed from the current watermark");
        replace(t, 1, 0, 0, 0, 0, OrderTypes.TRAIL_AMOUNT, p(100), OrderTypes.TRAILING_STOP);
        assertEquals(RiskReason.TRAIL_INVALID.ordinal(), all.get(all.size() - 1).reason());
    }

    /**
     * Review I2 (FR-OT29): a trailing replace whose NEW stop the current trade is already through
     * is refused before the reservation moves, on both sides and at equality, and a same-key retry
     * is refused identically. The watermark is the best print since admission, so a tighter trail
     * can land behind the market even though the order itself has not triggered.
     */
    @Test
    void i2_trailingReplaceAlreadyThroughIsRefusedAndPreservesTheOrder() {
        newEngine();
        trade(p(110));
        final int sell = trailing(A, SELL, 10, OrderTypes.TRAIL_AMOUNT, p(5));   // stop 105, wm 110
        trade(p(108));                                                            // not through 105
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, status(sell));
        final long[] sellBefore = st(sell);
        final long sellHeld = risk.reservedNotional(A);
        assertEquals(10 * p(105), sellHeld);
        for (final double trail : new double[] { 1, 2 }) {   // stops 109 and 108 (= last trade)
            for (int attempt = 0; attempt < 2; attempt++) {   // the second is a same-key retry
                replace(sell, 10, 0, 0, 0, 0, OrderTypes.TRAIL_AMOUNT, p(trail), OrderTypes.TRAILING_STOP, 77L);
                assertEquals(RiskReason.STOP_ALREADY_TRIGGERED.ordinal(), last(sell).reason(), "trail " + trail);
                assertTrue(java.util.Arrays.equals(sellBefore, st(sell)), "order bit-identical, trail " + trail);
                assertEquals(sellHeld, risk.reservedNotional(A), "aggregates unchanged, trail " + trail);
            }
        }
        replace(sell, 10, 0, 0, 0, 0, OrderTypes.TRAIL_AMOUNT, p(3), OrderTypes.TRAILING_STOP, 78L);
        assertEquals(RiskReason.ACCEPTED.ordinal(), last(sell).reason(), "107 is below the market: allowed");
        assertEquals(p(107), st(sell)[4]);
        assertEquals(10 * p(107), risk.reservedNotional(A));
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, status(sell));

        newEngine();
        trade(p(100));
        final int buy = trailing(B, BUY, 10, OrderTypes.TRAIL_AMOUNT, p(5));      // stop 105, wm 100
        trade(p(102));
        final long[] buyBefore = st(buy);
        final long buyHeld = risk.reservedNotional(B);
        for (final double trail : new double[] { 1, 2 }) {   // stops 101 and 102 (= last trade)
            replace(buy, 10, 0, 0, 0, 0, OrderTypes.TRAIL_AMOUNT, p(trail), OrderTypes.TRAILING_STOP);
            assertEquals(RiskReason.STOP_ALREADY_TRIGGERED.ordinal(), last(buy).reason(), "trail " + trail);
            assertTrue(java.util.Arrays.equals(buyBefore, st(buy)), "order bit-identical, trail " + trail);
            assertEquals(buyHeld, risk.reservedNotional(B));
        }
        replace(buy, 10, 0, 0, 0, 0, OrderTypes.TRAIL_BPS, 300, OrderTypes.TRAILING_STOP);   // 103
        assertEquals(RiskReason.ACCEPTED.ordinal(), last(buy).reason());
        assertEquals(p(103), st(buy)[4]);
        assertEquals(10 * p(103), risk.reservedNotional(B));
    }

    /**
     * Review I4 fast paths. Both skip work only where the skipped fields are already zero: the
     * pool reset clears the typed block of any order that was typed or still linked, and a reused
     * ring slot that last carried a typed update is zeroed by the next legacy update written there.
     */
    @Test
    void i4_fastPathsNeverLeakATypedShape() {
        final RestingOrder o = new RestingOrder();
        o.orderType = OrderTypes.TRAILING_STOP;
        o.tif = OrderTypes.DAY;
        o.stopPx = 5;
        o.trailValue = 7;
        o.watermark = 9;
        o.sessionDate = 20260923;
        o.triggered = true;
        o.repriceMark = 3;
        o.reset();
        assertEquals(0, o.orderType);
        assertEquals(0, o.tif);
        assertEquals(0L, o.stopPx + o.trailValue + o.watermark + o.sessionDate + o.repriceMark);
        assertFalse(o.triggered);
        o.store = RestingOrder.STORE_PENDING;   // a linked order is cleared even if untyped
        o.storeNext = new RestingOrder();
        o.reset();
        assertEquals(RestingOrder.STORE_NONE, o.store);
        assertEquals(null, o.storeNext);

        final RingBuffer<OutputEvent> small =
            RingBuffer.createSingleProducer(OutputEvent::newInstance, 2, new BlockingWaitStrategy());
        final OutputPublisher pub = new OutputPublisher(small);
        final RestingOrder typedOrder = new RestingOrder();
        typedOrder.orderType = OrderTypes.PEGGED;
        typedOrder.tif = OrderTypes.GTC;
        typedOrder.capPx = p(101);
        typedOrder.pegOffset = -2;
        typedOrder.pegRef = OrderTypes.PEG_MIDPOINT;
        typedOrder.suspendReason = RestingOrder.SUSPEND_REFERENCE;
        typedOrder.triggered = true;
        final RestingOrder legacy = new RestingOrder();
        pub.emitOrderUpdate(typedOrder, 1, 0, true, 0, 0);   // slot 0
        pub.emitOrderUpdate(typedOrder, 2, 0, true, 0, 0);   // slot 1
        assertEquals(p(101), small.get(1).typed.capPx, "a typed update carries its shape");
        for (long seq = 2; seq < 6; seq++) {                 // legacy updates wrap both slots twice
            pub.emitOrderUpdate(legacy, seq + 1, 0, true, 0, 0);
            final OutputEvent e = small.get(seq);
            assertEquals(OrderTypes.LEGACY, e.orderType, "slot " + (seq & 1));
            assertEquals(0, e.typed.tif);
            assertEquals(0L, e.typed.capPx + e.typed.pegOffset + e.typed.pegRef + e.typed.suspendReason);
            assertFalse(e.typed.triggered, "slot " + (seq & 1));
        }
    }
}
