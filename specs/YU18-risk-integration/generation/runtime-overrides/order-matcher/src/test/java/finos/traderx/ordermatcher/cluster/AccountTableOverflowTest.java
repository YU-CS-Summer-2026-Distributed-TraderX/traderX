package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** ENGINE-REVIEW F7: a control event for an account past the table's capacity is ignored, not thrown. */
class AccountTableOverflowTest {
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

    @Test void accountTableOverflowIsRefusedNotThrown() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        for (int a = 1; a <= 128; a++) apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, a, 0));
        assertDoesNotThrow(() -> apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 129, 0)));
        apply(s, ctl(InputEvent.TYPE_SECURITY_CONTROL, 0, 0));
        InputEvent t = new InputEvent(); t.type = InputEvent.TYPE_PRICE_TICK; t.securityId = 0; t.priceTicks = 100 * PX;
        apply(s, t);
        InputEvent o = new InputEvent();
        o.type = InputEvent.TYPE_ORDER_NEW; o.accountId = 129; o.securityId = 0;
        o.side = InputEvent.SIDE_BUY; o.qty = 1; o.limitPx = 100 * PX;
        apply(s, o);
        assertEquals(RiskReason.UNKNOWN_ACCOUNT.ordinal(), s.engine().orderState((int) s.nextOrderRef() - 1)[10]);
    }
}
