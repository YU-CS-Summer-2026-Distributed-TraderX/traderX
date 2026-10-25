package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ENGINE-REVIEW G2: a large partial fill releases exactly the filled share of the reservation. */
class PartialFillReleaseTest {
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

    InputEvent order(int acct, byte side, int qty, long px) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW; e.accountId = acct; e.securityId = 0;
        e.side = side; e.qty = qty; e.limitPx = px;
        return e;
    }

    @Test void largePartialFillReleasesExactlyTheFilledShare() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 1, 0));
        apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 2, 0));
        apply(s, ctl(InputEvent.TYPE_SECURITY_CONTROL, 0, 0));
        InputEvent t = new InputEvent(); t.type = InputEvent.TYPE_PRICE_TICK; t.securityId = 0; t.priceTicks = 100 * PX;
        apply(s, t);
        apply(s, order(1, InputEvent.SIDE_BUY, 1_000_000, 100 * PX));
        final int big = (int) s.nextOrderRef() - 1;
        apply(s, order(2, InputEvent.SIDE_SELL, 900_000, 100 * PX));
        long[] st = s.engine().orderState(big);
        assertEquals(RestingOrder.STATUS_PARTIALLY_FILLED, st[0]);
        assertEquals(100_000L * 100 * PX, st[8], "reservation left = remaining * px");
        assertEquals(100_000L, st[9], "reserved qty left = remaining");
        assertEquals(100_000L * 100 * PX, s.risk().reservedNotional(1), "account aggregate matches");
    }
}
