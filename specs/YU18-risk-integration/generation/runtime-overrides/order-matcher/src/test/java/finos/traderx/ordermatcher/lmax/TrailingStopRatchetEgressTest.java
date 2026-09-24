package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3 (RI-07): a trailing stop's watermark ratchet must PUBLISH the new stop level (FR-OT19, FR-OT33
 * "stopprice is a TRAILING_STOP's CURRENT level"). Before the fix updateWatermark moved the level
 * with no order update, so every consumer of the egress -- the read model, the blotter -- kept the
 * admission level until the order's next event. Invariant checked here: after every command, the
 * last order update published for each pending trailing stop carries the engine's current level.
 */
class TrailingStopRatchetEgressTest {
    static final int A = 1001;
    static final int D = 1004;
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

    record Up(int ref, int flags, byte status, long stopPx, boolean triggered) { }

    RingBuffer<OutputEvent> ring;
    MatchingEngine engine;
    long nextSeq = 1;
    long drainedTo = -1;
    int nextRef = 0;
    final List<Up> ups = new ArrayList<>();

    void newEngine() {
        ring = RingBuffer.createSingleProducer(OutputEvent::newInstance, 1 << 16, new BlockingWaitStrategy());
        final BlpRiskState risk = new BlpRiskState(64, 16, 4096, 1024, HUGE, 1_000_000, HUGE, HUGE, new RiskMetrics());
        for (int acct : new int[] { A, D, F, G }) {
            risk.putAccount(acct, true);
        }
        risk.putSecurity(SEC, true);
        risk.putLimits(50_000_000, HUGE);
        engine = new MatchingEngine(new OutputPublisher(ring), new HotPathMetrics(), 16, 0, 1024, 64, 0, risk);
        engine.setBookGeometry(1 << 15, CENT);
        engine.overrideBookTickPx(SEC, CENT);
    }

    void apply(InputEvent e) {
        e.securityId = SEC;
        e.eventTimeMillis = 1_000 + nextSeq;
        e.seq = nextSeq;
        engine.onEvent(e, nextSeq++, true);
        for (long s = drainedTo + 1; s <= ring.getCursor(); s++) {
            final OutputEvent o = ring.get(s);
            if (OutputEvent.isOrderLifecycleKind(o.kind)) {
                ups.add(new Up(o.orderRef, o.flags, o.status, o.typed.stopPx, o.typed.triggered));
            }
        }
        drainedTo = ring.getCursor();
    }

    int order(int acct, byte side, int qty, long limitPx) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.orderRef = ++nextRef;
        e.accountId = acct;
        e.side = side;
        e.qty = qty;
        e.limitPx = limitPx;
        apply(e);
        return e.orderRef;
    }

    int trailing(int acct, byte side, byte mode, long value) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.orderRef = ++nextRef;
        e.accountId = acct;
        e.side = side;
        e.qty = 1;
        e.orderType = OrderTypes.TRAILING_STOP;
        e.tif = OrderTypes.GTC;
        e.trailMode = mode;
        e.trailValue = value;
        apply(e);
        return e.orderRef;
    }

    /** One print at {@code px} between two accounts that hold no trailing orders. */
    void trade(long px) {
        order(F, SELL, 1, px);
        order(G, BUY, 1, px);
    }

    long engineStop(int ref) {
        final long[] s = engine.orderState(ref);
        assertNotNull(s, "no order " + ref);
        return s[4];
    }

    Up lastUp(int ref) {
        Up found = null;
        for (Up u : ups) {
            if (u.ref() == ref) {
                found = u;
            }
        }
        assertNotNull(found, "no update for " + ref);
        return found;
    }

    long updatesFor(int ref) {
        return ups.stream().filter(u -> u.ref() == ref).count();
    }

    void assertPublishedIsCurrent(int ref) {
        assertEquals(engineStop(ref), lastUp(ref).stopPx(), "published stop level == engine stop level");
    }

    @Test
    void aSellTrailPublishesEachRatchetAsAnUnsolicitedUpdate() {
        newEngine();
        trade(p(100));
        final int t = trailing(A, SELL, OrderTypes.TRAIL_AMOUNT, p(2));
        assertEquals(p(98), lastUp(t).stopPx(), "admission publishes the first level");
        order(D, BUY, 5, p(90));             // liquidity so later prints do not need the trail
        final long before = updatesFor(t);
        trade(p(103));
        assertEquals(p(101), engineStop(t));
        assertEquals(before + 1, updatesFor(t), "exactly one update for one ratchet");
        final Up u = lastUp(t);
        assertEquals(p(101), u.stopPx());
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, u.status());
        assertFalse(u.triggered());
        assertTrue((u.flags() & OutputEvent.FLAG_RESTING_UPDATE) != 0,
            "unsolicited: the gateway must not read it as the print's own answer");
        trade(p(104.5));
        assertPublishedIsCurrent(t);
        assertEquals(p(102.5), lastUp(t).stopPx());
    }

    @Test
    void aBuyTrailPublishesEachRatchetDownwards() {
        newEngine();
        trade(p(100));
        final int t = trailing(A, BUY, OrderTypes.TRAIL_AMOUNT, p(2));
        assertEquals(p(102), lastUp(t).stopPx());
        trade(p(97));
        assertEquals(p(99), engineStop(t));
        assertPublishedIsCurrent(t);
        assertTrue((lastUp(t).flags() & OutputEvent.FLAG_RESTING_UPDATE) != 0);
        trade(p(95.25));
        assertEquals(p(97.25), lastUp(t).stopPx());
        assertPublishedIsCurrent(t);
    }

    @Test
    void noUpdateWhenThePrintDoesNotMoveTheLevel() {
        newEngine();
        trade(p(100));
        final int sell = trailing(A, SELL, OrderTypes.TRAIL_AMOUNT, p(2));
        order(D, BUY, 5, p(90));
        trade(p(103));
        final long afterRatchet = updatesFor(sell);
        trade(p(101.5));                      // below the watermark: no ratchet, no trigger (stop 101)
        trade(p(103));                        // equal to the watermark: no ratchet
        assertEquals(afterRatchet, updatesFor(sell), "no level change, no update");
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, (byte) engine.orderState(sell)[0]);
    }

    @Test
    void aPercentageTrailWhoseRoundedLevelDoesNotMovePublishesNothing() {
        newEngine();
        trade(p(100));
        final int t = trailing(A, SELL, OrderTypes.TRAIL_BPS, 150);   // stop floor(100 - 1.50) = 98.50
        assertEquals(p(98.5), lastUp(t).stopPx());
        final long before = updatesFor(t);
        trade(p(100.01));                      // watermark rises, floor(100.01 - 1.500150) = 98.50
        assertEquals(p(100.01), engine.orderState(t)[5], "watermark did move");
        assertEquals(p(98.5), engineStop(t), "level did not");
        assertEquals(before, updatesFor(t), "only a level change is published");
        trade(p(100.02));                      // floor(100.02 - 1.500300) = 98.51
        assertEquals(p(98.51), engineStop(t));
        assertPublishedIsCurrent(t);
    }

    @Test
    void publishedLevelTracksTheEngineAcrossAMixedWalk() {
        newEngine();
        trade(p(100));
        final int s1 = trailing(A, SELL, OrderTypes.TRAIL_AMOUNT, p(3));
        final int s2 = trailing(A, SELL, OrderTypes.TRAIL_BPS, 200);
        final int b1 = trailing(D, BUY, OrderTypes.TRAIL_AMOUNT, p(3));
        final int b2 = trailing(D, BUY, OrderTypes.TRAIL_BPS, 200);
        final double[] walk = { 100.4, 101.1, 100.2, 99.3, 101.9, 98.8, 101.2, 100.5, 99.9, 101.4 };
        for (double px : walk) {
            trade(p(px));
            for (int ref : new int[] { s1, s2, b1, b2 }) {
                if (engine.orderState(ref)[0] == RestingOrder.STATUS_PENDING_TRIGGER) {
                    assertPublishedIsCurrent(ref);
                }
            }
        }
    }
}
