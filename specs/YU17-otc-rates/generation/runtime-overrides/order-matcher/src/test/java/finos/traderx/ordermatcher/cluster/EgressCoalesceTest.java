package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import io.aeron.DirectBufferVector;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Egress coalescing (CLUSTER_EGRESS_COALESCE) changes the framing only: the same inputs give the
 * same records in the same order with the same bytes, packed into fewer messages that every reader
 * splits back with {@code egressRecordCount}; replicated state is untouched.
 */
class EgressCoalesceTest {
    private static final int A = 42422, B = 22214; // real accounts in counterparties.csv
    private static final long PX = 150_000_000L;
    private static final int W = MatchingEngineClusteredService.EGRESS_ACK_LENGTH;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private long timestamp;

    private static final class CapturingSession implements ClientSession {
        final List<byte[]> egress = new ArrayList<>();

        @Override public long id() { return 7; }
        @Override public int responseStreamId() { return 0; }
        @Override public String responseChannel() { return "test"; }
        @Override public byte[] encodedPrincipal() { return new byte[0]; }
        @Override public void close() { }
        @Override public boolean isClosing() { return false; }
        @Override public long offer(final DirectBuffer buffer, final int offset, final int length) {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            egress.add(copy);
            return 1;
        }
        @Override public long offer(final DirectBufferVector[] vectors) { throw new UnsupportedOperationException(); }
        @Override public long tryClaim(final int length, final BufferClaim claim) { throw new UnsupportedOperationException(); }
    }

    @Test
    void recordCountSplitsOnlyWholeMultiplesAboveOneRecord() {
        assertEquals(1, MatchingEngineClusteredService.egressRecordCount(W));
        assertEquals(2, MatchingEngineClusteredService.egressRecordCount(2 * W));
        assertEquals(MatchingEngineClusteredService.EGRESS_COALESCE_MAX,
            MatchingEngineClusteredService.egressRecordCount(W * MatchingEngineClusteredService.EGRESS_COALESCE_MAX));
        assertEquals(1, MatchingEngineClusteredService.egressRecordCount(24), "a short (pre-B) record stays whole and is refused as before");
        assertEquals(1, MatchingEngineClusteredService.egressRecordCount(W + 8), "a ragged length stays whole and is refused as before");
    }

    @Test
    void coalescingKeepsEveryRecordAndItsOrderInFewerMessages() {
        assertFalse(MatchingEngineClusteredService.EGRESS_COALESCE, "precondition: the default is off");
        final MatchingEngineClusteredService plain = service(false);
        final MatchingEngineClusteredService packed = service(true);
        final CapturingSession plainOut = new CapturingSession();
        final CapturingSession packedOut = new CapturingSession();
        int multiRecordApplies = 0;
        for (int i = 0; i < 40; i++) {
            final InputEvent e = order(i);
            final int before = plainOut.egress.size();
            final long ts = timestamp;
            apply(plain, plainOut, e);
            timestamp = ts;
            apply(packed, packedOut, e);
            if (plainOut.egress.size() - before > 1) multiRecordApplies++;
        }
        assertTrue(multiRecordApplies > 0, "precondition: some apply must emit more than one record, or nothing coalesced");
        assertTrue(packedOut.egress.size() < plainOut.egress.size(), "coalescing must send fewer messages");

        final List<byte[]> split = new ArrayList<>();
        for (final byte[] m : packedOut.egress) {
            assertEquals(0, m.length % W, "a coalesced message is a whole number of records");
            assertTrue(m.length <= W * MatchingEngineClusteredService.EGRESS_COALESCE_MAX);
            final int n = MatchingEngineClusteredService.egressRecordCount(m.length);
            for (int r = 0; r < n; r++) split.add(Arrays.copyOfRange(m, r * W, (r + 1) * W));
        }
        assertEquals(plainOut.egress.size(), split.size(), "every record survives the split");
        for (int i = 0; i < split.size(); i++) {
            assertArrayEquals(plainOut.egress.get(i), split.get(i), "record " + i + " differs or moved");
        }
        assertArrayEquals(snapshot(plain), snapshot(packed), "framing must never reach replicated state");
    }

    private MatchingEngineClusteredService service(final boolean coalesce) {
        timestamp = 1_000_000_000_000L;
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        service.egressCoalesce = coalesce;
        for (final int account : new int[] {A, B}) {
            final InputEvent enable = new InputEvent();
            enable.type = InputEvent.TYPE_ACCOUNT_CONTROL;
            enable.accountId = account;
            enable.setControlEnabled(true);
            enable.setControlVersion(1);
            apply(service, new CapturingSession(), enable);
        }
        final InputEvent security = new InputEvent();
        security.type = InputEvent.TYPE_SECURITY_CONTROL;
        security.securityId = 0;
        security.setControlEnabled(true);
        security.setControlVersion(2);
        apply(service, new CapturingSession(), security);
        final InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK;
        tick.securityId = 0;
        tick.priceTicks = PX;
        apply(service, new CapturingSession(), tick);
        return service;
    }

    /** Two accounts on alternating sides at one price with uneven sizes, so most orders cross one or
     *  two resting ones: such an apply drains several records (fills of both sides, trades). */
    private static InputEvent order(final int i) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.accountId = i % 2 == 0 ? A : B;
        e.securityId = 0;
        e.side = i % 2 == 0 ? InputEvent.SIDE_SELL : InputEvent.SIDE_BUY;
        e.qty = 10 + (i % 3) * 5;
        e.limitPx = PX;
        e.setClientOrderKey(1_000L + i);
        return e;
    }

    private void apply(final MatchingEngineClusteredService service, final ClientSession session, final InputEvent event) {
        codec.encodeInput(ingressBuffer, 0, event, 77L, 0, 0);
        service.onSessionMessage(session, ++timestamp, ingressBuffer, 0, AeronReplicationCodec.INPUT_BYTES, null);
    }

    private static byte[] snapshot(final MatchingEngineClusteredService service) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            out.writeBytes(copy);
        });
        return out.toByteArray();
    }
}
