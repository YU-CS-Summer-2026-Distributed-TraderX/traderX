package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import io.aeron.DirectBufferVector;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The member half of the ack-correlation fix (option B), driven through the real sequenced ingress
 * path with a capturing session and no cluster. Two claims, each with its own arm:
 *
 * <ol>
 *   <li><b>The echo.</b> The gateway-chosen request id rides the ingress message's dead inputSeq
 *       slot; every egress ack the apply emits is {@code EGRESS_ACK_LENGTH} (32) bytes and carries
 *       that id back at bytes 24..31 — which is the key the gateway's whole keyed correlation
 *       completes on. An input naming no request (id 0) echoes 0, which the gateway never
 *       registers, so it can complete nothing.</li>
 *   <li><b>Never state.</b> The id is carriage, not state: two services applying identical inputs
 *       that differ ONLY in request ids write byte-identical snapshots. That is the fact that lets
 *       the SNAPSHOT_FORMAT stay put and keeps replay/determinism untouched — asserted, not
 *       claimed.</li>
 * </ol>
 */
class RequestIdEchoTest {
    private static final int ACCOUNT = 42422; // real account in counterparties.csv
    private static final long REQUEST_ID = 424_242L;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer symbolBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
    private long timestamp = 1_000_000_000_000L;

    /** Capturing egress session: every offer is copied whole, so length and bytes are asserted on
     *  exactly what a gateway would have received. */
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
    void everyOrderAckIsAckLengthAndEchoesTheIngressRequestId() {
        final MatchingEngineClusteredService service = service();
        final CapturingSession session = new CapturingSession();

        apply(service, session, newOrder(1L), REQUEST_ID);

        assertFalse(session.egress.isEmpty(), "the order's apply must emit at least one direct ack");
        for (final byte[] ack : session.egress) {
            assertEquals(MatchingEngineClusteredService.EGRESS_ACK_LENGTH, ack.length,
                "every egress record is the one fixed ack width — a mixed width would be read as "
                + "garbage request ids by the gateway's fixed-offset decode");
            assertEquals(REQUEST_ID, new UnsafeBuffer(ack).getLong(24),
                "every ack of this apply echoes the id the ingress carried — the key the gateway "
                + "completes the pending on");
        }
    }

    @Test
    void anInputNamingNoRequestEchoesZero() {
        final MatchingEngineClusteredService service = service();
        final CapturingSession session = new CapturingSession();

        apply(service, session, newOrder(2L), 0L);

        assertFalse(session.egress.isEmpty());
        for (final byte[] ack : session.egress) {
            assertEquals(0L, new UnsafeBuffer(ack).getLong(24),
                "no request named, none invented: 0 is never registered gateway-side");
        }
    }

    @Test
    void aSymbolAckIsAckLengthWithNoOrderRequestId() {
        final MatchingEngineClusteredService service = service();
        final CapturingSession session = new CapturingSession();
        codec.encodeSymbolRegister(symbolBuffer, 0, 55L, "JPM");
        service.onSessionMessage(session, ++timestamp, symbolBuffer, 0,
            AeronReplicationCodec.SYMBOL_BYTES, null);

        assertEquals(1, session.egress.size());
        final UnsafeBuffer ack = new UnsafeBuffer(session.egress.get(0));
        assertEquals(MatchingEngineClusteredService.EGRESS_ACK_LENGTH, session.egress.get(0).length,
            "one uniform egress width — the gateway's length check refuses everything else");
        assertEquals(55L, ack.getLong(13), "symbol acks keep their own request id at 13");
        assertEquals(0L, ack.getLong(24), "and carry no order request id");
    }

    /**
     * The request id must never enter replicated state — that is what keeps SNAPSHOT_FORMAT
     * unmoved and every member/replay identical whatever ids gateways chose. Two services, the
     * same inputs, different ids: byte-identical snapshots or this whole design is mis-scoped.
     */
    @Test
    void requestIdsNeverEnterReplicatedState() {
        final MatchingEngineClusteredService one = service();
        final MatchingEngineClusteredService two = service();
        final CapturingSession sink = new CapturingSession();
        long ts = timestamp;
        apply(one, sink, newOrder(3L), 1_111L);
        timestamp = ts;
        apply(two, sink, newOrder(3L), 9_999_999L);

        final List<byte[]> snapOne = snapshot(one);
        final List<byte[]> snapTwo = snapshot(two);
        assertEquals(snapOne.size(), snapTwo.size(),
            "identical inputs, different request ids: identical snapshot record count");
        assertTrue(snapOne.size() > 1, "non-empty precondition: the snapshot must hold real state");
        for (int i = 0; i < snapOne.size(); i++) {
            assertArrayEquals(snapOne.get(i), snapTwo.get(i),
                "snapshot record " + i + " differs: the request id leaked into replicated state");
        }
    }

    // ----- harness ----------------------------------------------------------------------------

    private MatchingEngineClusteredService service() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        final InputEvent enable = new InputEvent();
        enable.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        enable.accountId = ACCOUNT;
        enable.setControlEnabled(true);
        enable.setControlVersion(1);
        apply(service, new CapturingSession(), enable, 0L);
        return service;
    }

    private InputEvent newOrder(final long clientKey) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.accountId = ACCOUNT;
        e.securityId = 0;
        e.side = InputEvent.SIDE_BUY;
        e.qty = 10;
        e.limitPx = 100_000_000L;
        e.setClientOrderKey(clientKey);
        return e;
    }

    private void apply(final MatchingEngineClusteredService service, final ClientSession session,
                       final InputEvent event, final long requestId) {
        codec.encodeInput(ingressBuffer, 0, event, requestId, 0, 0);
        service.onSessionMessage(session, ++timestamp, ingressBuffer, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }

    private static List<byte[]> snapshot(final MatchingEngineClusteredService service) {
        final List<byte[]> records = new ArrayList<>();
        service.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            records.add(copy);
        });
        return records;
    }
}
