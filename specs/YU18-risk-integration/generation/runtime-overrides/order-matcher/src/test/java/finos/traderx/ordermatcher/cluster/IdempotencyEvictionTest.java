package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * FR-IMRG14 after bounded terminal retention (ENGINE-REVIEW F1): a keyed retry whose original
 * order has been EVICTED must still be answered as a duplicate — never admitted as a second,
 * unreserved order. Retention here is MECS's production POOL_SIZE (1,024), so 1,100 terminals
 * age the original out exactly as a post-failover retry would find it.
 */
class IdempotencyEvictionTest {
    static final long PX = 1_000_000L;
    static final long KEY = 0xC0FFEEL;
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

    InputEvent order(int acct, byte side, int qty, long key) {
        InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW; e.accountId = acct; e.securityId = 0;
        e.side = side; e.qty = qty; e.limitPx = 100 * PX; e.priceTicks = key;
        return e;
    }

    @Test
    void retryAfterTerminalEvictionIsAnsweredAsDuplicateNotExecutedAgain() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        final List<long[]> acks = new ArrayList<>();   // {kind, orderRef, riskReason}
        s.outputSink(o -> acks.add(new long[] { o.kind, o.orderRef, o.riskReason }));
        apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 1, 0));
        apply(s, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 2, 0));
        apply(s, ctl(InputEvent.TYPE_SECURITY_CONTROL, 0, 0));
        InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK; tick.securityId = 0; tick.priceTicks = 100 * PX;
        apply(s, tick);

        apply(s, order(1, InputEvent.SIDE_BUY, 10, KEY));
        final int originalRef = (int) s.nextOrderRef() - 1;
        apply(s, order(2, InputEvent.SIDE_SELL, 10, 0L));   // fills the original -> terminal
        assertEquals(10, s.engine().positionQuantity(1, 0));
        for (int i = 0; i < 1100; i++) {                    // age it out of the 1,024 ring
            apply(s, order(99, InputEvent.SIDE_BUY, 1, 0L));  // unknown account -> REJECTED terminal
        }
        assertNull(s.engine().orderState(originalRef), "precondition: the original was evicted");

        acks.clear();
        apply(s, order(1, InputEvent.SIDE_BUY, 10, KEY));    // the client retries the same key
        final int retryRef = (int) s.nextOrderRef() - 1;
        assertNull(s.engine().orderState(retryRef), "no second order was created");
        assertEquals(0L, s.risk().reservedNotional(1), "nothing reserved");
        assertEquals(1, acks.size(), "exactly one answer");
        assertEquals(OutputEvent.KIND_ORDER_REJECTED, (byte) acks.get(0)[0]);
        assertEquals(originalRef, (int) acks.get(0)[1], "the answer names the ORIGINAL ref");
        assertEquals(RiskReason.DUPLICATE.ordinal(), (int) acks.get(0)[2]);

        apply(s, order(2, InputEvent.SIDE_SELL, 10, 0L));
        assertEquals(10, s.engine().positionQuantity(1, 0), "the keyed order executed exactly once");
    }
}
