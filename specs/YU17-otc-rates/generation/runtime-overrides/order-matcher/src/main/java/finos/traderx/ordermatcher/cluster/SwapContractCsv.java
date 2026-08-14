package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.SwapConventions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The per-trade OTC contract artifact (YU17, ADR-064) — the second of the two files rendered from
 * one cut at one consensus sequence (D3).
 *
 * <p><b>Why this is not rows in the netted extract.</b> Our position grain is
 * {@code (accountId, security) -> (signed quantity, averageCostBasis)}. For anything fungible that
 * is exact. For a swap it is lossy in a way that has no error and no log line: receive fixed 4.2%
 * on 10mm and then pay fixed 4.3% on 10mm net to quantity ZERO at that grain, average rate
 * meaningless, position gone — while the account is in fact locked into paying ~10bp on 10mm for
 * five years. For a fungible instrument the price is what you paid; for a swap <em>the rate is
 * what the contract is</em>. It breaks in the same-direction case too: receive-fixed 4.2% 5Y and
 * receive-fixed 4.2% 3Y share rate and direction and still cannot be averaged.
 *
 * <p>So swaps are carried one row per contract and the netted extract keeps its schema and its
 * meaning for the instruments netting IS correct for. A single polymorphic file was the other
 * option and is worse: it forces every consumer to branch on instrument type before reading any
 * column, and the "non-bond rows carry empty bond columns" convention does not stretch to a row
 * that shares almost nothing with a position.
 *
 * <p><b>Terms, not values (D5).</b> No NPV, no discount factor, no curve, no par rate, no Greeks.
 * We are authoritative for what was booked; the consumer's engine is authoritative for what it is
 * worth. A number here that looked like a valuation would be duplicating their half of the
 * boundary, and doing it worse.
 *
 * <p>Like {@link RiskExtractCsv} this is a pure function of the cut plus immutable reference data
 * — no clocks, no map iteration order, no floating point — so it rebuilds byte-identically from
 * the stored cut alone, forever.
 */
final class SwapContractCsv {

    /** Bumped only on an incompatible column change. */
    static final int SCHEMA = 1;

    static final String HEADER = "contractId,accountId,payReceive,notional,fixedRate,floatIndex,"
        + "effectiveDate,maturityDate,paymentFrequency,dayCount,currency,counterpartyId,nettingSetId";

    private static final int RATE_SCALE = 6;

    private SwapContractCsv() {
    }

    /**
     * Render the contracts section of {@code cut}.
     *
     * @throws IllegalStateException if the cut is malformed or truncated, if it carries no
     *     contracts section at all (which would mean it was rendered by a build that predates this
     *     one, not that the portfolio holds no swaps), if a convention index is unknown to this
     *     build, or if a booking account has no counterparty mapping. A contracts file that
     *     silently drops or zero-fills a trade is worse than no file.
     */
    static String render(final String cut, final Map<Integer, RiskExtractCsv.Counterparty> accounts,
                         final RiskExtractCsv.Stamp stamp) {
        final String[] lines = cut.split("\n", -1);
        if (lines.length < 2 || !lines[0].startsWith("#cut ")) {
            throw new IllegalStateException("swap contracts: cut is not in the expected format");
        }
        final long cutSeq = Long.parseLong(field(lines[0], "seq="));
        if (cutSeq != stamp.consensusSequence()) {
            throw new IllegalStateException("swap contracts: cut sequence " + cutSeq
                + " does not match the stamped sequence " + stamp.consensusSequence());
        }
        final int declared = Integer.parseInt(field(lines[0], "contracts="));

        int at = -1;
        for (int i = 2; i < lines.length; i++) {
            if (RiskExtractCut.CONTRACTS_MARKER.equals(lines[i])) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            throw new IllegalStateException("swap contracts: cut carries no '"
                + RiskExtractCut.CONTRACTS_MARKER + "' section — it was rendered by a build older"
                + " than this one. An absent section is NOT an empty portfolio.");
        }
        if (at + 1 >= lines.length || !RiskExtractCut.CONTRACTS_HEADER.equals(lines[at + 1])) {
            throw new IllegalStateException("swap contracts: section header missing or changed");
        }

        final List<String> body = new ArrayList<>(declared);
        for (int i = at + 2; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            body.add(row(lines[i], accounts));
        }
        if (body.size() != declared) {
            // The cut travels as one message precisely so truncation is detectable here.
            throw new IllegalStateException("swap contracts: cut declared " + declared
                + " contracts but carried " + body.size());
        }

        final StringBuilder sb = new StringBuilder(2048 + body.size() * 160);
        preamble(sb, stamp, declared);
        sb.append(HEADER).append('\n');
        for (final String line : body) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Everything here is derived from the stamp, so the file is byte-reproducible. The convention
     * lines say plainly what this file does NOT contain, because the expensive misunderstanding is
     * a consumer assuming a missing valuation column means zero exposure.
     */
    private static void preamble(final StringBuilder sb, final RiskExtractCsv.Stamp stamp,
                                 final int contracts) {
        sb.append("# traderx-swap-contracts schema=").append(SCHEMA).append('\n');
        sb.append("# consensusSequence=").append(stamp.consensusSequence()).append('\n');
        sb.append("# sessionDate=").append(stamp.sessionDate()).append('\n');
        sb.append("# priceSnapshotVersion=").append(stamp.priceVersion()).append('\n');
        sb.append("# cutSha256=").append(stamp.cutSha256()).append('\n');
        sb.append("# contracts=").append(contracts).append('\n');
        sb.append("# cutConsistency=every row is the replicated state machine's state at"
            + " consensusSequence on the totally-ordered consensus log, not a read-model query\n");
        sb.append("# companionArtifact=the netted position extract rendered from this same cut,"
            + " at this same consensusSequence and cutSha256, carries equities, treasuries and"
            + " listed options; no row appears in both files\n");
        sb.append("# netting=NONE, and none is possible. Rows are one per booked contract. Two"
            + " offsetting swaps do NOT cancel: receive-fixed 4.2% and pay-fixed 4.3% on the same"
            + " notional and dates net to zero quantity at an (account, instrument) grain while"
            + " leaving a real locked-in rate differential. Netting these rows destroys that\n");
        sb.append("# valuation=ABSENT BY DESIGN. This file states TERMS, not values: no NPV, no"
            + " mark, no discount factors, no curve, no par rate, no sensitivities. An absent"
            + " valuation column does not mean zero exposure\n");
        sb.append("# payReceive=direction of the FIXED leg from the booking account's point of"
            + " view; the float leg is the opposite side of the same contract\n");
        sb.append("# notionalConvention=whole currency units of the currency column, not scaled\n");
        sb.append("# fixedRateConvention=annual decimal fraction, six decimals (0.042000 = 4.2%)\n");
        sb.append("# lifecycle=NOT MODELLED. Terms are as booked: no resets, no coupon payments,"
            + " no accrual, no amortisation, no unwinds or terminations. A contract past its"
            + " maturityDate is still listed here exactly as booked\n");
        sb.append("# contractIdentity=SW-<consensusSequence of the booking>, unique within the"
            + " cluster epoch by construction and reproducible from the log alone\n");
        sb.append("# conventionSource=floatIndex, paymentFrequency and dayCount are resolved from"
            + " the committed convention index by a table compiled into the engine, so every"
            + " member and every replay resolves them identically\n");
    }

    private static String row(final String cutLine,
                              final Map<Integer, RiskExtractCsv.Counterparty> accounts) {
        final String[] c = cutLine.split(",", -1);
        if (c.length != 8) {
            throw new IllegalStateException("swap contracts: malformed cut row: " + cutLine);
        }
        final long contractId = Long.parseLong(c[0]);
        final int accountId = Integer.parseInt(c[1]);
        final boolean paysFixed = Long.parseLong(c[2]) != 0L;
        final long notional = Long.parseLong(c[3]);
        final BigDecimal fixedRate = BigDecimal.valueOf(Long.parseLong(c[4]), RATE_SCALE);
        final SwapConventions.Convention convention = SwapConventions.at(Integer.parseInt(c[5]));
        final LocalDate effective = LocalDate.ofEpochDay(Long.parseLong(c[6]));
        final LocalDate maturity = LocalDate.ofEpochDay(Long.parseLong(c[7]));

        final RiskExtractCsv.Counterparty cp = accounts.get(accountId);
        if (cp == null) {
            throw new IllegalStateException("swap contracts: account " + accountId
                + " has no counterparty mapping in reference data");
        }
        // The currency column is the CONTRACT's, from its conventions — not the account's base
        // currency from reference data. A USD-based account trading a GBP swap is ordinary, so
        // those two disagreeing is not an error; taking the account's would misstate the trade.
        return "SW-" + contractId + "," + accountId + ","
            + (paysFixed ? "PAY_FIXED" : "RECEIVE_FIXED") + ","
            + notional + "," + fixedRate.toPlainString() + ","
            + convention.floatIndex() + ","
            + effective + "," + maturity + ","
            + convention.paymentFrequency() + "," + convention.dayCount() + ","
            + convention.currency() + ","
            + cp.counterpartyId() + "," + cp.nettingSetId();
    }

    private static String field(final String header, final String key) {
        final int at = header.indexOf(key);
        if (at < 0) {
            throw new IllegalStateException("swap contracts: cut header missing " + key);
        }
        final int from = at + key.length();
        int to = header.indexOf(' ', from);
        if (to < 0) {
            to = header.length();
        }
        return header.substring(from, to);
    }
}
