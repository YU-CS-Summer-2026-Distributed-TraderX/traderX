package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-093: the event that books a NEW order carries the client's key on the /orders egress; no other event does (a fill
 * of a resting order, the aggressor's own fill). A pre-open queued order's booking carries it too. The recon replay
 * (ClusterRecon.applyAndProject into a shadow) projects the same bytes from the same log.
 */
class ClientOrderKeyEgressTest {
    static final long PX = 1_000_000L;
    final AeronReplicationCodec codec = new AeronReplicationCodec();
    final UnsafeBuffer in = new UnsafeBuffer(new byte[256]);
    long ts = 1_000_000_000_000L;
    final List<byte[]> log = new ArrayList<>();

    void apply(final MatchingEngineClusteredService s, final InputEvent e) {
        codec.encodeInput(in, 0, e, 0, 0, 0);
        final byte[] m = new byte[AeronReplicationCodec.INPUT_BYTES];
        in.getBytes(0, m);
        log.add(m);
        s.onSessionMessage(null, ++ts, in, 0, AeronReplicationCodec.INPUT_BYTES, null);
    }

    static InputEvent ctl(final byte type, final int acct, final int sec) {
        final InputEvent e = new InputEvent();
        e.type = type; e.accountId = acct; e.securityId = sec;
        e.setControlEnabled(true); e.setControlVersion(1L);
        return e;
    }

    static InputEvent order(final int acct, final byte side, final long key) {
        final InputEvent o = new InputEvent();
        o.type = InputEvent.TYPE_ORDER_NEW; o.accountId = acct; o.securityId = 0;
        o.side = side; o.qty = 10; o.limitPx = 100 * PX; o.priceTicks = key;
        return o;
    }

    @Test
    void onlyTheBookingEventCarriesTheClientKeyAndTheReconProjectsTheSame() throws Exception {
        final Path file = Files.createTempDirectory("cok").resolve(RunDescriptor.FILE);
        Files.writeString(file, "{\"schema\":\"traderx.run.v1\",\"epoch\":\"coktest\",\"eventIdScheme\":\"epoch-v1\","
            + "\"storageLineage\":\"cok-storage\",\"projectionScope\":\"cok-scope\",\"adoptionEvidenceSha256\":null}");
        final RunDescriptor d = RunDescriptor.read(file);
        final MatchingEngineClusteredService live = new MatchingEngineClusteredService();
        live.initEngine();
        final List<OutputEvent> events = new ArrayList<>();
        final List<String> projected = new ArrayList<>();
        live.outputSink(o -> {
            if (OutputEvent.isOrderLifecycleKind(o.kind) && o.publishNats) {
                final OutputEvent c = new OutputEvent();
                c.kind = o.kind; c.orderRef = o.orderRef; c.flags = o.flags; c.status = o.status;
                c.clientOrderKey = o.clientOrderKey;
                events.add(c);
                projected.add(new String(OrderNatsPublisher.projectionEvent(o, "IBM", d, projected.size()),
                    StandardCharsets.UTF_8));
            }
        });
        apply(live, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 1, 0));
        apply(live, ctl(InputEvent.TYPE_ACCOUNT_CONTROL, 2, 0));
        apply(live, ctl(InputEvent.TYPE_SECURITY_CONTROL, 0, 0));
        final InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK; tick.securityId = 0; tick.priceTicks = 100 * PX;
        apply(live, tick);
        apply(live, order(1, InputEvent.SIDE_BUY, -1L));               // rests: key 2^64-1, unsigned on the wire
        apply(live, order(2, InputEvent.SIDE_SELL, 88L));              // crosses it

        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, events.get(0).kind);
        assertEquals(-1L, events.get(0).clientOrderKey, "the buy's booking");
        assertTrue(projected.get(0).contains("\"clientOrderKey\":\"18446744073709551615\""), projected.get(0));
        int keyed = 0;
        for (int i = 1; i < events.size(); i++) {
            final OutputEvent e = events.get(i);
            if (e.kind == OutputEvent.KIND_ORDER_ACCEPTED && e.clientOrderKey == 88L) {
                keyed++;
                assertTrue(projected.get(i).contains("\"clientOrderKey\":\"88\""), projected.get(i));
            } else {
                assertEquals(0L, e.clientOrderKey, "event " + i + " kind " + e.kind + " is not a booking");
                assertFalse(projected.get(i).contains("clientOrderKey"), projected.get(i));
            }
        }
        assertEquals(1, keyed, "the sell's booking, once");
        assertTrue(events.stream().anyMatch(e -> e.kind == OutputEvent.KIND_ORDER_FILLED), "the cross filled");

        // A pre-open queued order: its booking (QUEUED) names the key.
        final InputEvent halt = new InputEvent();
        halt.type = InputEvent.TYPE_SESSION_CONTROL; halt.side = MatchingEngineClusteredService.PHASE_PRE_OPEN;
        apply(live, halt);
        final int before = events.size();
        apply(live, order(1, InputEvent.SIDE_BUY, 99L));
        assertEquals(99L, events.get(before).clientOrderKey, "the queued booking");

        // Recon parity: the same log through the recon's apply seam projects byte-identical events.
        final MatchingEngineClusteredService shadow = new MatchingEngineClusteredService();
        shadow.initEngine();
        final List<String> recon = new ArrayList<>();
        shadow.outputSink(o -> {
            if (OutputEvent.isOrderLifecycleKind(o.kind) && o.publishNats) {
                recon.add(new String(OrderNatsPublisher.projectionEvent(o, "IBM", d, recon.size()), StandardCharsets.UTF_8));
            }
        });
        long t = 1_000_000_000_000L;
        for (final byte[] m : log) {
            ClusterRecon.applyAndProject(shadow, ++t, new UnsafeBuffer(m), 0, m.length, null);
        }
        assertEquals(projected, recon);
    }
}
