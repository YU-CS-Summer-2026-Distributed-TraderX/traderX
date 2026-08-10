package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OccSymbol;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The EOD risk extract itself (YU15) — a pure function from a rendered cut plus immutable
 * reference data to the exact bytes the risk engine consumes. No clocks, no maps in iteration
 * order, no locale-sensitive formatting, no floating point: run it twice on the same inputs and
 * it produces identical bytes, forever (FR-RXT10).
 *
 * <p>Row grain is {@code (accountId, security)} and rows are never netted — netting and CSA
 * treatment belong to the consumer's engine, which is why {@code counterpartyId} and
 * {@code nettingSetId} ride along as attributes rather than as an aggregation the extract has
 * already applied.
 *
 * <p>All decimal arithmetic is {@link BigDecimal} over integer ticks
 * ({@link finos.traderx.ordermatcher.lmax.Px#SCALE}), so values are exact and cannot overflow the
 * way {@code quantity * priceTicks * multiplier} would in {@code long}.
 */
final class RiskExtractCsv {

    /** Bumped only on an incompatible column change. Schema 2 (YU16, ADR-059): instrumentType
     * gains TREASURY and two columns append — coupon and maturityDate, populated for Treasury
     * rows by join against the instrument static. Every schema-1 column keeps its name,
     * position and meaning. */
    static final int SCHEMA = 2;

    static final String HEADER = "accountId,security,instrumentType,quantity,contractMultiplier,"
        + "costBasis,closingMark,markSource,markQuality,marketValue,unrealizedPnl,currency,"
        + "counterpartyId,nettingSetId,coupon,maturityDate";

    private static final int TICK_SCALE = 6;

    /** One instrument's official close, as published by the YU06 EOD chain. */
    record Mark(BigDecimal price, String quality) { }

    /** Reference-data attributes of an account (specs/.../reference-data/counterparties.csv). */
    record Counterparty(String counterpartyId, String nettingSetId, String currency) { }

    /**
     * Bond static from the instrument reference data (instruments.csv). Classification is by
     * THIS join, never by prefix-parsing inside the render (ADR-059): a security present here is
     * a Treasury, and its coupon/maturity ride onto the row. Prices in a Treasury row are
     * fractions of par (ADR-057) — the same integer ticks as every other instrument.
     */
    record BondStatic(String couponRatePercent, String maturityDate) { }

    /**
     * The immutable name of one extract. Every field is derivable from the cut itself, which is
     * what makes {@link #render} a pure function of the cut plus immutable reference data — and
     * therefore rebuildable from the stored cut alone. Operational evidence about the build (the
     * quiescence witness sequence, the sink URI, wall-clock times) deliberately lives in the
     * delivery announcement, never in the fixture.
     */
    record Stamp(long consensusSequence, LocalDate sessionDate, int priceVersion, String cutSha256) { }

    private RiskExtractCsv() {
    }

    /**
     * Join the cut with the published marks and the counterparty reference data.
     *
     * @param cut       the exact text {@link RiskExtractCut#render} produced at the stamp's sequence
     * @param marks     official closes by security; a security absent here falls back to the cut's
     *                  own last-trade price at N (ADR-056), which is how listed options are marked
     * @param accounts  counterparty/netting attributes by account id
     * @throws IllegalStateException if the cut is malformed or truncated, if a row has no mark from
     *     either source, or if a holding account has no counterparty mapping — a risk extract that
     *     silently drops or zero-fills a position is worse than no extract at all
     */
    static String render(final String cut, final Map<String, Mark> marks,
                         final Map<Integer, Counterparty> accounts,
                         final Map<String, BondStatic> bonds, final Stamp stamp) {
        final String[] lines = cut.split("\n", -1);
        if (lines.length < 2 || !lines[0].startsWith("#cut ") || !lines[1].equals(RiskExtractCut.HEADER)) {
            throw new IllegalStateException("risk extract: cut is not in the expected format");
        }
        final int declaredRows = Integer.parseInt(field(lines[0], "rows="));
        final long cutSeq = Long.parseLong(field(lines[0], "seq="));
        if (cutSeq != stamp.consensusSequence()) {
            throw new IllegalStateException("risk extract: cut sequence " + cutSeq
                + " does not match the stamped sequence " + stamp.consensusSequence());
        }

        final List<String> body = new ArrayList<>(declaredRows);
        int seen = 0;
        for (int i = 2; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            seen++;
            body.add(row(lines[i], marks, accounts, bonds, stamp));
        }
        if (seen != declaredRows) {
            // The cut travels as one message precisely so truncation is detectable here.
            throw new IllegalStateException("risk extract: cut declared " + declaredRows
                + " rows but carried " + seen);
        }

        final StringBuilder sb = new StringBuilder(1024 + body.size() * 128);
        preamble(sb, stamp, declaredRows);
        sb.append(HEADER).append('\n');
        for (final String line : body) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * The self-describing header. Everything here is derived from the stamp — nothing is sampled
     * from a clock — so it is part of what makes the fixture byte-reproducible. The convention
     * lines are deliberately explicit: they are the starting point for any tie-out discrepancy
     * hunt against our own P&L.
     */
    private static void preamble(final StringBuilder sb, final Stamp stamp, final int rows) {
        sb.append("# traderx-risk-extract schema=").append(SCHEMA).append('\n');
        sb.append("# consensusSequence=").append(stamp.consensusSequence()).append('\n');
        sb.append("# sessionDate=").append(stamp.sessionDate()).append('\n');
        sb.append("# priceSnapshotVersion=").append(stamp.priceVersion()).append('\n');
        sb.append("# cutSha256=").append(stamp.cutSha256()).append('\n');
        sb.append("# rows=").append(rows).append('\n');
        sb.append("# cutConsistency=every row is the replicated state machine's state at"
            + " consensusSequence on the totally-ordered consensus log, not a read-model query\n");
        sb.append("# netting=none; rows are un-netted at (accountId, security) grain\n");
        sb.append("# quantityConvention=signed net position in contracts (options), shares (equity)"
            + " or USD face amount (treasuries)\n");
        sb.append("# costBasisConvention=weighted average trade price per contract or share,"
            + " excludes fees and excludes the contract multiplier\n");
        sb.append("# marketValueConvention=quantity * closingMark * contractMultiplier\n");
        sb.append("# unrealizedPnlConvention=(closingMark - costBasis) * quantity * contractMultiplier\n");
        sb.append("# markSourceLegend=EOD_SNAPSHOT=YU06 published closing price for"
            + " (sessionDate, priceSnapshotVersion); CLUSTER_LAST_TRADE_AT_N=matching-engine last"
            + " trade price at consensusSequence\n");
        sb.append("# optionIdentity=OCC symbol; underlying, expiry, call/put and strike are"
            + " derivable from the security field\n");
        sb.append("# treasuryPriceConvention=costBasis and closingMark for instrumentType=TREASURY"
            + " are clean prices as a FRACTION of par (0.998780 = 99.878%), six decimals; the"
            + " contract multiplier is 1, so marketValue = face * fraction\n");
        sb.append("# treasuryStatic=coupon (annual %, fixed, semiannual) and maturityDate are"
            + " joined from instrument reference data; empty for non-treasury rows\n");
    }

    private static String row(final String cutLine, final Map<String, Mark> marks,
                              final Map<Integer, Counterparty> accounts,
                              final Map<String, BondStatic> bonds, final Stamp stamp) {
        final String[] c = cutLine.split(",", -1);
        if (c.length != 6) {
            throw new IllegalStateException("risk extract: malformed cut row: " + cutLine);
        }
        final int accountId = Integer.parseInt(c[0]);
        final String security = c[1];
        final long quantity = Long.parseLong(c[2]);
        final BigDecimal costBasis = ticks(Long.parseLong(c[3]));
        final long multiplier = Long.parseLong(c[4]);
        final long lastTradeTicks = Long.parseLong(c[5]);

        final Mark published = marks.get(security);
        final BigDecimal mark;
        final String markSource;
        final String markQuality;
        if (published != null) {
            mark = published.price().setScale(TICK_SCALE, RoundingMode.UNNECESSARY);
            markSource = "EOD_SNAPSHOT";
            markQuality = published.quality();
        } else if (lastTradeTicks != 0L) {
            // ADR-056: listed options have no published close, so the engine's own last trade at
            // the cut sequence is the mark. Same instant as the position, by construction.
            mark = ticks(lastTradeTicks);
            markSource = "CLUSTER_LAST_TRADE_AT_N";
            markQuality = "LAST_TRADE";
        } else {
            throw new IllegalStateException("risk extract: no mark for " + security
                + " (account " + accountId + ") in snapshot v" + stamp.priceVersion()
                + " and no last trade at sequence " + stamp.consensusSequence());
        }

        final Counterparty cp = accounts.get(accountId);
        if (cp == null) {
            throw new IllegalStateException("risk extract: account " + accountId
                + " has no counterparty mapping in reference data");
        }

        final BigDecimal qty = BigDecimal.valueOf(quantity);
        final BigDecimal mult = BigDecimal.valueOf(multiplier);
        final BigDecimal marketValue = mark.multiply(qty).multiply(mult).setScale(TICK_SCALE, RoundingMode.UNNECESSARY);
        final BigDecimal unrealised = mark.subtract(costBasis).multiply(qty).multiply(mult)
            .setScale(TICK_SCALE, RoundingMode.UNNECESSARY);

        final BondStatic bond = bonds.get(security);
        final String instrumentType =
            bond != null ? "TREASURY" : (OccSymbol.isOption(security) ? "OPTION" : "EQUITY");
        return accountId + "," + security + ","
            + instrumentType + ","
            + quantity + "," + multiplier + ","
            + costBasis.toPlainString() + "," + mark.toPlainString() + ","
            + markSource + "," + markQuality + ","
            + marketValue.toPlainString() + "," + unrealised.toPlainString() + ","
            + cp.currency() + "," + cp.counterpartyId() + "," + cp.nettingSetId() + ","
            + (bond == null ? "" : bond.couponRatePercent()) + ","
            + (bond == null ? "" : bond.maturityDate());
    }

    private static BigDecimal ticks(final long value) {
        return BigDecimal.valueOf(value, TICK_SCALE);
    }

    private static String field(final String header, final String key) {
        final int at = header.indexOf(key);
        if (at < 0) {
            throw new IllegalStateException("risk extract: cut header missing " + key);
        }
        final int from = at + key.length();
        int to = header.indexOf(' ', from);
        if (to < 0) {
            to = header.length();
        }
        return header.substring(from, to);
    }
}
