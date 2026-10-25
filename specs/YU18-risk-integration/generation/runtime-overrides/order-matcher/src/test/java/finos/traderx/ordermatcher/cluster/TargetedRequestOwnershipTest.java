package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ENGINE-REVIEW G3: a cancel, replace or force-fill that names an account other than the order's
 * owner is refused NOT_ENTITLED and leaves the order untouched; a request naming no account (0,
 * the legacy binary frames and operator paths) is still honoured.
 */
class TargetedRequestOwnershipTest {
    static final long PX = 1_000_000L;
    final AeronReplicationCodec codec = new AeronReplicationCodec();
    final UnsafeBuffer in = new UnsafeBuffer(new byte[256]);
    long ts = 1_000_000_000_000L;
    final List<long[]> acks = new ArrayList<>();   // {kind, orderRef, riskReason}

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

    InputEvent targeted(byte type, int ref, int acct, int qty, long px) {
        InputEvent e = new InputEvent();
        e.type = type; e.orderRef = ref; e.accountId = acct; e.qty = qty; e.limitPx = px;
        return e;
    }

    MatchingEngineClusteredService withRestingOrder() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        s.outputSink(o -> acks.add(new long[] { o.kind, o.orderRef, o.riskReason }));
        apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 1, 0));
        apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 2, 0));
        apply(s, ctl(InputEvent.TYPE_SECURITY_CONTROL, 0, 0));
        InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK; tick.securityId = 0; tick.priceTicks = 100 * PX;
        apply(s, tick);
        InputEvent o = new InputEvent();
        o.type = InputEvent.TYPE_ORDER_NEW; o.accountId = 1; o.securityId = 0;
        o.side = InputEvent.SIDE_BUY; o.qty = 10; o.limitPx = 100 * PX;
        apply(s, o);
        acks.clear();
        return s;
    }

    int ref(MatchingEngineClusteredService s) { return (int) s.nextOrderRef() - 1; }

    @Test void foreignCancelIsRefusedAndOrderStands() {
        final MatchingEngineClusteredService s = withRestingOrder();
        apply(s, targeted(InputEvent.TYPE_ORDER_CANCEL, ref(s), 2, 0, 0L));
        assertEquals(RestingOrder.STATUS_NEW, s.engine().orderState(ref(s))[0]);
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, (byte) acks.get(0)[0]);
        assertEquals(RiskReason.NOT_ENTITLED.ordinal(), (int) acks.get(0)[2]);
        assertEquals(10L * 100 * PX, s.risk().reservedNotional(1), "reservation untouched");
    }

    @Test void foreignReplaceIsRefusedAndOrderStands() {
        final MatchingEngineClusteredService s = withRestingOrder();
        apply(s, targeted(InputEvent.TYPE_ORDER_REPLACE, ref(s), 2, 5, 99 * PX));
        long[] st = s.engine().orderState(ref(s));
        assertEquals(RestingOrder.STATUS_NEW, st[0]);
        assertEquals(10L, st[1], "quantity unchanged");
        assertEquals(100 * PX, st[3], "price unchanged");
        assertEquals(RiskReason.NOT_ENTITLED.ordinal(), (int) acks.get(0)[2]);
    }

    @Test void ownerAndUnclaimedCancelsStillWork() {
        MatchingEngineClusteredService s = withRestingOrder();
        apply(s, targeted(InputEvent.TYPE_ORDER_CANCEL, ref(s), 1, 0, 0L));   // the owner
        assertEquals(RestingOrder.STATUS_CANCELED, s.engine().orderState(ref(s))[0]);
        s = withRestingOrder();
        apply(s, targeted(InputEvent.TYPE_ORDER_CANCEL, ref(s), 0, 0, 0L));   // no claim on the wire
        assertEquals(RestingOrder.STATUS_CANCELED, s.engine().orderState(ref(s))[0]);
    }
}
