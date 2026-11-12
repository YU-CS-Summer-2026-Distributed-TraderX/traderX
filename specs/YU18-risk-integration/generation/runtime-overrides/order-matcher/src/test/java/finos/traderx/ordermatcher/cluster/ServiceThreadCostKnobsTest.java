package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-OPL-12: the two service-thread cost knobs change no output. The same input mix through a default service and
 * through one with both knobs on gives the same outputs in the same order (every decision field hashed), and the
 * defaults are the old behaviour: a blocking ring and engine timing on.
 */
class ServiceThreadCostKnobsTest {
    private static final long PX = 1_000_000L;

    @Test
    void defaultsKeepTodaysBehaviour() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        assertTrue(service.outputRingSignal, "the default output ring must keep its blocking wait strategy");
        assertTrue(service.engineTiming, "the default engine must keep its hot-path timing");
        service.initEngine();
        assertTrue(service.engine().timingEnabled());
    }

    @Test
    void knobsChangeNoOutput() {
        final long[] plain = run(false);
        final long[] knobs = run(true);
        assertTrue(plain[1] > 20_000, "the mix must produce outputs: " + plain[1]);
        assertEquals(plain[1], knobs[1], "output count");
        assertEquals(plain[0], knobs[0], "output digest");
    }

    @Test
    void timingOffGivesTheEngineNoMetrics() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.engineTiming = false;
        service.initEngine();
        assertFalse(service.engine().timingEnabled());
    }

    /** {digest, count} of every output of a fixed mix: crossing NEWs with keys, a duplicate key, ticks, cancels. */
    private static long[] run(final boolean knobs) {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        if (knobs) {
            service.outputRingSignal = false;
            service.engineTiming = false;
        }
        service.initEngine();
        final long[] acc = { 1125899906842597L, 0 };
        service.outputSink(out -> {
            long h = acc[0];
            for (final long v : new long[] { out.kind, out.inputSeq, out.orderRef, out.tradeSeq, out.accountId, out.securityId,
                out.side, out.quantity, out.remainingQty, out.limitPx, out.status, out.tradeQty, out.tradePx,
                out.riskReason, out.flags }) {
                h = 31 * h + v;
            }
            acc[0] = h;
            acc[1]++;
        });
        final AeronReplicationCodec codec = new AeronReplicationCodec();
        final InputEvent event = new InputEvent();
        final UnsafeBuffer buf = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
        long timestamp = 1_000_000_000_000L;
        long version = 0;
        for (final int account : new int[] { 11, 12 }) {
            event.type = InputEvent.TYPE_ACCOUNT_CONTROL;
            event.accountId = account;
            event.setControlEnabled(true);
            event.setControlVersion(++version);
            codec.encodeInput(buf, 0, event, 0, 0, 0);
            service.onSessionMessage(null, ++timestamp, buf, 0, AeronReplicationCodec.INPUT_BYTES, null);
        }
        event.type = InputEvent.TYPE_SECURITY_CONTROL;
        event.accountId = 0;
        event.securityId = 1;
        event.setControlVersion(++version);
        codec.encodeInput(buf, 0, event, 0, 0, 0);
        service.onSessionMessage(null, ++timestamp, buf, 0, AeronReplicationCodec.INPUT_BYTES, null);
        event.type = InputEvent.TYPE_PRICE_TICK;
        event.side = 0;
        event.securityId = 1;
        event.priceTicks = 150 * PX;
        codec.encodeInput(buf, 0, event, 0, 0, 0);
        service.onSessionMessage(null, ++timestamp, buf, 0, AeronReplicationCodec.INPUT_BYTES, null);
        long key = 7;
        for (int i = 0; i < 20_000; i++) {
            event.type = i % 97 == 96 ? InputEvent.TYPE_ORDER_CANCEL : InputEvent.TYPE_ORDER_NEW;
            event.orderRef = event.type == InputEvent.TYPE_ORDER_CANCEL ? 3 : 0;
            event.accountId = (i & 1) == 0 ? 11 : 12;
            event.side = ((i & 1) == 0) == (((i >> 1) & 1) == 1) ? InputEvent.SIDE_BUY : InputEvent.SIDE_SELL;
            event.securityId = 1;
            event.qty = 10 + i % 3;                       // unequal sizes leave resting remainders
            event.limitPx = 150 * PX;
            if (i % 50 != 49) key = key * 6364136223846793005L + 1442695040888963407L;   // every 50th repeats a key
            event.setClientOrderKey(event.type == InputEvent.TYPE_ORDER_NEW ? key : 0);
            codec.encodeInput(buf, 0, event, 0, 0, 0);
            service.onSessionMessage(null, ++timestamp, buf, 0, AeronReplicationCodec.INPUT_BYTES, null);
        }
        return acc;
    }
}
