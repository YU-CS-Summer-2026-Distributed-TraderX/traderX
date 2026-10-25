package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OrderTypes;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENGINE-REVIEW F3: a book whose security holds a pending STOP_LIMIT keeps its grid across an
 * empty-book admission, so the stop's limit stays on-grid, it rests at exactly its limit when it
 * triggers, and the snapshot taken in between restores.
 */
class PendingStopGridStabilityTest {
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

    InputEvent tick(long px) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK; e.securityId = 0; e.priceTicks = px;
        return e;
    }

    InputEvent order(int acct, byte side, int qty, long px) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW; e.accountId = acct; e.securityId = 0;
        e.side = side; e.qty = qty; e.limitPx = px;
        return e;
    }

    MatchingEngineClusteredService restore(MatchingEngineClusteredService src) {
        List<byte[]> recs = new ArrayList<>();
        src.writeSnapshot((b, o, n) -> { byte[] c = new byte[n]; b.getBytes(o, c); recs.add(c); });
        MatchingEngineClusteredService t = new MatchingEngineClusteredService();
        t.initEngine();
        boolean done = false;
        for (byte[] r : recs) done = t.onSnapshotRecord(new UnsafeBuffer(r), 0);
        assertTrue(done);
        return t;
    }

    @Test void pendingStopLimitPinsTheGridAndTriggersAtItsExactLimit() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        for (int a = 1; a <= 4; a++) apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, a, 0));
        apply(s, ctl(InputEvent.TYPE_SECURITY_CONTROL, 0, 0));
        apply(s, tick(9_500_000L));                          // $9.50 -> decade grid 10 Px

        final InputEvent stop = order(1, InputEvent.SIDE_SELL, 5, 8_999_990L);
        stop.orderType = OrderTypes.STOP_LIMIT; stop.tif = OrderTypes.GTC; stop.stopPx = 9_000_000L;
        apply(s, stop);
        final int stopRef = (int) s.nextOrderRef() - 1;
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, s.engine().orderState(stopRef)[0]);
        assertEquals(10L, s.engine().bookTickPxOf(0));

        apply(s, tick(10_500_000L));                         // $10.50 would derive grid 100 Px
        apply(s, order(3, InputEvent.SIDE_SELL, 1, 10_500_000L));   // an empty-book admission...
        assertEquals(10L, s.engine().bookTickPxOf(0), "...does NOT re-tick while a stop pends");

        final MatchingEngineClusteredService restored = restore(s);   // snapshot mid-flight restores
        assertEquals(RestingOrder.STATUS_PENDING_TRIGGER, restored.engine().orderState(stopRef)[0]);

        apply(s, tick(9_000_000L));
        apply(s, order(2, InputEvent.SIDE_BUY, 1, 9_000_000L));
        apply(s, order(4, InputEvent.SIDE_SELL, 1, 9_000_000L));    // print 9.00 -> triggers
        final long[] st = s.engine().orderState(stopRef);
        assertEquals(RestingOrder.STATUS_NEW, st[0]);
        assertEquals(8_999_990L, s.engine().bestAskPx(0), "rests at exactly its limit");
        restore(s);                                           // and the post-trigger snapshot restores too
    }
}
