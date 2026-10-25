package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ENGINE-REVIEW F9: a key already decided by the engine is not queued again during PRE_OPEN. */
class PreOpenDuplicateKeyTest {
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

    @Test void retryOfAnOpenDecidedKeyDuringPreOpenIsNotQueued() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        final List<long[]> acks = new ArrayList<>();
        s.outputSink(o -> acks.add(new long[] { o.kind, o.orderRef, o.status }));
        apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 1, 0));
        apply(s, ctl(InputEvent.TYPE_SECURITY_CONTROL, 0, 0));
        InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK; tick.securityId = 0; tick.priceTicks = 100 * PX;
        apply(s, tick);
        InputEvent o = new InputEvent();
        o.type = InputEvent.TYPE_ORDER_NEW; o.accountId = 1; o.securityId = 0;
        o.side = InputEvent.SIDE_BUY; o.qty = 10; o.limitPx = 100 * PX; o.priceTicks = 77L;
        apply(s, o);                                        // accepted while OPEN
        final int originalRef = (int) s.nextOrderRef() - 1;

        InputEvent halt = new InputEvent();
        halt.type = InputEvent.TYPE_SESSION_CONTROL; halt.side = MatchingEngineClusteredService.PHASE_PRE_OPEN;
        apply(s, halt);
        acks.clear();
        apply(s, o);                                        // the same key, retried during PRE_OPEN
        assertEquals(0, s.queueDepth(), "not queued a second time");
        assertEquals(1, acks.size());
        assertEquals(originalRef, (int) acks.get(0)[1], "answered with the original order");
        assertEquals(RestingOrder.STATUS_NEW, (byte) acks.get(0)[2]);
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, (byte) acks.get(0)[0]);
    }
}
