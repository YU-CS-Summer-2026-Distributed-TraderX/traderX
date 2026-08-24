package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.SwapConventions;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YU17: booked OTC contracts on the YU05 regulatory audit surface
 * ({@code issues/…/otc-bookings-absent-from-the-regulatory-report.md}).
 *
 * <p>The measured defect: a swap consumed a consensus sequence and returned {@code booked:true},
 * and {@code /regulatory/report} over a range bracketing that sequence returned nothing. The
 * projection enumerated six order-lifecycle kinds and an entire instrument class was absent from
 * it in silence.
 *
 * <p>What is provable here and what is not. The replay needs a live Aeron Archive, so these drive
 * {@link ClusterRecon#applyAndProject} — the seam the replay's fragment handler calls, one apply
 * plus the projection of what that apply committed — through a REAL
 * {@link MatchingEngineClusteredService}. The archive plumbing either side of it is proven on the
 * rig by booking through the live gateway and reading the booking back off the report.
 *
 * <p>{@link #anOtcBookingProducesNoOutputEventAtAll()} is the negative control and the reason this
 * class exists rather than one more kind in {@code isReportableKind}: there is no output event to
 * have been filtered out, so a test that asserted against the output tap would pass for a
 * projection that still reported nothing.
 */
class ClusterReconOtcTest {
    private static final int BUYER = 22214;      // real accounts in counterparties.csv
    private static final int SELLER = 42422;
    private static final int NOTIONAL = 10_000_000;
    private static final long RECEIVE_RATE_TICKS = 42_000L;   // 4.2%
    private static final long PAY_RATE_TICKS = 43_000L;       // 4.3%
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 8, 17);
    private static final LocalDate MATURITY = LocalDate.of(2031, 8, 17);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 17);
    private static final int BERMUDAN = 1;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingress = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer marker =
        new UnsafeBuffer(new byte[AeronReplicationCodec.RISK_EXTRACT_BYTES]);
    private final List<ClusterRecon.AuditRow> rows = new ArrayList<>();
    private long timestamp = 1_700_000_000_000L;

    // ----- the headline --------------------------------------------------------------------------

    @Test
    void aBookedSwapBecomesAnAuditRowWithItsOwnIdentityAndTerms() {
        final MatchingEngineClusteredService shadow = enabledAccounts();
        apply(shadow, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));

        assertEquals(1, rows.size(), "a booked swap must reach the audit projection");
        final ClusterRecon.AuditRow row = rows.get(0);
        assertEquals("SWAP_BOOKED", row.kind());
        assertEquals(1, shadow.contractCount(), "sanity: exactly one contract was committed");
        final long contractId = shadow.contractTuples().get(0)[0];
        assertEquals(contractId, row.inputSeq(),
            "a contract id IS its booking sequence; that is what makes one fromSeq/toSeq range"
                + " select orders and bookings alike");
        assertEquals("SW-" + contractId, row.orderId(),
            "the identifier a reader greps for, verbatim as the contracts artifact prints it");
        assertNull(row.tradeId(), "a swap books no trade");
        assertEquals(BUYER, row.accountId());
        assertEquals("USD-SOFR-1Y-ACT360", row.security());
        assertEquals("RECEIVE_FIXED", row.side());
        assertEquals(NOTIONAL, row.quantity(), "quantity is the notional, per contract, unnetted");
        assertEquals(new BigDecimal("0.042000"), row.price(), "price is the fixed rate");
        assertEquals("ACCEPTED", row.riskReason(),
            "a contract only reaches the store because the credit gate passed it; a REFUSED "
                + "booking produces no row at all to carry a reason");
        assertEquals(timestamp, row.timestampMillis(),
            "the booking time is the cluster time of the message that applied it");
    }

    @Test
    void theOffsettingPairIsTwoRowsWithBothRatesIntact() {
        // The design constraint, mirrored onto this surface. At (accountId, security) grain these
        // two are quantity zero and BOTH rates are gone -- the loss yu17-swap-netting.sh step 7
        // exists to catch. An audit row per contract is the only shape that survives it.
        final MatchingEngineClusteredService shadow = enabledAccounts();
        apply(shadow, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        apply(shadow, swap(BUYER, InputEvent.SWAP_PAY_FIXED, PAY_RATE_TICKS, 2L));

        assertEquals(2, rows.size(), "the pair that nets to zero is TWO rows, not one and not none");
        assertEquals(rows.get(0).quantity(), rows.get(1).quantity(), "sanity: the notionals offset");
        assertNotEquals(rows.get(0).side(), rows.get(1).side(),
            "sanity: the directions are opposite -- otherwise this pair does not net to zero");
        assertEquals("RECEIVE_FIXED", rows.get(0).side());
        assertEquals("PAY_FIXED", rows.get(1).side());
        assertEquals(new BigDecimal("0.042000"), rows.get(0).price());
        assertEquals(new BigDecimal("0.043000"), rows.get(1).price());
        assertNotEquals(rows.get(0).orderId(), rows.get(1).orderId(), "two contracts, two ids");
        assertTrue(shadow.engine().positionTuples().isEmpty(),
            "and the booking still never reached the position model");
    }

    // ----- the negative control ------------------------------------------------------------------

    @Test
    void anOtcBookingProducesNoOutputEventAtAll() {
        // Why this is not "the projection learns a seventh kind". onSwapBook answers with a direct
        // egress ack and never offers to the output ring, so the tap the report was built on sees
        // NOTHING go past. A check written against out.kind cannot distinguish a working OTC
        // projection from the defect.
        final List<Byte> kinds = new ArrayList<>();
        final MatchingEngineClusteredService shadow = new MatchingEngineClusteredService();
        shadow.initEngine();
        shadow.outputSink(out -> kinds.add(out.kind));
        apply(shadow, accountControl(BUYER));
        apply(shadow, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));

        assertEquals(1, shadow.contractCount(), "precondition: the booking really was committed");
        assertTrue(kinds.isEmpty(),
            "a booking emits no OutputEvent; the audit row must come from the contract store: " + kinds);
        assertFalse(kinds.contains(OutputEvent.KIND_ORDER_ACCEPTED));
        assertEquals(1, rows.size(), "...and it does");
    }

    @Test
    void aRefusedBookingProducesNoRow() {
        // The mirror: only BOOKED contracts are enumerated. An unknown account leaves no replicated
        // state, so the projection has nothing to render and must not invent a row. (That a refused
        // booking is therefore invisible on this surface, where a refused ORDER is not, is recorded
        // in issues/open as its own gap -- it needs an emit-side decision, not this change.)
        final MatchingEngineClusteredService shadow = enabledAccounts();
        apply(shadow, swap(999123, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        assertEquals(0, shadow.contractCount());
        assertTrue(rows.isEmpty(), "a refused booking must not appear as a booked contract");
    }

    @Test
    void aRetriedBookingIsOneRow() {
        final MatchingEngineClusteredService shadow = enabledAccounts();
        apply(shadow, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 77L));
        apply(shadow, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 77L));
        assertEquals(1, shadow.contractCount(), "precondition: idempotency held in the engine");
        assertEquals(1, rows.size(), "a duplicated 10mm confirmation must not become two audit rows");
    }

    // ----- swaptions, and agreement with the artifact ----------------------------------------------

    @Test
    void aSwaptionIsItsOwnKindAndItsOwnIdNamespace() {
        final MatchingEngineClusteredService shadow = enabledAccounts();
        apply(shadow, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        apply(shadow, swaption(SELLER, InputEvent.SWAP_PAY_FIXED, PAY_RATE_TICKS, BERMUDAN, 2L));

        assertEquals(2, rows.size());
        assertEquals("SWAP_BOOKED", rows.get(0).kind());
        assertEquals("SWAPTION_BOOKED", rows.get(1).kind());
        assertTrue(rows.get(0).orderId().startsWith("SW-"), rows.get(0).orderId());
        assertTrue(rows.get(1).orderId().startsWith("SWPT-"), rows.get(1).orderId());
        assertEquals(new BigDecimal("0.043000"), rows.get(1).price(),
            "a swaption's price column is the STRIKE, which is the underlying's fixed rate");
        assertEquals(NOTIONAL, rows.get(1).quantity(),
            "the notional is the UNDERLYING's, not a premium");
    }

    @Test
    void theReportAndTheContractsArtifactNameTheSameContractIdentically() {
        // The point of the change: the EOD artifact stops being the ONLY way to see a booking. Two
        // surfaces describing one contract in two vocabularies would be two things to reconcile,
        // so id, direction and rate are asserted to be the SAME strings on both.
        final MatchingEngineClusteredService shadow = enabledAccounts();
        apply(shadow, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        apply(shadow, swaption(SELLER, InputEvent.SWAP_PAY_FIXED, PAY_RATE_TICKS, BERMUDAN, 2L));

        final List<String> csv = artifactRows(shadow);
        assertEquals(rows.size(), csv.size(), "one artifact row per audit row");
        for (int i = 0; i < csv.size(); i++) {
            final String[] c = csv.get(i).split(",", -1);
            final ClusterRecon.AuditRow row = rows.get(i);
            assertEquals(c[0], row.orderId(), "contractId column vs orderId: " + csv.get(i));
            assertEquals(Integer.parseInt(c[1]), row.accountId());
            assertEquals(c[2], row.side(), "payReceive column vs side: " + csv.get(i));
            assertEquals(Long.parseLong(c[3]), row.quantity());
            assertEquals(c[4], row.price().toPlainString(), "fixedRate column vs price");
        }
    }

    @Test
    void anUnknownConventionIsNamedOpaquelyNotResolvedAndNotFatal() {
        // Deliberately unlike SwapContractCsv, which aborts: that artifact publishes a day count
        // and must not guess one, whereas this row publishes no day count at all, and aborting
        // would blank an entire regulatory report over one contract booked by a later build.
        final int future = SwapConventions.count() + 5;
        final ClusterRecon.AuditRow row = ClusterRecon.otcAuditRow(
            new long[] {19864L, BUYER, 0L, NOTIONAL, RECEIVE_RATE_TICKS, future,
                EFFECTIVE.toEpochDay(), MATURITY.toEpochDay(), 0L, 0L, 0L}, timestamp);
        assertEquals("CONVENTION_" + future, row.security());
        assertNotEquals(SwapConventions.at(0).name(), row.security(),
            "a later build's convention must never resolve to index 0's");
        assertEquals("SW-19864", row.orderId(), "the rest of the row still renders");
    }

    // ----- harness ---------------------------------------------------------------------------------

    private MatchingEngineClusteredService enabledAccounts() {
        final MatchingEngineClusteredService shadow = new MatchingEngineClusteredService();
        shadow.initEngine();
        apply(shadow, accountControl(SELLER));
        apply(shadow, accountControl(BUYER));
        return shadow;
    }

    /**
     * Drive one message through the replay's own seam, collecting what it projects. This is
     * {@code ClusterRecon.replay}'s fragment handler minus the cluster session-header unwrap.
     */
    private void apply(final MatchingEngineClusteredService shadow, final InputEvent event) {
        codec.encodeInput(ingress, 0, event, 0, 0, 0);
        ClusterRecon.applyAndProject(shadow, ++timestamp, ingress, 0,
            AeronReplicationCodec.INPUT_BYTES,
            (contract, millis) -> rows.add(ClusterRecon.otcAuditRow(contract, millis)));
    }

    /** The contracts artifact rendered from the same state, for the cross-surface comparison. */
    private List<String> artifactRows(final MatchingEngineClusteredService shadow) {
        codec.encodeRiskExtract(marker, 0, ++timestamp, EFFECTIVE.toEpochDay(), 3);
        shadow.onSessionMessage(null, ++timestamp, marker, 0,
            AeronReplicationCodec.RISK_EXTRACT_BYTES, null);
        final String cut = RiskExtractCut.render(shadow.lastExtractCutSeq(), EFFECTIVE.toEpochDay(),
            3, shadow.engine().positionTuples(), shadow.engine().priceTuples(),
            new String[MatchingEngineClusteredService.MAX_SECURITIES],
            id -> shadow.risk().contractMultiplier(id), shadow.contractTuples());
        final Map<Integer, RiskExtractCsv.Counterparty> accounts = new HashMap<>();
        accounts.put(SELLER, new RiskExtractCsv.Counterparty("CPTY-DELTA-PRIME", "NS-DELT-ISDA-01", "USD"));
        accounts.put(BUYER, new RiskExtractCsv.Counterparty("CPTY-CASCADE-AM", "NS-CASC-ISDA-01", "USD"));
        final String artifact = SwapContractCsv.render(cut, accounts,
            new RiskExtractCsv.Stamp(shadow.lastExtractCutSeq(), EFFECTIVE, 3,
                RiskExtractCut.sha256(cut)));
        return artifact.lines()
            .filter(l -> !l.startsWith("#") && !l.isEmpty() && !l.equals(SwapContractCsv.HEADER))
            .toList();
    }

    private InputEvent swap(final int accountId, final byte direction, final long rateTicks,
                            final long clientKey) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SWAP_BOOK;
        e.accountId = accountId;
        e.side = direction;
        e.qty = NOTIONAL;
        e.limitPx = rateTicks;
        e.securityId = 0;   // USD-SOFR-1Y-ACT360
        e.setClientOrderKey(clientKey);
        e.setSwapDates((int) EFFECTIVE.toEpochDay(), (int) MATURITY.toEpochDay());
        e.eventTimeMillis = timestamp;
        return e;
    }

    private InputEvent swaption(final int accountId, final byte direction, final long strikeTicks,
                                final int exerciseStyle, final long clientKey) {
        final InputEvent e = swap(accountId, direction, strikeTicks, clientKey);
        e.type = InputEvent.TYPE_SWAPTION_BOOK;
        e.setSwaptionTerms(0, exerciseStyle, (int) EXPIRY.toEpochDay());
        return e;
    }

    private InputEvent accountControl(final int accountId) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        e.accountId = accountId;
        e.setControlEnabled(true);
        e.setControlVersion(1L);
        return e;
    }
}
