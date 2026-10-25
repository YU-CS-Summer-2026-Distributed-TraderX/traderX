package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ENGINE-REVIEW F2: the risk exposure table is sized so it can never fill within the account and
 * security domains, so a long-running member and one restored from its snapshot (whose table only
 * re-grows open pairs) reach the same admission decision. Before the fix 2,048 distinct
 * (account, security) pairs filled the table: the survivor said CAPACITY, the restorer said NEW.
 */
class ExposureTableRestoreParityTest {
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

    InputEvent order(int acct, int sec, long px) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW; e.accountId = acct; e.securityId = sec;
        e.side = InputEvent.SIDE_BUY; e.qty = 1; e.limitPx = px;
        return e;
    }

    int lastRef(MatchingEngineClusteredService s) { return (int) s.nextOrderRef() - 1; }

    @Test void survivorAndRestoredMemberAgreeAfterThousandsOfPairs() {
        final MatchingEngineClusteredService a = new MatchingEngineClusteredService();
        a.initEngine();
        a.engine().setBookGeometry(1 << 10, 1_000L);
        for (int acct = 1; acct <= 64; acct++) apply(a, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, acct, 0));
        for (int sec = 0; sec <= 32; sec++) apply(a, ctl(InputEvent.TYPE_SECURITY_CONTROL, 0, sec));
        for (int acct = 1; acct <= 64; acct++) {               // 2,048 distinct pairs, all closed
            for (int sec = 0; sec < 32; sec++) {
                apply(a, order(acct, sec, 100 * PX));
                final InputEvent c = new InputEvent();
                c.type = InputEvent.TYPE_ORDER_CANCEL; c.orderRef = lastRef(a); c.accountId = acct;
                apply(a, c);
            }
        }
        final List<byte[]> recs = new ArrayList<>();
        a.writeSnapshot((buf, o, n) -> { byte[] c = new byte[n]; buf.getBytes(o, c); recs.add(c); });
        final MatchingEngineClusteredService b = new MatchingEngineClusteredService();
        b.initEngine();
        for (byte[] r : recs) b.onSnapshotRecord(new UnsafeBuffer(r), 0);

        apply(a, order(1, 32, 100 * PX));                     // a brand-new pair on both
        apply(b, order(1, 32, 100 * PX));
        assertEquals(RestingOrder.STATUS_NEW, a.engine().orderState(lastRef(a))[0], "survivor admits");
        assertEquals(RestingOrder.STATUS_NEW, b.engine().orderState(lastRef(b))[0], "restorer admits");
    }
}
