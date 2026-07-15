package finos.traderx.ordermatcher.risk;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import finos.traderx.ordermatcher.lmax.HotPathMetrics;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.OutputPublisher;
import finos.traderx.ordermatcher.lmax.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Replay determinism for the risk-gated BLP (FR-IMRG22 / NFR-IMRG03): the same sequenced
 * commands + prices + versioned control events reproduce identical book, position, and risk
 * state — and a snapshot v3 written mid-stream restores identically before the tail replays.
 */
class RiskReplayDeterminismTest {
    private static final int ACCT = 22214;
    private static final int SEC = 2;
    private static final long PX = 100_000_000L;

    private static MatchingEngine newEngine() {
        RingBuffer<OutputEvent> ring = RingBuffer.createSingleProducer(
            OutputEvent::newInstance, 1 << 14, new BlockingWaitStrategy());
        BlpRiskState risk = new BlpRiskState(64, 16, 1024, 128, 1_000_000_000_000L, 10_000,
            1_000_000_000_000L, 30_000L, new RiskMetrics());
        risk.putAccount(ACCT, true);
        risk.putSecurity(SEC, true);
        risk.putLimits(50_000, 1_000_000_000_000L);
        return new MatchingEngine(new OutputPublisher(ring), new HotPathMetrics(), 16, 1000, 1024,
            64, 1024, risk);
    }

    private static InputEvent event(long seq, byte type) {
        InputEvent e = new InputEvent();
        e.seq = seq;
        e.type = type;
        e.eventTimeMillis = 1_000L + seq;
        return e;
    }

    /** A mixed sequence: controls, prices, accepted/rejected/idempotent orders, cancel, trade. */
    private static List<InputEvent> script() {
        List<InputEvent> events = new ArrayList<>();

        InputEvent price = event(0, InputEvent.TYPE_PRICE_TICK);
        price.securityId = SEC;
        price.priceTicks = PX;
        events.add(price);

        InputEvent accepted = event(1, InputEvent.TYPE_ORDER_NEW);
        accepted.orderRef = 1;
        accepted.accountId = ACCT;
        accepted.securityId = SEC;
        accepted.side = InputEvent.SIDE_BUY;
        accepted.qty = 500;
        accepted.limitPx = PX - 1_000_000L;   // out of the money: rests, keeps its reservation
        accepted.setClientOrderKey(101L);
        events.add(accepted);

        // Kill switch armed via a sequenced POLICY control -> next order rejects.
        InputEvent kill = event(2, InputEvent.TYPE_POLICY_CONTROL);
        kill.setControlEnabled(true);
        kill.setControlVersion(2L);
        events.add(kill);

        InputEvent rejected = event(3, InputEvent.TYPE_ORDER_NEW);
        rejected.orderRef = 2;
        rejected.accountId = ACCT;
        rejected.securityId = SEC;
        rejected.side = InputEvent.SIDE_BUY;
        rejected.qty = 100;
        rejected.limitPx = PX;
        rejected.setClientOrderKey(102L);
        events.add(rejected);

        InputEvent disarm = event(4, InputEvent.TYPE_POLICY_CONTROL);
        disarm.setControlEnabled(false);
        disarm.setControlVersion(3L);
        events.add(disarm);

        // Idempotent retry of the accepted order: replays the original decision, no new order.
        InputEvent retry = event(5, InputEvent.TYPE_ORDER_NEW);
        retry.orderRef = 3;
        retry.accountId = ACCT;
        retry.securityId = SEC;
        retry.side = InputEvent.SIDE_BUY;
        retry.qty = 500;
        retry.limitPx = PX - 1_000_000L;
        retry.setClientOrderKey(101L);
        events.add(retry);

        InputEvent second = event(6, InputEvent.TYPE_ORDER_NEW);
        second.orderRef = 4;
        second.accountId = ACCT;
        second.securityId = SEC;
        second.side = InputEvent.SIDE_SELL;
        second.qty = 200;
        second.limitPx = PX + 5_000_000L;     // out of the money for a sell: rests
        second.setClientOrderKey(103L);
        events.add(second);

        InputEvent cancel = event(7, InputEvent.TYPE_ORDER_CANCEL);
        cancel.orderRef = 4;
        events.add(cancel);

        InputEvent trade = event(8, InputEvent.TYPE_TRADE_NEW);
        trade.accountId = ACCT;
        trade.securityId = SEC;
        trade.side = InputEvent.SIDE_BUY;
        trade.qty = 50;
        trade.setClientOrderKey(104L);
        events.add(trade);

        // Restriction control then an order on the restricted security -> RESTRICTED.
        InputEvent restrict = event(9, InputEvent.TYPE_RESTRICTION_CONTROL);
        restrict.securityId = SEC;
        restrict.setControlEnabled(true);
        restrict.setControlVersion(4L);
        events.add(restrict);

        InputEvent restricted = event(10, InputEvent.TYPE_ORDER_NEW);
        restricted.orderRef = 5;
        restricted.accountId = ACCT;
        restricted.securityId = SEC;
        restricted.side = InputEvent.SIDE_BUY;
        restricted.qty = 10;
        restricted.limitPx = PX;
        restricted.setClientOrderKey(105L);
        events.add(restricted);

        return events;
    }

    private static void apply(MatchingEngine engine, List<InputEvent> events) {
        for (InputEvent e : events) {
            engine.onEvent(e, e.seq, true);
        }
    }

    private static void assertSameState(MatchingEngine a, MatchingEngine b) {
        MatchingEngine.RecoveryDigest da = a.recoveryDigest();
        MatchingEngine.RecoveryDigest db = b.recoveryDigest();
        assertEquals(da.openOrders(), db.openOrders());
        assertEquals(da.orderHash(), db.orderHash());
        assertEquals(da.positionHash(), db.positionHash());
        assertEquals(da.tradeCounter(), db.tradeCounter());
        assertEquals(a.riskState().reservedNotional(ACCT), b.riskState().reservedNotional(ACCT));
        assertEquals(a.riskState().executedNotional(ACCT), b.riskState().executedNotional(ACCT));
        assertEquals(a.riskState().policyVersion(), b.riskState().policyVersion());
        assertEquals(a.riskState().killSwitch(), b.riskState().killSwitch());
        // per-order decisions (riskReason at tuple index 12) must match exactly
        List<long[]> ordersA = a.allOrderTuples();
        List<long[]> ordersB = b.allOrderTuples();
        assertEquals(ordersA.size(), ordersB.size());
        for (int i = 0; i < ordersA.size(); i++) {
            assertEquals(ordersA.get(i)[12], ordersB.get(i)[12], "riskReason of order " + ordersA.get(i)[0]);
            assertEquals(ordersA.get(i)[13], ordersB.get(i)[13], "reservedNotional of order " + ordersA.get(i)[0]);
        }
    }

    @Test
    void identicalEventSequenceReproducesIdenticalRiskAndBookState() {
        MatchingEngine live = newEngine();
        MatchingEngine replay = newEngine();
        List<InputEvent> events = script();
        apply(live, events);
        apply(replay, events);
        assertSameState(live, replay);
        // sanity: the script really exercised risk — one live reservation, one rejection
        assertEquals(500L * (PX - 1_000_000L), live.riskState().reservedNotional(ACCT));
    }

    @Test
    void frImrg22InterleavedAccountSecurityRestrictionAndPolicyControlsReplayIdentically() {
        List<InputEvent> events = alternateControlScript();
        MatchingEngine live = newEngine();
        MatchingEngine replay = newEngine();

        apply(live, events);
        apply(replay, events);

        assertSameState(live, replay);
        List<Long> reasons = live.allOrderTuples().stream().map(tuple -> tuple[12]).toList();
        assertEquals(List.of(
            (long) RiskReason.ACCOUNT_DISABLED.ordinal(),
            // The BLP's bounded security table intentionally represents disabled/absent with
            // the same zero slot, so the stable authoritative reason is UNKNOWN_SECURITY.
            (long) RiskReason.UNKNOWN_SECURITY.ordinal(),
            (long) RiskReason.RESTRICTED.ordinal(),
            (long) RiskReason.KILL_SWITCH.ordinal(),
            (long) RiskReason.ACCEPTED.ordinal()), reasons);
    }

    private static List<InputEvent> alternateControlScript() {
        List<InputEvent> events = new ArrayList<>();
        events.add(accountControl(0, false, 2));
        events.add(order(1, 11, 201));
        events.add(accountControl(2, true, 3));
        events.add(securityControl(3, false, 4));
        events.add(order(4, 12, 202));
        events.add(securityControl(5, true, 5));
        events.add(restrictionControl(6, true, 6));
        events.add(order(7, 13, 203));
        events.add(restrictionControl(8, false, 7));
        events.add(policyControl(9, true, 8));
        events.add(order(10, 14, 204));
        events.add(policyControl(11, false, 9));
        events.add(order(12, 15, 205));
        return events;
    }

    private static InputEvent order(long seq, int orderRef, long clientOrderKey) {
        InputEvent order = event(seq, InputEvent.TYPE_ORDER_NEW);
        order.orderRef = orderRef;
        order.accountId = ACCT;
        order.securityId = SEC;
        order.side = InputEvent.SIDE_BUY;
        order.qty = 10;
        order.limitPx = PX - 1_000_000L;
        order.setClientOrderKey(clientOrderKey);
        return order;
    }

    private static InputEvent accountControl(long seq, boolean enabled, long version) {
        InputEvent control = event(seq, InputEvent.TYPE_ACCOUNT_CONTROL);
        control.accountId = ACCT;
        control.setControlEnabled(enabled);
        control.setControlVersion(version);
        return control;
    }

    private static InputEvent securityControl(long seq, boolean enabled, long version) {
        InputEvent control = event(seq, InputEvent.TYPE_SECURITY_CONTROL);
        control.securityId = SEC;
        control.setControlEnabled(enabled);
        control.setControlVersion(version);
        return control;
    }

    private static InputEvent restrictionControl(long seq, boolean restricted, long version) {
        InputEvent control = event(seq, InputEvent.TYPE_RESTRICTION_CONTROL);
        control.securityId = SEC;
        control.setControlEnabled(restricted);
        control.setControlVersion(version);
        return control;
    }

    private static InputEvent policyControl(long seq, boolean killSwitch, long version) {
        InputEvent control = event(seq, InputEvent.TYPE_POLICY_CONTROL);
        control.setControlEnabled(killSwitch);
        control.setControlVersion(version);
        return control;
    }

    @Test
    void snapshotV3PlusTailRestoresIdenticalState(@TempDir Path dir) throws Exception {
        List<InputEvent> events = script();
        int cut = 6;   // snapshot after the idempotent retry, before the sell/cancel/trade tail

        MatchingEngine live = newEngine();
        apply(live, events.subList(0, cut));

        SnapshotStore store = new SnapshotStore(dir);
        BlpRiskState liveRisk = live.riskState();
        store.write(new SnapshotStore.Data(0L, 100, live.tradeCounter(), live.priceTuples(),
            live.positionTuples(), live.allOrderTuples(), -1L, liveRisk.policyTuple(),
            liveRisk.accountTuples(), liveRisk.securityTuples(), liveRisk.idempotencyTuples()));
        apply(live, events.subList(cut, events.size()));

        // Restore: fresh engine + risk from the snapshot, then replay only the tail.
        MatchingEngine restored = newEngine();
        SnapshotStore.Data data = store.read();
        BlpRiskState restoredRisk = restored.riskState();
        restoredRisk.bootstrapPolicy(data.riskPolicy());
        for (long[] a : data.riskAccounts()) {
            restoredRisk.bootstrapAccount((int) a[0], a[1] != 0, a[2]);
        }
        for (long[] s : data.riskSecurities()) {
            restoredRisk.bootstrapSecurity((int) s[0], s[1] != 0, s[2] != 0, s[3], s[4]);
        }
        for (long[] k : data.riskIdempotency()) {
            restoredRisk.bootstrapIdempotency(k[0], (int) k[1], (byte) k[2]);
        }
        for (long[] o : data.orders()) {
            restored.bootstrapOrder((int) o[0], (int) o[1], (int) o[2], (byte) o[3], (int) o[4],
                (int) o[5], o[6], (byte) o[7], (byte) o[12], o[8], (int) o[9], o[10], o[11],
                o[13], (int) o[14]);
        }
        for (long[] p : data.positions()) {
            restored.bootstrapPosition((int) p[0], (int) p[1], (int) p[2], p[3]);
        }
        for (long[] pr : data.prices()) {
            restored.bootstrapPrice((int) pr[0], pr[1]);
        }
        restored.bootstrapTradeCounter(data.tradeCounter());
        apply(restored, events.subList(cut, events.size()));

        assertSameState(live, restored);
    }
}
