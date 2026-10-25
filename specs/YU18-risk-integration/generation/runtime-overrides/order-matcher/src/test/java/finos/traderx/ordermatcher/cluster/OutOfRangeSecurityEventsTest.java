package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** ENGINE-REVIEW F10: an out-of-range securityId on a price tick or market trade never throws in apply. */
class OutOfRangeSecurityEventsTest {
    static final long PX = 1_000_000L;
    final AeronReplicationCodec codec = new AeronReplicationCodec();
    final UnsafeBuffer in = new UnsafeBuffer(new byte[256]);
    long ts = 1_000_000_000_000L;

    void apply(MatchingEngineClusteredService s, InputEvent e) {
        codec.encodeInput(in, 0, e, 0, 0, 0);
        s.onSessionMessage(null, ++ts, in, 0, AeronReplicationCodec.INPUT_BYTES, null);
    }

    @Test void outOfRangeSecurityOnTickAndMarketTradeDoesNotThrow() {
        final MatchingEngineClusteredService s = new MatchingEngineClusteredService();
        s.initEngine();
        InputEvent a = new InputEvent();
        a.type = InputEvent.TYPE_ACCOUNT_CONTROL; a.accountId = 1; a.setControlEnabled(true); a.setControlVersion(1L);
        apply(s, a);
        InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK; tick.securityId = 5000; tick.priceTicks = 100 * PX;
        assertDoesNotThrow(() -> apply(s, tick));
        InputEvent trade = new InputEvent();
        trade.type = InputEvent.TYPE_TRADE_NEW; trade.accountId = 1; trade.securityId = 5000;
        trade.side = InputEvent.SIDE_BUY; trade.qty = 1;
        assertDoesNotThrow(() -> apply(s, trade));
        assertEquals(0, s.engine().positionQuantity(1, 0));
    }
}
