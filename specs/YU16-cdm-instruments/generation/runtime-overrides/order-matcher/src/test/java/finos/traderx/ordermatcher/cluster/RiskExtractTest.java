package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YU15 acceptance proofs for the EOD risk extract, driven through the real sequenced ingress path
 * and requiring no cluster.
 *
 * <p>The two that matter most to the consumer:
 * <ul>
 *   <li><b>the cut is a consistent, reproducible point-in-time</b> — a member restored from a
 *       snapshot renders byte-identical cut text at the same sequence as the member that never
 *       restarted (T-RXT01/02), which is what makes "the extract for sequence N" a stable name;</li>
 *   <li><b>the fixture is byte-identical</b> — rendering twice, and rendering from the replica's
 *       cut, produces the same bytes (T-RXT03), so CPU/GPU/TPU runs score the identical portfolio.
 * </ul>
 */
class RiskExtractTest {
    private static final long PX = 1_000_000L;
    private static final int SELLER = 42422;   // both are real accounts in counterparties.csv
    private static final int BUYER = 22214;
    private static final LocalDate SESSION = LocalDate.of(2026, 7, 21);
    private static final int PRICE_VERSION = 3;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer markerBuffer =
        new UnsafeBuffer(new byte[AeronReplicationCodec.RISK_EXTRACT_BYTES]);
    private long timestamp = 1_000_000_000_000L;

    // ----- the cut is a consistent, reproducible cut ------------------------------------------

    @Test
    void cutIsIdenticalOnAMemberRestoredFromSnapshot() {
        final MatchingEngineClusteredService source = portfolio();
        final MatchingEngineClusteredService replica = restore(source);

        final String live = markAndCapture(source);
        final String recovered = markAndCapture(replica);

        assertEquals(live, recovered,
            "a restored member must render the identical cut at the same sequence");
        assertEquals(source.lastExtractCutSeq(), replica.lastExtractCutSeq(),
            "the marker lands at the same consensus sequence on both");
        assertEquals(source.lastExtractCutSha(), replica.lastExtractCutSha());
    }

    @Test
    void cutNamesTheSequenceTheMarkerLandedAt() {
        final MatchingEngineClusteredService service = portfolio();
        final String cut = markAndCapture(service);
        assertTrue(cut.startsWith("#cut schema=" + RiskExtractCut.SCHEMA + " seq="
            + service.lastExtractCutSeq() + " "), cut.lines().findFirst().orElse(""));
        assertTrue(cut.contains(" sessionDateEpochDay=" + SESSION.toEpochDay() + " "));
        assertTrue(cut.contains(" priceVersion=" + PRICE_VERSION + " "));
    }

    @Test
    void markerAdvancesTheSequenceByExactlyOneAndMutatesNothing() {
        final MatchingEngineClusteredService service = portfolio();
        final long tradesBefore = service.engine().tradeCounter();
        final long orderHashBefore = service.engine().recoveryDigest().orderHash();

        markAndCapture(service);
        final long first = service.lastExtractCutSeq();
        markAndCapture(service);

        assertEquals(first + 1, service.lastExtractCutSeq(),
            "back-to-back markers on a quiescent cluster differ by exactly one — this is the"
                + " witness the producer uses to prove nothing traded mid-build");
        assertEquals(tradesBefore, service.engine().tradeCounter(), "the marker books nothing");
        assertEquals(orderHashBefore, service.engine().recoveryDigest().orderHash(),
            "the marker mutates no engine state");
    }

    @Test
    void cutRowsAreSortedAndCarryTheOptionMultiplier() {
        final String cut = markAndCapture(portfolio());
        final List<String> rows = cut.lines().filter(l -> !l.startsWith("#"))
            .filter(l -> !l.equals(RiskExtractCut.HEADER)).toList();
        assertEquals(4, rows.size(), "two accounts x two securities");

        final List<String> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> {
            final String[] x = a.split(",");
            final String[] y = b.split(",");
            final int byAccount = Long.compare(Long.parseLong(x[0]), Long.parseLong(y[0]));
            return byAccount != 0 ? byAccount : x[1].compareTo(y[1]);
        });
        assertEquals(sorted, rows, "rows are emitted in (accountId, security) order, never map order");

        assertTrue(rows.stream().anyMatch(r -> r.contains(",AAPL260918C00240000,") && r.endsWith(",3800000")),
            "the option row carries its last trade price in ticks");
        assertTrue(rows.stream().filter(r -> r.contains(",AAPL260918C00240000,"))
            .allMatch(r -> r.split(",")[4].equals("100")), "options carry multiplier 100");
        assertTrue(rows.stream().filter(r -> r.contains(",AAPL,"))
            .allMatch(r -> r.split(",")[4].equals("1")), "equities carry multiplier 1");
    }

    @Test
    void aRestoredMemberReportsItsConsensusPositionWhileIdle() {
        // Readiness compares this number against peers'. The engine's blpSeq is -1 on a member
        // that restored from a snapshot and has applied no events since — which is the normal
        // state of every member during an EOD window — so readiness must not be derived from it.
        final MatchingEngineClusteredService source = portfolio();
        final long sourceSeq = source.appliedSeq();
        assertTrue(sourceSeq > 0);

        final MatchingEngineClusteredService replica = restore(source);
        assertEquals(-1, replica.engine().blpSeq(),
            "sanity: a freshly restored engine has applied no events of its own");
        assertEquals(sourceSeq, replica.appliedSeq(),
            "a restored member knows the consensus sequence its state corresponds to");
    }

    @Test
    void aPositionOnAnUnregisteredSecurityFailsClosed() {
        // A risk extract that silently omits a position is worse than no extract.
        assertThrows(IllegalStateException.class, () -> RiskExtractCut.render(
            10, SESSION.toEpochDay(), PRICE_VERSION,
            List.of(new long[] { SELLER, 7, 100, PX }), List.of(), new String[8], id -> 1L));
    }

    // ----- the fixture is byte-identical -------------------------------------------------------

    @Test
    void fixtureIsByteIdenticalAcrossRebuildsAndAcrossMembers() {
        final MatchingEngineClusteredService source = portfolio();
        final MatchingEngineClusteredService replica = restore(source);
        final String liveCut = markAndCapture(source);
        final String replicaCut = markAndCapture(replica);

        final String first = render(liveCut);
        final String second = render(liveCut);
        final String fromReplica = render(replicaCut);

        assertEquals(first, second, "rendering the same cut twice must produce the same bytes");
        assertEquals(first, fromReplica,
            "the fixture rebuilt from a recovered member's cut is byte-identical");
        assertEquals(RiskExtractCut.sha256(first), RiskExtractCut.sha256(fromReplica));
    }

    @Test
    void fixtureCarriesItsStampAndConventions() {
        final MatchingEngineClusteredService service = portfolio();
        final String cut = markAndCapture(service);
        final String csv = render(cut);

        assertTrue(csv.contains("# consensusSequence=" + service.lastExtractCutSeq() + "\n"));
        assertTrue(csv.contains("# sessionDate=" + SESSION + "\n"));
        assertTrue(csv.contains("# priceSnapshotVersion=" + PRICE_VERSION + "\n"));
        assertTrue(csv.contains("# cutSha256=" + service.lastExtractCutSha() + "\n"));
        assertTrue(csv.contains("# marketValueConvention=quantity * closingMark * contractMultiplier\n"),
            "the P&L convention travels with the fixture so a tie-out hunt has a starting point");
        assertTrue(csv.contains("# netting=none;"));
        // No wall clock anywhere in the body — that is what makes it reproducible.
        assertTrue(csv.lines().noneMatch(l -> l.contains("generatedAt") || l.contains("Millis")));
    }

    @Test
    void equityUsesThePublishedCloseAndOptionsFallBackToTheClusterLastTrade() {
        final String csv = render(markAndCapture(portfolio()));
        final Map<String, String[]> bySecurity = new HashMap<>();
        csv.lines().filter(l -> !l.startsWith("#") && !l.startsWith("accountId,"))
            .forEach(l -> bySecurity.put(l.split(",")[1], l.split(",")));

        final String[] equity = bySecurity.get("AAPL");
        assertEquals("EQUITY", equity[2]);
        assertEquals("EOD_SNAPSHOT", equity[7], "an equity takes the YU06 published close");
        assertEquals("241.500000", equity[6]);

        final String[] option = bySecurity.get("AAPL260918C00240000");
        assertEquals("OPTION", option[2]);
        assertEquals("CLUSTER_LAST_TRADE_AT_N", option[7],
            "a listed option has no published close, so the engine's last trade at N is the mark");
        assertEquals("3.800000", option[6]);
    }

    @Test
    void marketValueAndPnlAreMultiplierAware() {
        final String csv = render(markAndCapture(portfolio()));
        final String[] option = csv.lines()
            .filter(l -> l.startsWith(BUYER + ",AAPL260918C00240000,")).findFirst().orElseThrow()
            .split(",");
        // Bought 5 contracts at $3.80, marked at $3.80, multiplier 100.
        assertEquals("5", option[3]);
        assertEquals("100", option[4]);
        assertEquals("1900.000000", option[9], "5 x 3.80 x 100 — notional is multiplied");
        assertEquals("0.000000", option[10]);

        final String[] equity = csv.lines().filter(l -> l.startsWith(BUYER + ",AAPL,"))
            .findFirst().orElseThrow().split(",");
        // Bought 10 shares at $240.00, marked at the published close of $241.50, multiplier 1.
        assertEquals("2415.000000", equity[9]);
        assertEquals("15.000000", equity[10], "(241.50 - 240.00) x 10 x 1");
    }

    @Test
    void counterpartyAndNettingSetRideAlongUnNetted() {
        final String csv = render(markAndCapture(portfolio()));
        final List<String> sellerRows = csv.lines().filter(l -> l.startsWith(SELLER + ",")).toList();
        assertEquals(2, sellerRows.size(), "rows stay at (account, security) grain — never netted");
        assertTrue(sellerRows.stream().allMatch(r -> r.endsWith("USD,CPTY-DELTA-PRIME,NS-DELT-ISDA-01,,")),
            "non-treasury rows carry empty coupon/maturity columns (schema 2)");
        assertTrue(csv.lines().filter(l -> l.startsWith(BUYER + ","))
            .allMatch(r -> r.endsWith("USD,CPTY-CASCADE-AM,NS-CASC-ISDA-01,,")));
    }

    // ----- fail closed -------------------------------------------------------------------------

    @Test
    void anUnmarkableRowRefusesToEmit() {
        // No published close and no trade at N: there is no defensible mark, so no fixture.
        final String cut = "#cut schema=1 seq=9 sessionDateEpochDay=" + SESSION.toEpochDay()
            + " priceVersion=1 rows=1\n" + RiskExtractCut.HEADER + "\n"
            + SELLER + ",AAPL,100,240000000,1,0\n";
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> RiskExtractCsv.render(cut, Map.of(), counterparties(), Map.of(), stamp(cut, 9)));
        assertTrue(e.getMessage().contains("no mark for AAPL"), e.getMessage());
    }

    @Test
    void anUnmappedAccountRefusesToEmit() {
        final String cut = "#cut schema=1 seq=9 sessionDateEpochDay=" + SESSION.toEpochDay()
            + " priceVersion=1 rows=1\n" + RiskExtractCut.HEADER + "\n"
            + "99999,AAPL,100,240000000,1,241500000\n";
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> RiskExtractCsv.render(cut, Map.of(), counterparties(), Map.of(), stamp(cut, 9)));
        assertTrue(e.getMessage().contains("no counterparty mapping"), e.getMessage());
    }

    @Test
    void aTruncatedCutRefusesToEmit() {
        // The cut declares its own row count precisely so a short message cannot pass as complete.
        final String cut = "#cut schema=1 seq=9 sessionDateEpochDay=" + SESSION.toEpochDay()
            + " priceVersion=1 rows=2\n" + RiskExtractCut.HEADER + "\n"
            + SELLER + ",AAPL,100,240000000,1,241500000\n";
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> RiskExtractCsv.render(cut, Map.of(), counterparties(), Map.of(), stamp(cut, 9)));
        assertTrue(e.getMessage().contains("declared 2 rows but carried 1"), e.getMessage());
    }

    @Test
    void aCutFromADifferentSequenceRefusesToEmit() {
        final String cut = "#cut schema=1 seq=9 sessionDateEpochDay=" + SESSION.toEpochDay()
            + " priceVersion=1 rows=1\n" + RiskExtractCut.HEADER + "\n"
            + SELLER + ",AAPL,100,240000000,1,241500000\n";
        assertThrows(IllegalStateException.class,
            () -> RiskExtractCsv.render(cut, Map.of(), counterparties(), Map.of(), stamp(cut, 10)));
    }

    @Test
    void differentSequencesProduceDifferentFixtures() {
        final MatchingEngineClusteredService service = portfolio();
        final String one = render(markAndCapture(service));
        final String two = render(markAndCapture(service));
        assertNotEquals(one, two, "the sequence is part of the fixture's identity");
    }

    // ----- YU16: the bond position, end to end through the unchanged engine --------------------

    @Test
    void aTreasuryPositionIsClassifiedByJoinAndValuedAsFaceTimesFraction() {
        // 100,000 USD face of UST-20280630 crossed at 99.878% of par, stored as the fraction
        // 0.998780 = 998,780 ticks (ADR-057). The engine is byte-identical to YU15's: the bond
        // is an ordinary security whose arithmetic is equity arithmetic at multiplier 1.
        final MatchingEngineClusteredService service = portfolio();
        final int bond = registerSymbol(service, "UST-20280630");
        apply(service, securityControl(bond, true));
        apply(service, priceTick(bond, 998_780L));
        apply(service, order(bond, SELLER, InputEvent.SIDE_SELL, 998_780L, 100_000));
        apply(service, order(bond, BUYER, InputEvent.SIDE_BUY, 998_780L, 100_000));

        // A member restored from snapshot must re-derive the bond book grid (T_SYMBOL precedes
        // T_BOOK) and render the identical cut - the exact path where a missed derivation would
        // diverge a member permanently.
        final MatchingEngineClusteredService restored = restore(service);
        final String cut = markAndCaptureWith(service, "AAPL", "AAPL260918C00240000", "UST-20280630");
        final String restoredCut = markAndCaptureWith(restored, "AAPL", "AAPL260918C00240000", "UST-20280630");
        assertEquals(cut, restoredCut,
            "a restored member renders the identical cut for a portfolio holding a bond");
        final String csv = RiskExtractCsv.render(cut, marks(), counterparties(), bonds(),
            stamp(cut, seqOf(cut)));

        assertTrue(csv.startsWith("# traderx-risk-extract schema=2\n"),
            "schema 2 announces the widened column set");
        final String[] row = csv.lines()
            .filter(l -> l.startsWith(BUYER + ",UST-20280630,")).findFirst().orElseThrow()
            .split(",", -1);
        assertEquals("TREASURY", row[2], "classified by the instruments.csv join, not by prefix");
        assertEquals("100000", row[3], "quantity is USD face");
        assertEquals("1", row[4], "the contract multiplier for a bond is 1");
        assertEquals("0.998780", row[5], "cost basis is the fraction of par at six decimals");
        assertEquals("0.998780", row[6], "the mark is the cluster last trade at N, same fraction");
        assertEquals("99878.000000", row[9], "marketValue = face x fraction x 1 = $99,878");
        assertEquals("0.000000", row[10]);
        assertEquals("4.125", row[14], "coupon joined from the static");
        assertEquals("2028-06-30", row[15], "maturity joined from the static");
    }

    // ----- helpers ------------------------------------------------------------------------------

    /**
     * A two-account, two-security portfolio built entirely through committed ingress: 10 AAPL
     * shares crossed at $240.00 and 5 AAPL Sep-26 240-strike calls crossed at $3.80.
     */
    private MatchingEngineClusteredService portfolio() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        apply(service, accountControl(SELLER, true));
        apply(service, accountControl(BUYER, true));

        final int equity = registerSymbol(service, "AAPL");
        apply(service, securityControl(equity, true));
        apply(service, priceTick(equity, 240 * PX));
        apply(service, order(equity, SELLER, InputEvent.SIDE_SELL, 240 * PX, 10));
        apply(service, order(equity, BUYER, InputEvent.SIDE_BUY, 240 * PX, 10));

        final int option = registerSymbol(service, "AAPL260918C00240000");
        apply(service, securityControl(option, true));
        apply(service, priceTick(option, 3_800_000L));
        apply(service, order(option, SELLER, InputEvent.SIDE_SELL, 3_800_000L, 5));
        apply(service, order(option, BUYER, InputEvent.SIDE_BUY, 3_800_000L, 5));
        assertTrue(service.engine().tradeCounter() > 0, "sanity: both crosses booked");
        return service;
    }

    /** Apply a marker and return the cut text the service rendered for it. */
    private String markAndCapture(final MatchingEngineClusteredService service) {
        return markAndCaptureWith(service, "AAPL", "AAPL260918C00240000");
    }

    private String markAndCaptureWith(final MatchingEngineClusteredService service,
                                      final String... tickers) {
        codec.encodeRiskExtract(markerBuffer, 0, ++timestamp, SESSION.toEpochDay(), PRICE_VERSION);
        service.onSessionMessage(null, ++timestamp, markerBuffer, 0,
            AeronReplicationCodec.RISK_EXTRACT_BYTES, null);
        // Re-render from the same replicated state the service just rendered from; the sha the
        // service recorded proves the two agree.
        final String cut = RiskExtractCut.render(service.lastExtractCutSeq(), SESSION.toEpochDay(),
            PRICE_VERSION, service.engine().positionTuples(), service.engine().priceTuples(),
            tickerById(service, tickers), id -> service.risk().contractMultiplier(id));
        assertEquals(service.lastExtractCutSha(), RiskExtractCut.sha256(cut),
            "the service's own recorded hash must match the cut being asserted on");
        return cut;
    }

    private String[] tickerById(final MatchingEngineClusteredService service, final String... named) {
        final String[] tickers = new String[MatchingEngineClusteredService.MAX_SECURITIES];
        for (final String ticker : named) {
            final int id = service.symbolIdFor(ticker);
            if (id >= 0) {
                tickers[id] = ticker;
            }
        }
        return tickers;
    }

    private String render(final String cut) {
        return RiskExtractCsv.render(cut, marks(), counterparties(), bonds(), stamp(cut, seqOf(cut)));
    }

    /** YU16 (ADR-059): the extract join's bond static, as loaded from instruments.csv. */
    private Map<String, RiskExtractCsv.BondStatic> bonds() {
        return Map.of("UST-20280630", new RiskExtractCsv.BondStatic("4.125", "2028-06-30"));
    }

    /** The YU06 published close for the equity only — options are absent from that chain. */
    private Map<String, RiskExtractCsv.Mark> marks() {
        final Map<String, RiskExtractCsv.Mark> marks = new HashMap<>();
        marks.put("AAPL", new RiskExtractCsv.Mark(new BigDecimal("241.500000"), "OK"));
        return marks;
    }

    private Map<Integer, RiskExtractCsv.Counterparty> counterparties() {
        final Map<Integer, RiskExtractCsv.Counterparty> accounts = new HashMap<>();
        accounts.put(SELLER, new RiskExtractCsv.Counterparty("CPTY-DELTA-PRIME", "NS-DELT-ISDA-01", "USD"));
        accounts.put(BUYER, new RiskExtractCsv.Counterparty("CPTY-CASCADE-AM", "NS-CASC-ISDA-01", "USD"));
        return accounts;
    }

    private RiskExtractCsv.Stamp stamp(final String cut, final long seq) {
        return new RiskExtractCsv.Stamp(seq, SESSION, PRICE_VERSION, RiskExtractCut.sha256(cut));
    }

    private long seqOf(final String cut) {
        final String head = cut.substring(0, cut.indexOf('\n'));
        final int from = head.indexOf("seq=") + 4;
        return Long.parseLong(head.substring(from, head.indexOf(' ', from)));
    }

    private MatchingEngineClusteredService restore(final MatchingEngineClusteredService source) {
        final List<byte[]> records = new ArrayList<>();
        source.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            records.add(copy);
        });
        final MatchingEngineClusteredService restored = new MatchingEngineClusteredService();
        restored.initEngine();
        boolean done = false;
        for (final byte[] record : records) {
            done = restored.onSnapshotRecord(new UnsafeBuffer(record), 0);
        }
        assertTrue(done, "snapshot record stream must terminate with END");
        return restored;
    }

    private int registerSymbol(final MatchingEngineClusteredService service, final String ticker) {
        codec.encodeSymbolRegister(ingressBuffer, 0, ++timestamp, ticker);
        service.onSessionMessage(null, timestamp, ingressBuffer, 0,
            AeronReplicationCodec.SYMBOL_BYTES, null);
        final int id = service.symbolIdFor(ticker);
        assertTrue(id >= 0, "registration must assign an id for " + ticker);
        return id;
    }

    private void apply(final MatchingEngineClusteredService service, final InputEvent event) {
        codec.encodeInput(ingressBuffer, 0, event, 0, 0, 0);
        service.onSessionMessage(null, ++timestamp, ingressBuffer, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }

    private InputEvent order(final int securityId, final int accountId, final byte side,
                             final long limitPx, final int qty) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.accountId = accountId;
        e.securityId = securityId;
        e.side = side;
        e.qty = qty;
        e.limitPx = limitPx;
        e.eventTimeMillis = timestamp;
        return e;
    }

    private InputEvent priceTick(final int securityId, final long px) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK;
        e.securityId = securityId;
        e.priceTicks = px;
        e.eventTimeMillis = timestamp;
        return e;
    }

    private InputEvent accountControl(final int accountId, final boolean enabled) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        e.accountId = accountId;
        e.setControlEnabled(enabled);
        e.setControlVersion(1);
        e.eventTimeMillis = timestamp;
        return e;
    }

    private InputEvent securityControl(final int securityId, final boolean enabled) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SECURITY_CONTROL;
        e.securityId = securityId;
        e.setControlEnabled(enabled);
        e.setControlVersion(1);
        e.eventTimeMillis = timestamp;
        return e;
    }
}
