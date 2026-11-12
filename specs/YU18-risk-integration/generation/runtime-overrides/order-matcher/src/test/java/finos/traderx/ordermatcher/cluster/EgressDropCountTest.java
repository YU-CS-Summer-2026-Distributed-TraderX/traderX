package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import io.aeron.DirectBufferVector;
import io.aeron.Publication;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-BSP-03 (RI38): the leader counts every egress record it does not hand its client. Before, {@code offerEgress} and
 * {@code flushCoalesced} gave up after 20 attempts and counted nothing; the sender answered ambiguous 12 s later
 * (RI35: 7 of 720,819 binary acks, 187 FIX acks). Harness mirrors {@code OrderTypesSessionBaselineTest}.
 */
class EgressDropCountTest {
    private static final int ACCOUNT = 42422;   // a real account in counterparties.csv
    private static final int SECURITY = 0;
    private static final long LIMIT = 150 * 1_000_000L;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES + AeronReplicationCodec.WIDE_REF_TAIL_BYTES]);
    private long timestamp = 1_000_000_000_000L;

    @ParameterizedTest(name = "coalesced: {0}")
    @ValueSource(booleans = {false, true})
    void aRefusedRecordIsCountedOncePerRecord(final boolean coalesce) {
        final Session accepting = new Session(1, false);
        final MatchingEngineClusteredService reference = seeded(coalesce);
        apply(reference, accepting, order(101L));
        final long records = accepting.records;
        assertTrue(records > 0, "an order's answer is egress");
        assertEquals(0, reference.egressDropped(), "a session that accepts drops nothing");

        final MatchingEngineClusteredService service = seeded(coalesce);
        final Session refusing = new Session(Publication.BACK_PRESSURED, false);
        apply(service, refusing, order(101L));
        assertEquals(records, service.egressDropped(), "each refused record counted once");
        assertEquals(coalesce ? 20 : 20 * records, refusing.offers, "the bounded retry, per message");
        assertEquals(0, service.egressClosing());
    }

    @Test
    void aClosingSessionsRecordsAreCountedApart() {
        final MatchingEngineClusteredService service = seeded(false);
        final Session closing = new Session(1, true);
        apply(service, closing, order(102L));
        assertTrue(service.egressClosing() > 0, "a closing session's answer is not delivered");
        assertEquals(0, closing.offers, "and is not offered");
        assertEquals(0, service.egressDropped());
    }

    private static final class Session implements ClientSession {
        final long answer;
        final boolean closing;
        long offers, records;
        Session(final long answer, final boolean closing) { this.answer = answer; this.closing = closing; }
        @Override public long id() { return 7; }
        @Override public int responseStreamId() { return 0; }
        @Override public String responseChannel() { return "test"; }
        @Override public byte[] encodedPrincipal() { return new byte[0]; }
        @Override public void close() { }
        @Override public boolean isClosing() { return closing; }
        @Override public long offer(final DirectBuffer buffer, final int offset, final int length) {
            offers++;
            if (answer > 0) { records += MatchingEngineClusteredService.egressRecordCount(length); }
            return answer;
        }
        @Override public long offer(final DirectBufferVector[] vectors) { throw new UnsupportedOperationException(); }
        @Override public long tryClaim(final int length, final BufferClaim claim) { throw new UnsupportedOperationException(); }
    }

    private MatchingEngineClusteredService seeded(final boolean coalesce) {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        service.egressCoalesce = coalesce;
        final Session sink = new Session(1, false);
        final InputEvent account = new InputEvent();
        account.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        account.accountId = ACCOUNT;
        account.setControlEnabled(true);
        account.setControlVersion(1L);
        apply(service, sink, account);
        final InputEvent security = new InputEvent();
        security.type = InputEvent.TYPE_SECURITY_CONTROL;
        security.securityId = SECURITY;
        security.setControlEnabled(true);
        security.setControlVersion(2L);
        apply(service, sink, security);
        final InputEvent tick = new InputEvent();
        tick.type = InputEvent.TYPE_PRICE_TICK;
        tick.securityId = SECURITY;
        tick.priceTicks = LIMIT;
        apply(service, sink, tick);
        return service;
    }

    private static InputEvent order(final long key) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.accountId = ACCOUNT;
        e.securityId = SECURITY;
        e.side = InputEvent.SIDE_BUY;
        e.qty = 10;
        e.limitPx = LIMIT;
        e.setClientOrderKey(key);
        return e;
    }

    private void apply(final MatchingEngineClusteredService service, final ClientSession session, final InputEvent event) {
        service.onSessionMessage(session, ++timestamp, ingressBuffer, 0, codec.encodeInput(ingressBuffer, 0, event, 0, 0, 0), null);
    }
}
