package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.SwapConventions;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YU17 acceptance proofs for OTC swap booking, driven through the real sequenced ingress path and
 * requiring no cluster.
 *
 * <p>The headline is {@link #twoOffsettingSwapsSurviveAsTwoContracts()}: the pair that our netted
 * position grain would reduce to nothing survives as two contracts with both rates intact. Every
 * other test here exists to stop that one from passing for the wrong reason.
 */
class SwapBookingTest {
    private static final int SELLER = 42422;   // both are real accounts in counterparties.csv
    private static final int BUYER = 22214;
    private static final LocalDate SESSION = LocalDate.of(2026, 8, 17);
    private static final int PRICE_VERSION = 3;
    private static final int NOTIONAL = 10_000_000;
    private static final long RECEIVE_RATE_TICKS = 42_000L;   // 4.2%
    private static final long PAY_RATE_TICKS = 43_000L;       // 4.3%
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 8, 17);
    private static final LocalDate MATURITY = LocalDate.of(2031, 8, 17);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 17);   // on the effective date
    private static final int EUROPEAN = 0;
    private static final int BERMUDAN = 1;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer markerBuffer =
        new UnsafeBuffer(new byte[AeronReplicationCodec.RISK_EXTRACT_BYTES]);
    private long timestamp = 1_000_000_000_000L;

    // ----- the headline ------------------------------------------------------------------------

    @Test
    void twoOffsettingSwapsSurviveAsTwoContracts() {
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        apply(service, swap(BUYER, InputEvent.SWAP_PAY_FIXED, PAY_RATE_TICKS, 2L));

        // The netting loss, stated as the arithmetic that would have caused it: same account, same
        // notional, opposite directions. At an (accountId, security) grain this is quantity zero.
        assertEquals(2, service.contractCount(),
            "the pair that nets to zero quantity is TWO contracts, not one and not none");
        final List<long[]> contracts = service.contractTuples();
        assertEquals(contracts.get(0)[3], contracts.get(1)[3], "sanity: the notionals do offset");
        assertNotEquals(contracts.get(0)[2], contracts.get(1)[2],
            "sanity: the directions are opposite — otherwise this pair does not net to zero");
        assertNotEquals(contracts.get(0)[4], contracts.get(1)[4],
            "the two rates are distinct and neither has been averaged away");
        assertEquals(RECEIVE_RATE_TICKS, contracts.get(0)[4]);
        assertEquals(PAY_RATE_TICKS, contracts.get(1)[4]);

        // And nothing became a position: the netted extract is untouched by either booking.
        assertTrue(service.engine().positionTuples().isEmpty(),
            "a swap must never reach the position model — that is where the netting loss happens");
        assertEquals(0, service.engine().tradeCounter(), "a swap books no trade");

        // End to end: the rendered contracts artifact carries both, per trade.
        final String cut = markAndCapture(service);
        final String artifact = SwapContractCsv.render(cut, counterparties(), stamp(cut));
        final List<String> rows = dataRows(artifact);
        assertEquals(2, rows.size(), artifact);
        assertTrue(rows.get(0).contains(",RECEIVE_FIXED,10000000,0.042000,"), rows.get(0));
        assertTrue(rows.get(1).contains(",PAY_FIXED,10000000,0.043000,"), rows.get(1));
    }

    @Test
    void theNettedExtractNeverCarriesASwap() {
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        final String cut = markAndCapture(service);

        // Non-empty precondition: the netted renderer must be looking at a cut that HAS a swap in
        // it, or "no swap rows" is satisfied by there being nothing to find.
        assertTrue(cut.contains(RiskExtractCut.CONTRACTS_MARKER + "\n"), cut);
        assertEquals(1, dataRows(SwapContractCsv.render(cut, counterparties(), stamp(cut))).size());

        final String netted = RiskExtractCsv.render(cut, Map.of(), counterparties(), Map.of(), stamp(cut));
        assertEquals(3, RiskExtractCsv.SCHEMA, "the netted extract's schema does not move for swaps (D3)");
        assertTrue(dataRows(netted).isEmpty(),
            "the netted extract parsed the contracts section as positions: " + netted);
    }

    // ----- the cut carries both sections, deterministically -------------------------------------

    @Test
    void theContractsSectionIsPresentEvenWithNoSwaps() {
        final String cut = markAndCapture(enabledAccounts());
        assertTrue(cut.contains(" contracts=0\n"), cut.lines().findFirst().orElse(""));
        assertTrue(cut.contains(RiskExtractCut.CONTRACTS_MARKER + "\n"
            + RiskExtractCut.CONTRACTS_HEADER + "\n"), cut);
        // An absent section and an empty one are opposite facts; the renderer must refuse to guess.
        final String withoutSection = cut.substring(0, cut.indexOf(RiskExtractCut.CONTRACTS_MARKER));
        assertThrows(IllegalStateException.class,
            () -> SwapContractCsv.render(withoutSection, counterparties(), stamp(cut)));
    }

    @Test
    void aTruncatedContractsSectionIsRefused() {
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        apply(service, swap(SELLER, InputEvent.SWAP_PAY_FIXED, PAY_RATE_TICKS, 2L));
        final String cut = markAndCapture(service);
        final RiskExtractCsv.Stamp stamp = stamp(cut);

        final int lastNewline = cut.lastIndexOf('\n', cut.length() - 2);
        final String truncated = cut.substring(0, lastNewline + 1);
        assertThrows(IllegalStateException.class,
            () -> SwapContractCsv.render(truncated, counterparties(), stamp),
            "a cut declaring 2 contracts but carrying 1 must not render");
    }

    @Test
    void aContractBookedUnderAnUnknownConventionIsRefusedNotGuessed() {
        final MatchingEngineClusteredService service = enabledAccounts();
        final InputEvent booking = swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L);
        booking.securityId = SwapConventions.count() + 5;   // a later build's convention index
        apply(service, booking);
        assertEquals(1, service.contractCount(),
            "the engine stores what the log committed; it is the RENDER that must refuse");

        final String cut = markAndCapture(service);
        final RiskExtractCsv.Stamp stamp = stamp(cut);
        assertThrows(IllegalStateException.class,
            () -> SwapContractCsv.render(cut, counterparties(), stamp),
            "an unknown convention index must abort, not resolve to index 0's day count");
    }

    // ----- the store survives a snapshot --------------------------------------------------------

    @Test
    void contractsRestoreByteIdenticallyFromASnapshot() {
        final MatchingEngineClusteredService source = enabledAccounts();
        apply(source, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        apply(source, swap(SELLER, InputEvent.SWAP_PAY_FIXED, PAY_RATE_TICKS, 2L));
        final MatchingEngineClusteredService replica = restore(source);

        assertEquals(source.contractCount(), replica.contractCount());
        for (int i = 0; i < source.contractCount(); i++) {
            assertArrayEqualsLong(source.contractTuples().get(i), replica.contractTuples().get(i));
        }
        assertEquals(markAndCapture(source), markAndCapture(replica),
            "a restored member renders the identical cut, contracts included");
    }

    @Test
    void aSnapshotFromThisBuildDeclaresFormatSix() {
        final List<byte[]> records = snapshotRecords(enabledAccounts());
        final UnsafeBuffer header = new UnsafeBuffer(records.get(0));
        assertEquals(MatchingEngineClusteredService.T_HEADER, header.getInt(0));
        assertEquals(6, header.getInt(4), "T_CONTRACT changed shape; the format must say so");
        assertEquals(3, MatchingEngineClusteredService.MIN_READABLE_SNAPSHOT_FORMAT,
            "adding a record type does not stop older snapshots restoring — do not raise this");
    }

    @Test
    void aFormatFourSnapshotStillRestores() {
        // The forward-roll path: an epoch minted by YU16 must come up on this build untouched.
        final List<byte[]> records = snapshotRecords(enabledAccounts());
        final UnsafeBuffer header = new UnsafeBuffer(records.get(0));
        header.putInt(4, 4);
        final MatchingEngineClusteredService restored = new MatchingEngineClusteredService();
        restored.initEngine();
        boolean done = false;
        for (final byte[] record : records) {
            done = restored.onSnapshotRecord(new UnsafeBuffer(record), 0);
        }
        assertTrue(done, "a format-4 snapshot must still restore here");
        assertEquals(0, restored.contractCount(), "a format-4 epoch simply has no contracts yet");
    }

    // ----- the risk gate --------------------------------------------------------------------------

    @Test
    void theGateRefusesAnUnknownAccountAndCreatesNoContract() {
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, swap(999123, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        assertEquals(0, service.contractCount(), "a refused booking must leave no contract behind");
        assertEquals(RiskReason.UNKNOWN_ACCOUNT, decisionFor(service, 999123));
    }

    @Test
    void theGateRefusesADisabledAccount() {
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, accountControl(BUYER, false));
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        assertEquals(0, service.contractCount());
        assertEquals(RiskReason.ACCOUNT_DISABLED, decisionFor(service, BUYER));
    }

    @Test
    void theGateMeasuresTheNOTIONALNotQuantityTimesRate() {
        // The whole reason a swap path exists. quantity x price would value this 10mm swap at
        // 10,000,000 x 0.042 = 420,000 — a 24x understatement that no check would ever notice.
        // Set the account's credit so that the true notional breaches it and the wrong one does
        // not: an accept here means the gate is using the wrong formula.
        final MatchingEngineClusteredService service = enabledAccounts();
        final long trueNotionalTicks = 10_000_000L * 1_000_000L;
        final long wrongNotionalTicks = 420_000L * 1_000_000L;
        assertTrue(wrongNotionalTicks < trueNotionalTicks, "sanity: the wrong formula understates");

        // Book until credit is a known quantity, then assert on the reason rather than on a
        // configured limit this test does not own: the shipped limits are effectively unbounded, so
        // instead prove the accrual itself moves by the notional.
        final long before = service.risk().executedNotional(BUYER);
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        final long accrued = service.risk().executedNotional(BUYER) - before;
        assertEquals(trueNotionalTicks, accrued,
            "credit must be consumed by the NOTIONAL; quantity x rate would be " + wrongNotionalTicks);
        assertNotEquals(wrongNotionalTicks, accrued);
    }

    @Test
    void aRetriedBookingAnswersTheOriginalContract() {
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 77L));
        final long firstId = service.contractTuples().get(0)[0];
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 77L));

        assertEquals(1, service.contractCount(),
            "a retried confirmation must not double-book a 10mm swap");
        assertEquals(firstId, service.contractTuples().get(0)[0], "the original contract id stands");
    }

    @Test
    void keyLessBookingsAreDistinctContracts() {
        // The mirror of the test above: idempotency must not silently collapse two genuinely
        // different bookings that simply carry no client id.
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 0L));
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 0L));
        assertEquals(2, service.contractCount());
    }

    // ----- the record's slot packing --------------------------------------------------------------

    @Test
    void theDatePairSurvivesTheWire() {
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        final long[] contract = service.contractTuples().get(0);
        assertEquals(EFFECTIVE.toEpochDay(), contract[6]);
        assertEquals(MATURITY.toEpochDay(), contract[7]);
        assertTrue(MATURITY.toEpochDay() <= InputEvent.MAX_SWAP_EPOCH_DAY,
            "sanity: this fixture is inside the representable range");
    }

    @Test
    void thePackedDatesRoundTripAcrossTheWholeRepresentableRange() {
        final InputEvent event = new InputEvent();
        for (final int day : new int[] {0, 1, 20_684, 65_534, InputEvent.MAX_SWAP_EPOCH_DAY}) {
            event.setSwapDates(day, InputEvent.MAX_SWAP_EPOCH_DAY - day);
            assertEquals(day, event.swapEffectiveEpochDay());
            assertEquals(InputEvent.MAX_SWAP_EPOCH_DAY - day, event.swapMaturityEpochDay());
        }
        // The boundary the gateway refuses at: one past the range aliases onto a plausible date,
        // which is exactly why the refusal lives at the boundary and not here.
        event.setSwapDates(InputEvent.MAX_SWAP_EPOCH_DAY + 1, 0);
        assertEquals(0, event.swapEffectiveEpochDay(),
            "a day past the range wraps — the gateway must refuse it before it is sequenced");
    }

    @Test
    void conventionIndicesResolveToTheirCompiledMeaning() {
        assertEquals(0, SwapConventions.indexOf("USD-SOFR-1Y-ACT360"),
            "index 0 is journaled in every existing booking; it cannot move");
        assertEquals("USD-SOFR", SwapConventions.at(0).floatIndex());
        assertEquals("ACT/360", SwapConventions.at(0).dayCount());
        assertEquals("USD", SwapConventions.at(0).currency());
        assertEquals(-1, SwapConventions.indexOf("USD-LIBOR-3M"), "an unknown name resolves to -1, not 0");
        assertFalse(SwapConventions.knows(SwapConventions.count()));
        assertThrows(IllegalStateException.class, () -> SwapConventions.at(SwapConventions.count()));
    }

    // ----- swaptions (phase 2) ---------------------------------------------------------------

    @Test
    void aEuropeanAndABermudanOnIdenticalTermsAreTwoContracts() {
        // The phase-2 headline, and a sharper version of the swap one. These two are identical in
        // EVERY column a position model could see and in every column a swap record carries — same
        // account, direction, notional, strike, dates, conventions. Only the exercise style differs,
        // and a Bermudan is worth materially more than a European. If the style were not a
        // published term these would be indistinguishable in the risk file.
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, swaption(BUYER, InputEvent.SWAP_PAY_FIXED, RECEIVE_RATE_TICKS, EUROPEAN, 1L));
        apply(service, swaption(BUYER, InputEvent.SWAP_PAY_FIXED, RECEIVE_RATE_TICKS, BERMUDAN, 2L));

        assertEquals(2, service.contractCount());
        final List<long[]> c = service.contractTuples();
        for (int col : new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9}) {
            assertEquals(c.get(0)[col], c.get(1)[col], "column " + col + " must be identical");
        }
        assertNotEquals(c.get(0)[10], c.get(1)[10], "only the exercise style distinguishes them");

        final String cut = markAndCapture(service);
        final String artifact = SwapContractCsv.render(cut, counterparties(), stamp(cut));
        final List<String> rows = dataRows(artifact);
        assertEquals(2, rows.size(), artifact);
        assertTrue(rows.get(0).endsWith(",SWAPTION," + EXPIRY + ",EUROPEAN"), rows.get(0));
        assertTrue(rows.get(1).endsWith(",SWAPTION," + EXPIRY + ",BERMUDAN"), rows.get(1));
    }

    @Test
    void aSwaptionCarriesItsUnderlyingSwapsTermsUnchanged() {
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, swaption(BUYER, InputEvent.SWAP_PAY_FIXED, PAY_RATE_TICKS, EUROPEAN, 1L));
        final long[] c = service.contractTuples().get(0);
        assertEquals(BUYER, c[1]);
        assertEquals(1L, c[2], "PAY_FIXED on the underlying = a payer swaption");
        assertEquals(NOTIONAL, c[3], "the notional is the UNDERLYING's, not a premium");
        assertEquals(PAY_RATE_TICKS, c[4], "the underlying's fixed rate IS the strike");
        assertEquals(EFFECTIVE.toEpochDay(), c[6]);
        assertEquals(MATURITY.toEpochDay(), c[7]);
        assertEquals(1L, c[8], "productType SWAPTION");
        assertEquals(EXPIRY.toEpochDay(), c[9]);
        assertEquals(EUROPEAN, c[10]);
    }

    @Test
    void aSwapCarriesAnEmptyOptionWrapper() {
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        final long[] c = service.contractTuples().get(0);
        assertEquals(0L, c[8], "productType SWAP");
        assertEquals(0L, c[9], "a swap has no expiry");
        assertEquals(0L, c[10], "a swap has no exercise style");
        final String cut = markAndCapture(service);
        final String row = dataRows(SwapContractCsv.render(cut, counterparties(), stamp(cut))).get(0);
        assertTrue(row.startsWith("SW-"), row);
        assertTrue(row.endsWith(",SWAP,,"), "a swap leaves the two option columns empty: " + row);
    }

    @Test
    void swapsAndSwaptionsShareOneArtifactAndOneIdSpace() {
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        apply(service, swaption(SELLER, InputEvent.SWAP_PAY_FIXED, PAY_RATE_TICKS, BERMUDAN, 2L));
        final String cut = markAndCapture(service);
        final String artifact = SwapContractCsv.render(cut, counterparties(), stamp(cut));
        final List<String> rows = dataRows(artifact);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).startsWith("SW-"), rows.get(0));
        assertTrue(rows.get(1).startsWith("SWPT-"), rows.get(1));
        // The ids are the booking sequences, so they are distinct across products by construction.
        assertNotEquals(rows.get(0).split(",")[0].substring(3),
            rows.get(1).split(",")[0].substring(5));
        assertTrue(service.engine().positionTuples().isEmpty(), "neither product becomes a position");
    }

    @Test
    void anUnknownExerciseStyleIsRefusedNotGuessed() {
        final MatchingEngineClusteredService service = enabledAccounts();
        final InputEvent booking = swaption(BUYER, InputEvent.SWAP_PAY_FIXED, PAY_RATE_TICKS, EUROPEAN, 1L);
        booking.setSwaptionTerms(0, SwapConventions.exerciseStyleCount() + 3, (int) EXPIRY.toEpochDay());
        apply(service, booking);
        assertEquals(1, service.contractCount(), "the engine stores what the log committed");
        final String cut = markAndCapture(service);
        final RiskExtractCsv.Stamp stamp = stamp(cut);
        assertThrows(IllegalStateException.class,
            () -> SwapContractCsv.render(cut, counterparties(), stamp),
            "a Bermudan published as European is a different instrument; refuse instead");
    }

    @Test
    void theSwaptionTermsWordRoundTrips() {
        final InputEvent e = new InputEvent();
        for (final int day : new int[] {0, 1, 20_684, InputEvent.MAX_SWAP_EPOCH_DAY}) {
            e.setSwaptionTerms(4, 2, day);
            assertEquals(4, e.swapConventionIndex(), "the convention index is the low byte");
            assertEquals(2, e.swaptionExerciseStyle());
            assertEquals(day, e.swaptionExpiryEpochDay());
        }
        // A swap sets the slot directly and must read back the same convention index, with no
        // wrapper — this is what lets both products share swapConventionIndex() without a branch.
        e.securityId = 3;
        assertEquals(3, e.swapConventionIndex());
        assertEquals(0, e.swaptionExpiryEpochDay());
    }

    @Test
    void aFormatFiveSnapshotRestoresItsSwapsAsSwaps() {
        // The phase-1 forward-roll path: a format-5 T_CONTRACT carries eight columns and no option
        // wrapper. Reading it at the new width would take the next record's bytes as an expiry.
        final MatchingEngineClusteredService source = enabledAccounts();
        apply(source, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        apply(source, swap(SELLER, InputEvent.SWAP_PAY_FIXED, PAY_RATE_TICKS, 2L));

        // Rewrite this build's snapshot as a format-5 one: header says 5, and every T_CONTRACT is
        // truncated to its first eight columns.
        final List<byte[]> records = new ArrayList<>();
        for (final byte[] record : snapshotRecords(source)) {
            final UnsafeBuffer view = new UnsafeBuffer(record);
            if (view.getInt(0) == MatchingEngineClusteredService.T_HEADER) {
                view.putInt(4, 5);
            } else if (view.getInt(0) == MatchingEngineClusteredService.T_CONTRACT) {
                records.add(java.util.Arrays.copyOf(record, 4 + 8 * 8));
                continue;
            }
            records.add(record);
        }

        final MatchingEngineClusteredService restored = new MatchingEngineClusteredService();
        restored.initEngine();
        boolean done = false;
        for (final byte[] record : records) {
            done = restored.onSnapshotRecord(new UnsafeBuffer(record), 0);
        }
        assertTrue(done, "a format-5 snapshot must still restore here");
        assertEquals(2, restored.contractCount());
        for (int i = 0; i < 2; i++) {
            final long[] before = source.contractTuples().get(i);
            final long[] after = restored.contractTuples().get(i);
            for (int col = 0; col < 8; col++) {
                assertEquals(before[col], after[col], "column " + col);
            }
            assertEquals(0L, after[8], "a format-5 contract is a SWAP");
            assertEquals(0L, after[9]);
            assertEquals(0L, after[10]);
        }
    }

    @Test
    void bothArtifactsAreUsAsciiEncodable() {
        // Not a style rule. RiskExtractMain writes every artifact with US_ASCII, so ONE non-ASCII
        // character — an em-dash in a preamble sentence, say — throws UnmappableCharacterException
        // and aborts the whole EOD batch, after the cut has already been rendered and hashed. The
        // other tests here render to a String and never encode it, so none of them can see it.
        final MatchingEngineClusteredService service = enabledAccounts();
        apply(service, swap(BUYER, InputEvent.SWAP_RECEIVE_FIXED, RECEIVE_RATE_TICKS, 1L));
        apply(service, swaption(SELLER, InputEvent.SWAP_PAY_FIXED, PAY_RATE_TICKS, BERMUDAN, 2L));
        final String cut = markAndCapture(service);
        final RiskExtractCsv.Stamp stamp = stamp(cut);

        for (final String artifact : new String[] {
                cut,
                SwapContractCsv.render(cut, counterparties(), stamp),
                RiskExtractCsv.render(cut, Map.of(), counterparties(), Map.of(), stamp)}) {
            for (int i = 0; i < artifact.length(); i++) {
                final char c = artifact.charAt(i);
                assertTrue(c < 128, "non-ASCII U+" + Integer.toHexString(c) + " at " + i
                    + ", in: " + artifact.substring(Math.max(0, i - 60), Math.min(artifact.length(), i + 20)));
            }
        }
    }

    // ----- harness -------------------------------------------------------------------------------

    private MatchingEngineClusteredService enabledAccounts() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        apply(service, accountControl(SELLER, true));
        apply(service, accountControl(BUYER, true));
        return service;
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

    private InputEvent accountControl(final int accountId, final boolean enabled) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        e.accountId = accountId;
        e.setControlEnabled(enabled);
        e.setControlVersion(++timestamp);
        e.eventTimeMillis = timestamp;
        return e;
    }

    /**
     * The reason the gate gives for booking on {@code accountId}. The service answers on egress,
     * which needs a ClientSession this harness has none of, so ask the gate the same question
     * directly — key-less, so it cannot be answered out of the idempotency table by the booking it
     * is being asked about.
     */
    private RiskReason decisionFor(final MatchingEngineClusteredService service, final int accountId) {
        return service.risk().decideSwapBooking(0L, 0L, accountId, 1L, 1);
    }

    private String markAndCapture(final MatchingEngineClusteredService service) {
        codec.encodeRiskExtract(markerBuffer, 0, ++timestamp, SESSION.toEpochDay(), PRICE_VERSION);
        service.onSessionMessage(null, ++timestamp, markerBuffer, 0,
            AeronReplicationCodec.RISK_EXTRACT_BYTES, null);
        final String cut = RiskExtractCut.render(service.lastExtractCutSeq(), SESSION.toEpochDay(),
            PRICE_VERSION, service.engine().positionTuples(), service.engine().priceTuples(),
            new String[MatchingEngineClusteredService.MAX_SECURITIES],
            id -> service.risk().contractMultiplier(id), service.contractTuples());
        assertEquals(service.lastExtractCutSha(), RiskExtractCut.sha256(cut),
            "the service's own recorded hash must match the cut being asserted on");
        return cut;
    }

    private RiskExtractCsv.Stamp stamp(final String cut) {
        final String head = cut.substring(0, cut.indexOf('\n'));
        final int from = head.indexOf("seq=") + 4;
        final long seq = Long.parseLong(head.substring(from, head.indexOf(' ', from)));
        return new RiskExtractCsv.Stamp(seq, SESSION, PRICE_VERSION, RiskExtractCut.sha256(cut));
    }

    private Map<Integer, RiskExtractCsv.Counterparty> counterparties() {
        final Map<Integer, RiskExtractCsv.Counterparty> accounts = new HashMap<>();
        accounts.put(SELLER, new RiskExtractCsv.Counterparty("CPTY-DELTA-PRIME", "NS-DELT-ISDA-01", "USD"));
        accounts.put(BUYER, new RiskExtractCsv.Counterparty("CPTY-CASCADE-AM", "NS-CASC-ISDA-01", "USD"));
        return accounts;
    }

    private static List<String> dataRows(final String artifact) {
        return artifact.lines()
            .filter(l -> !l.startsWith("#") && !l.isEmpty())
            .filter(l -> !l.equals(SwapContractCsv.HEADER) && !l.equals(RiskExtractCsv.HEADER))
            .toList();
    }

    private List<byte[]> snapshotRecords(final MatchingEngineClusteredService source) {
        final List<byte[]> records = new ArrayList<>();
        source.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            records.add(copy);
        });
        return records;
    }

    private MatchingEngineClusteredService restore(final MatchingEngineClusteredService source) {
        final MatchingEngineClusteredService restored = new MatchingEngineClusteredService();
        restored.initEngine();
        boolean done = false;
        for (final byte[] record : snapshotRecords(source)) {
            done = restored.onSnapshotRecord(new UnsafeBuffer(record), 0);
        }
        assertTrue(done, "snapshot record stream must terminate with END");
        return restored;
    }

    private void apply(final MatchingEngineClusteredService service, final InputEvent event) {
        codec.encodeInput(ingressBuffer, 0, event, 0, 0, 0);
        service.onSessionMessage(null, ++timestamp, ingressBuffer, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }

    private static void assertArrayEqualsLong(final long[] expected, final long[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "column " + i);
        }
    }
}
