package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.risk.RiskReason;
import io.aeron.DirectBufferVector;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The empty-book re-derivation ({@code format-8-price-derived-grid-design.md} section 2.3), driven
 * through the real sequenced ingress path with no cluster. This is the in-process counterpart of
 * {@code yu17-book-retick} and {@code yu17-fine-grid}.
 *
 * <p><b>Section 2.3 states that ONE rule is doing FOUR jobs, and asks that they stay stated together
 * so no later reader simplifies three of them away.</b> Each has its own arm here, named for the
 * job, so deleting the rule cannot pass as a cleanup:
 *
 * <ol>
 *   <li>{@link #jobOne_theFrozenAccidentLastsOneOccupancyNotAnEpoch()};</li>
 *   <li>{@link #jobTwo_scaleDriftSelfHealsWithNoReIndexMechanism()};</li>
 *   <li>{@link #jobThree_aRestoredMemberAndASurvivorAgreeOnTheNextOrdersGrid()};</li>
 *   <li>{@link #jobFour_theUnpricedFallbackIsProvisionalWhichIsWhyNoOptionConstantShips()}.</li>
 * </ol>
 */
class PriceDerivedGridTest {
    private static final int ACCOUNT = 42422;
    private static final int SECURITY = 0;
    private static final long PX = 1_000_000L;
    private static final long GLOBAL = MatchingEngine.DEFAULT_BOOK_TICK_PX;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer symbolBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
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

    // ----- the four jobs ----------------------------------------------------------------------

    @Test
    void jobOne_theFrozenAccidentLastsOneOccupancyNotAnEpoch() {
        // A book created BEFORE its security's first tick holds the provisional global grid. Under
        // the rejected "derive once at creation" design that accident would freeze for the whole
        // epoch. Here it lasts exactly one occupancy.
        final MatchingEngineClusteredService service = seeded();
        final int ref = rest(service, 100 * PX);
        assertEquals(GLOBAL, service.engine().bookTickPxOf(SECURITY),
            "created with no reference: the provisional global grid");
        assertEquals(0L, service.engine().bookReticks());

        cancel(service, ref);                       // the book empties
        tick(service, 1_120_000L);                  // ...and NOW it has a reference: $1.12

        final long before = service.engine().bookReticks();
        rest(service, 1_120_000L);                  // the next admission re-derives
        assertEquals(10L, service.engine().bookTickPxOf(SECURITY),
            "one occupancy later the book is on the grid its own price implies");
        assertEquals(before + 1, service.engine().bookReticks(),
            "and the accident window that fired is a COUNTED event, not a silent state change");
    }

    @Test
    void jobTwo_scaleDriftSelfHealsWithNoReIndexMechanism() {
        // A security that moves orders of magnitude re-derives at its next empty moment. This is
        // the scope's "say which" answered: ACCEPT the drift, because no state that would need a
        // re-index-at-a-different-scale mechanism can persist. Cost is a window, not an epoch.
        final MatchingEngineClusteredService service = seeded();
        tick(service, 150 * PX);
        final int ref = rest(service, 150 * PX);
        assertEquals(GLOBAL, service.engine().bookTickPxOf(SECURITY));

        // The security collapses two decades while an order rests: the grid does NOT move under it.
        tick(service, 1_500_000L);
        assertEquals(GLOBAL, service.engine().bookTickPxOf(SECURITY),
            "an OCCUPIED book never re-ticks — every resting order's slot is an index in the old unit");
        assertTrue(service.engine().bookTickDrifted(SECURITY),
            "...and the drift is VISIBLE while it lasts, which is what /bbo renders as tickDrift");

        cancel(service, ref);
        rest(service, 1_500_000L);
        assertEquals(10L, service.engine().bookTickPxOf(SECURITY), "the book turned over and healed");
        assertEquals(false, service.engine().bookTickDrifted(SECURITY));
    }

    @Test
    void jobThree_aRestoredMemberAndASurvivorAgreeOnTheNextOrdersGrid() {
        // THE DETERMINISM JOB — and the one place this build DISAGREES with the design's stated
        // reasoning, measured rather than inherited.
        //
        // Section 2.3 job 3 argues the re-derivation is load-bearing for determinism because
        // "un-anchored books are absent from the snapshot", so a restorer (no book) and a survivor
        // (book exists, empty) would otherwise disagree about the next order's grid. MEASURED
        // FALSE here: bookBaseTuples emits EVERY created book, un-anchored ones included, carrying
        // baseLevel -1 and — from format 8 — their tick. The restorer therefore rebuilds the same
        // book on the same grid, and the two agree with or without the re-derivation.
        //
        // So the STORAGE decision (section 2.4) subsumes job 3: the tick riding the record is what
        // makes a survivor and a restorer identical, not the re-derivation. Jobs 1, 2 and 4 remain
        // entirely load-bearing — the V4 detonator fails all three and does not fail this one on
        // its agreement assertion. Recorded here so a later reader does not "restore" a determinism
        // argument that this code does not need, or delete storage believing re-derivation covers it.
        final MatchingEngineClusteredService survivor = seeded();
        final int ref = rest(survivor, 100 * PX);        // book created on the global grid
        cancel(survivor, ref);                            // ...and emptied
        tick(survivor, 1_120_000L);

        final MatchingEngineClusteredService restored = restore(survivor);
        assertEquals(survivor.engine().bookTickPxOf(SECURITY), restored.engine().bookTickPxOf(SECURITY),
            "the EMPTY book itself round-trips on its stored grid");

        rest(survivor, 1_120_000L);
        rest(restored, 1_120_000L);
        assertEquals(survivor.engine().bookTickPxOf(SECURITY), restored.engine().bookTickPxOf(SECURITY),
            "a survivor and a restored member disagreeing here would be permanent divergence");
        assertEquals(10L, restored.engine().bookTickPxOf(SECURITY),
            "and both re-derive to the grid the shared replicated reference implies");
    }

    @Test
    void jobFour_theUnpricedFallbackIsProvisionalWhichIsWhyNoOptionConstantShips() {
        // The fallback never has to be RIGHT, only safe: an unpriced book is one
        // tick-then-empty-admission away from its real grid. That is what lets format 8 ship with
        // no OPTION_BOOK_TICK_PX constant at all (design section 8, settled) — the map prices each
        // option book off its own live premium, which is strictly better than a strike-derived
        // bucket and costs no new convention.
        final MatchingEngineClusteredService service = seeded("AAPL260918P00220000");
        final int ref = rest(service, 100 * PX);
        assertEquals(GLOBAL, service.engine().bookTickPxOf(SECURITY),
            "an unpriced OPTION book falls to the global grid, provisionally — no option constant");
        cancel(service, ref);
        tick(service, 504_000L);                     // the measured cheap end of the live chain
        rest(service, 504_000L);
        assertEquals(1L, service.engine().bookTickPxOf(SECURITY),
            "and heals to the grid its own premium implies: a $0.504 option lands on tick 1,"
                + " a +/-$0.0655 band — where the single constant 100 would have given it 13x premium");
    }

    // ----- the category outranks the map (design section 1.3) ----------------------------------

    @Test
    void aFractionOfParTickerKeepsItsCategoryGridEvenAbovePar() {
        // The live counterexample that inverted the scope's stated priority: CORP-JPM-20310601
        // trades ABOVE par on the rig, where the map would derive tick 10 — and a tick-10 grid
        // refuses most legal six-decimal bond quotes as off-grid INVALID. The bond grid is about
        // quote GRANULARITY, not band width, so the category must beat the map.
        final MatchingEngineClusteredService service = seeded("CORP-JPM-20310601");
        tick(service, 1_010_420L);                   // above par
        assertEquals(10L, MatchingEngine.decadeTickPx(1_010_420L, GLOBAL),
            "precondition: the MAP alone would put this book on tick 10");
        final int ref = rest(service, 1_010_420L);
        assertNotEquals(0, ref);
        assertEquals(1L, service.engine().bookTickPxOf(SECURITY),
            "the ADR-060 category wins: six decimals of par stay quotable");
        assertEquals(0L, service.engine().bookReticks(),
            "and a category-pinned book never re-ticks, whatever its price does");
    }

    // ----- the counter's own semantics --------------------------------------------------------

    @Test
    void anAdmissionThatDoesNotChangeTheTickIsNotARetick() {
        // The counter counts re-derivations that CHANGED a tick — the assertion yu17-book-retick
        // makes at its last step, and the one that stops the metric reading as "orders admitted".
        final MatchingEngineClusteredService service = seeded();
        // A book BORN on its derived grid counts nothing: bookFor already creates it there, so
        // there is no window and no accident. The counter is not "orders admitted".
        tick(service, 1_120_000L);
        final int born = rest(service, 1_120_000L);
        assertEquals(10L, service.engine().bookTickPxOf(SECURITY));
        assertEquals(0L, service.engine().bookReticks(),
            "creation on the derived grid is not a re-tick — nothing changed");
        cancel(service, born);

        // An empty admission that computes the SAME tick counts nothing either.
        final int again = rest(service, 1_120_000L);
        assertNotEquals(0, again);
        assertEquals(0L, service.engine().bookReticks(),
            "an empty admission that computes the same tick is not a re-tick");
        cancel(service, again);

        // Only a CHANGED tick counts — the last step yu17-book-retick asserts on the rig.
        tick(service, 150 * PX);
        rest(service, 150 * PX);
        assertEquals(GLOBAL, service.engine().bookTickPxOf(SECURITY));
        assertEquals(1L, service.engine().bookReticks(),
            "the decade crossing is the only thing in this sequence the counter sees");
    }

    // ----- gate V3: what bookBaseTuples contains ----------------------------------------------

    @Test
    void gateV3_bookBaseTuplesCarriesEveryCreatedBookWithItsTick() {
        // Gate V3, MEASURED and reported rather than assumed. The design expected un-anchored books
        // to be EXCLUDED from the snapshot; they are not — bookBaseTuples emits every CREATED book,
        // and an un-anchored one rides with baseLevel -1, which bootstrapBook restores as
        // "created, not anchored". That is fine and is now BETTER than exclusion would be: with the
        // tick stored, a survivor and a restorer hold the same grid for such a book instead of one
        // of them having to re-derive it. Recorded here because the design's V3 wording says
        // otherwise, and a later reader must not "fix" the code to match the doc.
        final MatchingEngineClusteredService service = seeded();
        final CapturingSession sink = new CapturingSession();
        final InputEvent unknownPrice = newOrder(1_000_000_000L);   // far outside any band: rejected
        apply(service, sink, unknownPrice);

        final List<long[]> tuples = service.engine().bookBaseTuples();
        assertEquals(1, tuples.size(), "a book is created by the ORDER, even one that is rejected");
        assertEquals(SECURITY, (int) tuples.get(0)[0]);
        assertEquals(3, tuples.get(0).length, "format 8: {securityId, baseLevel, tickPx}");
        assertTrue(tuples.get(0)[2] > 0, "every emitted book carries a positive tick");
    }

    // ----- harness ----------------------------------------------------------------------------

    private MatchingEngineClusteredService seeded() {
        return seeded("FNMA");
    }

    /** One enabled account and one enabled security registered under {@code ticker}. */
    private MatchingEngineClusteredService seeded(final String ticker) {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        final CapturingSession sink = new CapturingSession();
        codec.encodeSymbolRegister(symbolBuffer, 0, 1L, ticker);
        service.onSessionMessage(sink, ++timestamp, symbolBuffer, 0,
            AeronReplicationCodec.SYMBOL_BYTES, null);
        assertEquals(SECURITY, service.symbolIdFor(ticker), "the ticker must take id 0");

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
        return service;
    }

    /** Rest a BUY at {@code limitPx}; asserts it was accepted and returns its ref. */
    private int rest(final MatchingEngineClusteredService service, final long limitPx) {
        final CapturingSession session = new CapturingSession();
        apply(service, session, newOrder(limitPx));
        final UnsafeBuffer ack = new UnsafeBuffer(session.egress.get(0));
        assertEquals(OutputEvent.KIND_ORDER_ACCEPTED, ack.getByte(12),
            "the probe must rest for this arm to say anything; reason byte "
                + RiskReason.values()[ack.getByte(22)]);
        return ack.getInt(8);
    }

    private void cancel(final MatchingEngineClusteredService service, final int orderRef) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_CANCEL;
        e.orderRef = orderRef;
        apply(service, new CapturingSession(), e);
        assertEquals(0, service.engine().book(SECURITY).openOrders(), "the book must be empty");
    }

    private void tick(final MatchingEngineClusteredService service, final long px) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK;
        e.securityId = SECURITY;
        e.priceTicks = px;
        apply(service, new CapturingSession(), e);
    }

    private InputEvent newOrder(final long limitPx) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.accountId = ACCOUNT;
        e.securityId = SECURITY;
        e.side = InputEvent.SIDE_BUY;
        e.qty = 10;
        e.limitPx = limitPx;
        return e;
    }

    private void apply(final MatchingEngineClusteredService service, final ClientSession session,
                       final InputEvent event) {
        codec.encodeInput(ingressBuffer, 0, event, 0, 0, 0);
        service.onSessionMessage(session, ++timestamp, ingressBuffer, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }

    /** Round-trip a service through its own snapshot records. */
    private MatchingEngineClusteredService restore(final MatchingEngineClusteredService source) {
        final List<byte[]> records = new ArrayList<>();
        source.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            records.add(copy);
        });
        final MatchingEngineClusteredService target = new MatchingEngineClusteredService();
        target.initEngine();
        boolean done = false;
        for (final byte[] record : records) {
            done = target.onSnapshotRecord(new UnsafeBuffer(record), 0);
        }
        assertTrue(done, "the record stream must terminate with END");
        return target;
    }
}
