package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import io.aeron.DirectBufferVector;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-073's sequenced sandbox reset, driven through the real ingress path.
 *
 * <p>The reset exists because the obvious alternative -- stop the member, wipe its volume, mint a
 * fresh epoch -- restarts every identifier from 1, and a blotter spanning such a reset then shows
 * two different trades sharing an id. So the load-bearing assertion here is not "the book is
 * empty": it is that <b>no identifier issued before a reset is ever issued again after one</b>.
 * That failure is silent (ids simply start repeating, and every surface keeps returning 200), which
 * is exactly the kind this suite is supposed to catch at build time.
 *
 * <p>The second claim is the split: a reset clears the SESSION and keeps the VENUE. A reset that
 * also dropped admission would leave a venue where nothing can trade until every instrument is
 * re-admitted -- a teardown wearing a reset's name.
 */
class SandboxResetTest {
    private static final int ACCOUNT = 42422;
    private static final int ACCOUNT2 = 22214;
    private static final int SECURITY = 0;
    private static final long PX = 1_000_000L;
    private static final long LIMIT = 150 * PX;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private long timestamp = 1_000_000_000_000L;

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

    // ----- the reason this design was chosen over wiping the volume ---------------------------

    @Test
    void noOrderRefIsEverIssuedTwiceAcrossAReset() {
        final MatchingEngineClusteredService service = seeded();
        final Set<Integer> issued = new HashSet<>();

        for (int i = 0; i < 5; i++) {
            issued.add(refOf(place(service, ACCOUNT, 100L + i)));
        }
        final int beforeReset = issued.size();
        assertEquals(5, beforeReset, "control: five distinct refs before the reset");

        reset(service);

        for (int i = 0; i < 5; i++) {
            final int ref = refOf(place(service, ACCOUNT, 200L + i));
            assertTrue(issued.add(ref),
                "order ref " + ref + " was issued BEFORE the reset and has just been issued again."
                    + " This is the whole reason the reset is a sequenced command rather than a"
                    + " volume wipe -- a blotter spanning it would carry two different orders"
                    + " under one id");
        }
        assertEquals(10, issued.size());
    }

    @Test
    void theTradeCounterDoesNotRewind() {
        final MatchingEngineClusteredService service = seeded();
        // Cross a pair so the trade counter actually moves; a counter asserted at 0 on both sides
        // of a reset would pass while proving nothing.
        place(service, ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 300L);
        place(service, ACCOUNT2, InputEvent.SIDE_SELL, LIMIT, 301L);
        final long traded = service.engine().tradeCounter();
        assertTrue(traded > 0, "control: the pair must actually have crossed, got " + traded);

        reset(service);

        assertEquals(traded, service.engine().tradeCounter(),
            "a reset clears the session, not the identity of everything that already happened");
    }

    @Test
    void theConsensusSequenceAdvancesAcrossAReset() {
        final MatchingEngineClusteredService service = seeded();
        place(service, ACCOUNT, 400L);
        final long before = service.appliedSeq();

        final UnsafeBuffer ack = reset(service);

        assertTrue(ack.getLong(0) > before,
            "the reset is itself a committed apply and must occupy a position after the state it"
                + " cleared -- rewinding appliedSeq would make the log unreadable");
        assertEquals(ack.getLong(0), service.appliedSeq());
    }

    // ----- session cleared, venue kept ---------------------------------------------------------

    @Test
    void theSessionIsCleared() {
        final MatchingEngineClusteredService service = seeded();
        place(service, ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 500L);
        place(service, ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 501L);
        assertEquals(2, service.engine().openOrderTuples().size(), "control: two orders resting");

        final UnsafeBuffer ack = reset(service);

        assertEquals(0, service.engine().openOrderTuples().size(), "the book is empty after a reset");
        assertEquals(2, ack.getInt(8),
            "the ack must say how many orders it dropped: a cleared venue and an already-empty one"
                + " are indistinguishable from a 200 alone");
    }

    @Test
    void theVenueSurvives_soTheNextOrderTradesWithoutReadmittingAnything() {
        final MatchingEngineClusteredService service = seeded();
        place(service, ACCOUNT, 600L);

        reset(service);

        // The real assertion: NOT that a config table is non-empty, but that the venue still
        // works. An order accepted here proves account admission, security admission, the symbol
        // registration and the seeded price all survived -- any one of them missing rejects.
        final CapturingSession after = new CapturingSession();
        apply(service, after, newOrder(ACCOUNT, InputEvent.SIDE_BUY, LIMIT, 601L));
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, kindOf(directAck(after)),
            "a reset must leave a venue that still trades. If this rejects, the reset dropped"
                + " admission or the price and is a teardown, not a reset");
    }

    @Test
    void thePhaseSurvivesAReset() {
        final MatchingEngineClusteredService service = seeded();
        final InputEvent close = new InputEvent();
        close.type = InputEvent.TYPE_SESSION_CONTROL;
        close.side = MatchingEngineClusteredService.PHASE_CLOSED;
        close.setClientOrderKey(700L);
        apply(service, new CapturingSession(), close);
        assertEquals("CLOSED", service.phaseName(), "control: the venue is halted");

        reset(service);

        assertEquals("CLOSED", service.phaseName(),
            "a halt a reset can bypass is not a halt -- the same argument ADR-069 makes about a"
                + " restart applies to this command");
    }

    @Test
    void resettingTwiceIsSafeAndTheSecondReportsNothingCleared() {
        final MatchingEngineClusteredService service = seeded();
        place(service, ACCOUNT, 800L);
        assertEquals(1, reset(service).getInt(8));
        assertEquals(0, reset(service).getInt(8),
            "the second reset has nothing to clear and must say so rather than repeating the first"
                + " reset's count");
        assertEquals(0, service.engine().openOrderTuples().size());
    }

    // ----- the session boundary (format 10) ---------------------------------------------------

    @Test
    void aResetRecordsTheSequenceItAppliedAt() {
        final MatchingEngineClusteredService service = seeded();
        assertEquals(0L, service.lastResetSeq(), "a venue that has never been reset has no boundary");
        place(service, ACCOUNT, 900L);

        final UnsafeBuffer ack = reset(service);

        assertEquals(ack.getLong(0), service.lastResetSeq(),
            "the boundary IS the reset's own applied position; the results view asks for records"
                + " strictly after it, so an off-by-one here shows the previous session again");
    }

    @Test
    void theBoundarySurvivesASnapshotThatSkipsTheReset() {
        // The regulatory report replays the WHOLE log -- a reset clears the book, it cannot
        // un-write the log -- so this number is the only thing separating one session from the
        // last. A member that lost it across a restore would silently report every earlier session
        // again, which is precisely the bug this record type exists to prevent.
        final MatchingEngineClusteredService source = seeded();
        place(source, ACCOUNT, 901L);
        reset(source);
        final long boundary = source.lastResetSeq();
        assertTrue(boundary > 0, "control: the source must actually hold a boundary");

        final MatchingEngineClusteredService restored = roundTrip(source);

        assertEquals(boundary, restored.lastResetSeq(),
            "the reset marker must survive a snapshot restore");
    }

    @Test
    void aVenueNeverResetWritesNoMarkerAtAll() {
        final MatchingEngineClusteredService source = seeded();
        place(source, ACCOUNT, 902L);

        for (final byte[] record : snapshotOf(source)) {
            assertNotEquals(MatchingEngineClusteredService.T_SANDBOX_RESET,
                new UnsafeBuffer(record).getInt(0),
                "the marker must be absent until a venue has been reset -- it costs nothing and"
                    + " asserts nothing on every live venue, which is why it is never required");
        }
        assertEquals(0L, roundTrip(source).lastResetSeq());
    }

    // ----- helpers ----------------------------------------------------------------------------

    private List<byte[]> snapshotOf(final MatchingEngineClusteredService service) {
        final List<byte[]> records = new ArrayList<>();
        service.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            records.add(copy);
        });
        return records;
    }

    private MatchingEngineClusteredService roundTrip(final MatchingEngineClusteredService source) {
        final MatchingEngineClusteredService restored = new MatchingEngineClusteredService();
        restored.initEngine();
        for (final byte[] record : snapshotOf(source)) {
            restored.onSnapshotRecord(new UnsafeBuffer(record), 0);
        }
        return restored;
    }


    /** Applies the reset and returns its ack. */
    private UnsafeBuffer reset(final MatchingEngineClusteredService service) {
        final CapturingSession session = new CapturingSession();
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SANDBOX_RESET;
        e.setClientOrderKey(999L);
        apply(service, session, e);
        for (final byte[] record : session.egress) {
            final UnsafeBuffer ack = new UnsafeBuffer(record);
            if (ack.getByte(12) == MatchingEngineClusteredService.KIND_SANDBOX_RESET) {
                return ack;
            }
        }
        throw new AssertionError("no reset ack in " + session.egress.size() + " egress records");
    }

    private CapturingSession place(final MatchingEngineClusteredService service, final int account,
                                   final long key) {
        return place(service, account, InputEvent.SIDE_BUY, LIMIT, key);
    }

    private CapturingSession place(final MatchingEngineClusteredService service, final int account,
                                   final byte side, final long limitPx, final long key) {
        final CapturingSession session = new CapturingSession();
        apply(service, session, newOrder(account, side, limitPx, key));
        return session;
    }

    private int refOf(final CapturingSession session) {
        return directAck(session).getInt(8);
    }

    private MatchingEngineClusteredService seeded() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        final CapturingSession sink = new CapturingSession();
        for (final int account : new int[] { ACCOUNT, ACCOUNT2 }) {
            final InputEvent e = new InputEvent();
            e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
            e.accountId = account;
            e.setControlEnabled(true);
            e.setControlVersion(1L);
            apply(service, sink, e);
        }
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

    private InputEvent newOrder(final int account, final byte side, final long limitPx, final long key) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.accountId = account;
        e.securityId = SECURITY;
        e.side = side;
        e.qty = 10;
        e.limitPx = limitPx;
        e.setClientOrderKey(key);
        return e;
    }

    private void apply(final MatchingEngineClusteredService service, final ClientSession session,
                       final InputEvent event) {
        codec.encodeInput(ingressBuffer, 0, event, 0, 0, 0);
        service.onSessionMessage(session, ++timestamp, ingressBuffer, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }

    private UnsafeBuffer directAck(final CapturingSession session) {
        for (final byte[] record : session.egress) {
            final UnsafeBuffer ack = new UnsafeBuffer(record);
            final byte kind = ack.getByte(12);
            if (ack.getByte(21) == 0
                && (OutputEvent.isOrderLifecycleKind(kind) || kind == OutputEvent.KIND_ORDER_NOT_FOUND)) {
                return ack;
            }
        }
        throw new AssertionError("no direct order-lifecycle ack in " + session.egress.size() + " records");
    }

    private static byte kindOf(final UnsafeBuffer ack) {
        return ack.getByte(12);
    }
}
