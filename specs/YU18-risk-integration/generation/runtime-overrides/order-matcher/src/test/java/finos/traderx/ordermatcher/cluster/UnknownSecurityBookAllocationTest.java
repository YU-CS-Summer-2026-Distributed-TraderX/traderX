package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OrderTypes;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ENGINE-REVIEW F4: an order for an unknown security is refused without allocating a book. */
class UnknownSecurityBookAllocationTest {
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

    InputEvent order(int sec) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW; e.accountId = 1; e.securityId = sec;
        e.side = InputEvent.SIDE_BUY; e.qty = 1; e.limitPx = 100 * PX;
        return e;
    }

    int lastRef(MatchingEngineClusteredService s) { return (int) s.nextOrderRef() - 1; }

    @Test void unknownSecurityIsRefusedWithoutAllocatingABook() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        InputEvent a = new InputEvent();
        a.type = InputEvent.TYPE_ACCOUNT_CONTROL; a.accountId = 1; a.setControlEnabled(true); a.setControlVersion(1L);
        apply(s, a);
        assertNull(s.engine().book(900));
        apply(s, order(900));
        assertEquals(RiskReason.UNKNOWN_SECURITY.ordinal(), s.engine().orderState(lastRef(s))[10]);
        assertNull(s.engine().book(900), "legacy path: no book");
        InputEvent typed = order(901);
        typed.orderType = OrderTypes.LIMIT; typed.tif = OrderTypes.GTC;
        apply(s, typed);
        assertEquals(RiskReason.UNKNOWN_SECURITY.ordinal(), s.engine().orderState(lastRef(s))[10]);
        assertNull(s.engine().book(901), "typed path: no book");
        assertTrue(s.engine().bookBaseTuples().stream().noneMatch(t -> t[0] == 900 || t[0] == 901));
    }
}
