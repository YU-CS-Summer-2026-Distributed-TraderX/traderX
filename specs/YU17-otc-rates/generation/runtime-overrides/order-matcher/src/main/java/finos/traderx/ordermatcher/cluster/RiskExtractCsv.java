package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OccSymbol;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
     * rows by join against the instrument static. Schema 3 (YU16, ADR-061): two more append —
     * lastCouponDate and accruedInterestFraction, DERIVED from that same static rather than
     * joined, so the consumer and the extract agree on one accrued number instead of computing
     * it twice. Every earlier column keeps its name, position and meaning. */
    static final int SCHEMA = 3;

    static final String HEADER = "accountId,security,instrumentType,quantity,contractMultiplier,"
        + "costBasis,closingMark,markSource,markQuality,marketValue,unrealizedPnl,currency,"
        + "counterpartyId,nettingSetId,coupon,maturityDate,lastCouponDate,accruedInterestFraction";

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
    /**
     * @param dayCount the accrual convention, NAMED rather than assumed: {@code ACT/ACT ICMA} for
     *     Treasuries, {@code 30/360} for corporates. It is a field and not a constant because the
     *     two genuinely disagree — on the seeded GS 5.750% of 2036 the same position accrues
     *     0.004514 of par more under 30/360 than under ACT/ACT, which is $4,514 on $1m face. A
     *     bond's price and its accrued interest are only meaningful with respect to a convention,
     *     so the extract carries the convention it used rather than leaving the consumer to guess.
     * @param corporate true for a corporate issuer, which is what separates {@code CORPORATE} from
     *     {@code TREASURY} in the instrumentType column. Taken from the reference-data join, never
     *     from prefix-parsing the security (ADR-059).
     */
    record BondStatic(String couponRatePercent, String maturityDate, String dayCount,
                      boolean corporate) {

        /**
         * A US Treasury: ACT/ACT (ICMA), government issuer. A named factory rather than a
         * defaulting constructor, deliberately — a two-argument {@code new BondStatic(...)} that
         * quietly meant ACT/ACT is exactly how a corporate ends up accrued on the wrong
         * convention, and {@link RiskExtractMain} refuses to default the day count for the same
         * reason. Here the caller has to say "treasury" out loud.
         */
        static BondStatic treasury(final String couponRatePercent, final String maturityDate) {
            return new BondStatic(couponRatePercent, maturityDate, DAY_COUNT_ACT_ACT, false);
        }

        /** A fixed-rate bullet corporate: 30/360, and a credit spread over the curve. */
        static BondStatic corporate(final String couponRatePercent, final String maturityDate) {
            return new BondStatic(couponRatePercent, maturityDate, DAY_COUNT_30_360, true);
        }
    }

    static final String DAY_COUNT_30_360 = "30/360";
    static final String DAY_COUNT_ACT_ACT = "ACT/ACT ICMA";

    /**
     * A zero-coupon instrument — a Treasury bill or a STRIP — carries a coupon of exactly zero in
     * the reference data. Compared with {@code compareTo} rather than {@code equals} so every
     * spelling of zero ("0", "0.000", "0.0") lands in the same branch: a bill that arrived spelled
     * "0.000" instead of "0" and fell through to the coupon path would grow a fabricated schedule
     * and give back exactly the bug this branch exists to prevent.
     */
    private static boolean isZeroCoupon(final BondStatic bond) {
        return new BigDecimal(bond.couponRatePercent()).compareTo(BigDecimal.ZERO) == 0;
    }

    /** Accrued interest as of the session date, and the coupon date it accrued from. */
    private record Accrual(LocalDate lastCouponDate, BigDecimal fraction) { }

    /**
     * Accrued interest on a fixed-rate Treasury as of the session date (ADR-061).
     *
     * <p>The coupon schedule is generated BACKWARDS from {@code maturityDate} in six-month steps,
     * each step measured from the maturity anchor rather than from the step before it, so
     * end-of-month clamping cannot walk the schedule off its day (Aug 31 → Feb 29 → Aug 29 under
     * repeated subtraction). That makes the whole schedule a function of the maturity alone,
     * which is why no issue date is needed in the reference data — and it is also the standing
     * assumption: a short or long FIRST coupon is not modelled.
     *
     * <p>Day count is ACT/ACT (ICMA), the US Treasury convention — the elapsed fraction of the
     * current coupon period times half the annual coupon — and the result is a fraction of par,
     * the same unit as {@code closingMark} (ADR-057), so {@code closingMark + accrued} is the
     * dirty price with no scaling in between.
     *
     * <p>This is the ONE value in the extract that rounds: {@code elapsed/period} does not
     * terminate in decimal, so it cannot be exact the way a position value is. It rounds
     * HALF_EVEN at the tick scale, which is deterministic, rather than aborting the extract the
     * way {@code RoundingMode.UNNECESSARY} does everywhere else.
     *
     * @return null for a zero-coupon instrument, which has no coupon schedule to accrue over —
     *     the caller emits empty for both columns rather than a zero and a fabricated date
     */
    private static Accrual accrual(final BondStatic bond, final LocalDate sessionDate) {
        if (isZeroCoupon(bond)) {
            // A bill or a STRIP has NO COUPON SCHEDULE — which is a different statement from a
            // schedule that pays zero. The walk below would happily generate one anyway (it is a
            // function of the maturity alone) and emit a lastCouponDate that no issuer ever
            // announced, alongside a correct accrued 0.000000. The zero is right and the date is
            // fabricated, and the pair reads as a coherent bond row, so nothing downstream has
            // any way to notice: a consumer rolling accrual forward from that date to a settlement
            // date would compute interest on an instrument that pays none.
            //
            // Both columns go out EMPTY. Empty means "this instrument has no coupon schedule",
            // which is the truth; 0.000000 in the accrual column would mean "it has one and
            // nothing has accrued", which is not.
            return null;
        }
        final LocalDate maturity = LocalDate.parse(bond.maturityDate());
        if (!sessionDate.isBefore(maturity)) {
            // At or past maturity the final coupon has paid; nothing has accrued since.
            return new Accrual(maturity, BigDecimal.ZERO.setScale(TICK_SCALE));
        }
        int periodsBack = 0;
        LocalDate last = maturity;
        while (last.isAfter(sessionDate)) {
            last = maturity.minusMonths(6L * ++periodsBack);
        }
        final LocalDate next = maturity.minusMonths(6L * (periodsBack - 1));
        final BigDecimal semiAnnualCoupon = new BigDecimal(bond.couponRatePercent())
            .movePointLeft(2).multiply(new BigDecimal("0.5"));
        final boolean thirty360 = DAY_COUNT_30_360.equals(bond.dayCount());
        final BigDecimal elapsed = BigDecimal.valueOf(
            thirty360 ? days30360(last, sessionDate) : ChronoUnit.DAYS.between(last, sessionDate));
        // Under 30/360 a semiannual period is 180 days BY DEFINITION, not by measurement — that is
        // the whole content of the convention. Measuring the real period here and calling it
        // 30/360 would give a number that is neither convention.
        final BigDecimal periodDays = thirty360
            ? BigDecimal.valueOf(180L)
            : BigDecimal.valueOf(ChronoUnit.DAYS.between(last, next));
        final BigDecimal fraction = semiAnnualCoupon.multiply(elapsed)
            .divide(periodDays, TICK_SCALE, RoundingMode.HALF_EVEN);
        return new Accrual(last, fraction);
    }

    /**
     * US 30/360 (bond basis) day count. Both end-of-month clamps matter: without the second, a
     * period ending on the 31st is a day longer than one ending on the 30th, which is precisely
     * what 30/360 exists to deny. Mirrors the publisher's days30360 in treasury-pricing.js — the
     * two must agree or a consumer reconciling our accrual against our own feed sees a break.
     */
    private static long days30360(final LocalDate from, final LocalDate to) {
        int d1 = from.getDayOfMonth();
        int d2 = to.getDayOfMonth();
        if (d1 == 31) {
            d1 = 30;
        }
        if (d2 == 31 && d1 == 30) {
            d2 = 30;
        }
        return 360L * (to.getYear() - from.getYear())
            + 30L * (to.getMonthValue() - from.getMonthValue())
            + (d2 - d1);
    }

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
            // YU17: the cut gained a second section. Stop at its marker rather than parsing its
            // rows as positions — an OTC contract is not a position and never appears in the netted
            // extract (D3). Stopping at ANY '#' rather than at the literal marker keeps this reader
            // correct against a cut that grows a further section later.
            if (lines[i].charAt(0) == '#') {
                break;
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
        sb.append("# treasuryAccrualUnit=accruedInterestFraction is a FRACTION OF PAR in the same"
            + " unit as closingMark, so dirtyPrice = closingMark + accruedInterestFraction and"
            + " settlementValue = quantity * dirtyPrice; marketValue above stays CLEAN\n");
        sb.append("# instrumentTypeLegend=EQUITY | OPTION | TREASURY | CORPORATE. CORPORATE and"
            + " TREASURY are both fixed-rate bullet debt and share every bond column; they are"
            + " separated because they carry DIFFERENT DAY COUNTS and different credit risk. The"
            + " split comes from the reference-data join, never from prefix-parsing the security"
            + " (ADR-059) - a consumer that does not care may treat both as debt\n");
        sb.append("# bondDayCount=the accrual convention this file USED, per row, stated rather"
            + " than assumed: ACT/ACT (ICMA) for TREASURY, 30/360 for CORPORATE. They disagree by"
            + " real money - on the seeded GS 5.750% of 2036 the same position accrues 0.004514 of"
            + " par more under 30/360, which is $4,514 on $1m face - so a consumer reconciling"
            + " against its own model must use the convention named here, not a default\n");
        sb.append("# treasuryAccrualConvention=ACT/ACT (ICMA) semiannual: (days from"
            + " lastCouponDate to sessionDate / days in that coupon period) * coupon/2. Accrual"
            + " runs to sessionDate ITSELF, not to a T+1 settlement date, because every other"
            + " column here is as-of sessionDate and this system carries no holiday calendar. A"
            + " consumer wanting settlement-date accrual has lastCouponDate and coupon to roll"
            + " it forward\n");
        sb.append("# treasuryCouponSchedule=generated backwards from maturityDate in 6-month"
            + " steps measured from maturity, so nextCouponDate = lastCouponDate + 6 months; a"
            + " short or long first coupon is NOT modelled\n");
        sb.append("# treasuryZeroCoupon=a bill or STRIP (coupon 0) has NO coupon schedule, so"
            + " lastCouponDate and accruedInterestFraction are both EMPTY, never 0.000000. Empty"
            + " means no schedule exists; a zero in the accrual column would mean one exists and"
            + " nothing has accrued, which is a different and false claim. coupon and maturityDate"
            + " are still populated - 0 and the maturity are facts about the instrument. For these"
            + " rows dirtyPrice == closingMark, with no accrual to add\n");
        sb.append("# treasuryAccrualRounding=the only rounded value in this file (elapsed/period"
            + " does not terminate); HALF_EVEN at 6 decimals. Every other value is exact\n");
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
        final String instrumentType = bond != null
            ? (bond.corporate() ? "CORPORATE" : "TREASURY")
            : (OccSymbol.isOption(security) ? "OPTION" : "EQUITY");
        final Accrual accrual = bond == null ? null : accrual(bond, stamp.sessionDate());
        return accountId + "," + security + ","
            + instrumentType + ","
            + quantity + "," + multiplier + ","
            + costBasis.toPlainString() + "," + mark.toPlainString() + ","
            + markSource + "," + markQuality + ","
            + marketValue.toPlainString() + "," + unrealised.toPlainString() + ","
            + cp.currency() + "," + cp.counterpartyId() + "," + cp.nettingSetId() + ","
            + (bond == null ? "" : bond.couponRatePercent()) + ","
            + (bond == null ? "" : bond.maturityDate()) + ","
            + (accrual == null ? "" : accrual.lastCouponDate().toString()) + ","
            + (accrual == null ? "" : accrual.fraction().toPlainString());
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
