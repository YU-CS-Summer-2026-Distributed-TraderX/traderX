package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ENGINE-REVIEW F5: the band re-anchors (and strands) only for an order the risk gate accepts. */
class BandReanchorOnAcceptanceTest {
    static final long PX = 1_000_000L;
    final AeronReplicationCodec codec = new AeronReplicationCodec();
    final UnsafeBuffer in = new UnsafeBuffer(new byte[256]);
    long ts = 1_000_000_000_000L;

    void apply(MatchingEngineClusteredService s, InputEvent e) {
        codec.encodeInput(in, 0, e, 0, 0, 0);
        s.onSessionMessage(null, ++ts, in, 0, AeronReplicationCodec.INPUT_BYTES, null);
    }

    InputEvent ctl(byte type, int acct, int sec) {
        InputEvent e = new InputEvent();
        e.type = type; e.accountId = acct; e.securityId = sec;
        e.setControlEnabled(true); e.setControlVersion(1L);
        return e;
    }

    InputEvent tick(long px) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK; e.securityId = 0; e.priceTicks = px;
        return e;
    }

    InputEvent order(int acct, byte side, long px) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW; e.accountId = acct; e.securityId = 0;
        e.side = side; e.qty = 1; e.limitPx = px;
        return e;
    }

    int lastRef(MatchingEngineClusteredService s) { return (int) s.nextOrderRef() - 1; }

    @Test void refusedOrderDoesNotMoveTheBandButAcceptedOneDoes() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        for (int a = 1; a <= 3; a++) apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, a, 0));
        apply(s, ctl(InputEvent.TYPE_SECURITY_CONTROL, 0, 0));
        apply(s, tick(100 * PX));
        apply(s, order(1, InputEvent.SIDE_BUY, 40 * PX));
        final int victim = lastRef(s);
        apply(s, tick(150 * PX));
        InputEvent kill = new InputEvent();
        kill.type = InputEvent.TYPE_POLICY_CONTROL; kill.setControlVersion(9L); kill.setControlEnabled(true);
        apply(s, kill);
        apply(s, order(2, InputEvent.SIDE_SELL, 200 * PX));                 // refused: KILL_SWITCH
        assertEquals(RiskReason.KILL_SWITCH.ordinal(), s.engine().orderState(lastRef(s))[10]);
        assertEquals(RestingOrder.STATUS_NEW, s.engine().orderState(victim)[0], "band did not move");
        assertEquals(0L, s.engine().bandReanchors());

        kill.setControlEnabled(false); kill.setControlVersion(10L);
        apply(s, kill);
        apply(s, order(3, InputEvent.SIDE_SELL, 200 * PX));                 // accepted: band follows
        assertEquals(RestingOrder.STATUS_NEW, s.engine().orderState(lastRef(s))[0]);
        assertEquals(RestingOrder.STATUS_CANCELED, s.engine().orderState(victim)[0]);
        assertEquals(RiskReason.PRICE_COLLAR.ordinal(), s.engine().orderState(victim)[10]);
        assertEquals(1L, s.engine().bandReanchors());
    }
}
