package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OrderTypes;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ENGINE-REVIEW F6: an untyped replace of a PEGGED order is refused and the peg keeps its offset. */
class UntypedReplaceOfTypedShapeTest {
    static final long PX = 1_000_000L;
    final AeronReplicationCodec codec = new AeronReplicationCodec();
    final UnsafeBuffer in = new UnsafeBuffer(new byte[512]);
    long ts = 1_000_000_000_000L;

    void apply(MatchingEngineClusteredService s, InputEvent e) {
        if (e.orderType != 0) {
            codec.encodeOrderInstruction(in, 0, e, 0, 0, 0);
            s.onSessionMessage(null, ++ts, in, 0, AeronReplicationCodec.ORDER_INSTRUCTION_BYTES, null);
        } else {
            codec.encodeInput(in, 0, e, 0, 0, 0);
            s.onSessionMessage(null, ++ts, in, 0, AeronReplicationCodec.INPUT_BYTES, null);
        }
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

    @Test void untypedReplaceOfAPegIsRefusedAndKeepsItsOffset() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        for (int a = 1; a <= 3; a++) apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, a, 0));
        apply(s, ctl(InputEvent.TYPE_SECURITY_CONTROL, 0, 0));
        InputEvent t = new InputEvent(); t.type = InputEvent.TYPE_PRICE_TICK; t.securityId = 0; t.priceTicks = 100 * PX;
        apply(s, t);
        apply(s, order(2, InputEvent.SIDE_BUY, 10, 99 * PX));
        apply(s, order(3, InputEvent.SIDE_SELL, 10, 101 * PX));
        InputEvent peg = order(1, InputEvent.SIDE_BUY, 10, 100 * PX);
        peg.orderType = OrderTypes.PEGGED; peg.tif = OrderTypes.GTC; peg.pegRef = OrderTypes.PEG_PRIMARY;
        peg.pegOffset = -5;
        apply(s, peg);
        final int pegRef = (int) s.nextOrderRef() - 1;
        InputEvent r = new InputEvent();
        r.type = InputEvent.TYPE_ORDER_REPLACE; r.orderRef = pegRef; r.qty = 8; r.limitPx = 100 * PX;
        apply(s, r);                                            // template 1: untyped
        long[] ext = new long[17];
        assertTrue(s.engine().copyOrderExtension(pegRef, ext));
        assertEquals(-5L, ext[7], "offset untouched");
        assertEquals(10L, s.engine().orderState(pegRef)[1], "quantity untouched");
        assertEquals(99 * PX - 5_000L, s.engine().orderState(pegRef)[3], "price untouched");
    }
}
