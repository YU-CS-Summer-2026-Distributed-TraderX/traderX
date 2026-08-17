package finos.traderx.ordermatcher.lmax;

/**
 * The market conventions of a vanilla fixed-float interest-rate swap, as a compile-time table
 * addressed by index (YU17, ADR-062).
 *
 * <p><b>Why a table and not fields on the command.</b> {@link InputEvent} carries a fixed, small
 * set of slots and a swap needs more per-trade values than a listed order does: notional, fixed
 * rate, direction, effective date, maturity, float index, payment frequency and day count. The
 * last three are not per-trade economics at all — they are a small enum in practice, chosen from
 * a handful of market standards — so they ride as ONE index and the per-trade payload stays
 * inside the existing slots. No new SBE template, no wire change.
 *
 * <p>That follows the pattern this line already uses twice: {@code OccSymbol.multiplierFor}
 * derives the contract multiplier from the committed ticker (ADR-052) and the Treasury book grid
 * is derived from the ticker prefix (ADR-060). Both store nothing and are therefore identical on
 * every member, on replay and on restore. A table compiled into the binary has the same property
 * for the same reason.
 *
 * <p><b>Indices are permanent.</b> A committed booking names a convention by index, so an index
 * that has ever been journaled must keep its meaning forever — appending is safe, reordering or
 * reusing an index silently rewrites the terms of contracts already booked. Append only.
 */
public final class SwapConventions {

    /** One row: the float leg's index, the fixed leg's payment frequency, day count, currency. */
    public record Convention(String name, String floatIndex, String paymentFrequency,
                             String dayCount, String currency) { }

    // Append only — see the class javadoc. Index 0 is the USD SOFR OIS standard.
    private static final Convention[] TABLE = {
        new Convention("USD-SOFR-1Y-ACT360", "USD-SOFR", "1Y", "ACT/360", "USD"),
        new Convention("USD-SOFR-3M-ACT360", "USD-SOFR", "3M", "ACT/360", "USD"),
        new Convention("EUR-ESTR-1Y-ACT360", "EUR-ESTR", "1Y", "ACT/360", "EUR"),
        new Convention("GBP-SONIA-1Y-ACT365F", "GBP-SONIA", "1Y", "ACT/365F", "GBP"),
        new Convention("JPY-TONA-1Y-ACT365F", "JPY-TONA", "1Y", "ACT/365F", "JPY"),
    };

    private SwapConventions() {
    }

    public static int count() {
        return TABLE.length;
    }

    /** Index for a convention name, or -1. Used at the boundary, before anything is sequenced. */
    public static int indexOf(final String name) {
        for (int i = 0; i < TABLE.length; i++) {
            if (TABLE[i].name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The convention at {@code index}.
     *
     * @throws IllegalStateException for an index this build does not know. A booking committed by
     *     a later build naming convention 7 must NOT render as convention 0 — publishing a
     *     contract under the wrong day count is worse than refusing to publish it.
     */
    public static Convention at(final int index) {
        if (index < 0 || index >= TABLE.length) {
            throw new IllegalStateException("unknown swap convention index " + index
                + " (this build knows 0.." + (TABLE.length - 1) + "): the contract was booked by a"
                + " later build. Roll forward; do not reinterpret it.");
        }
        return TABLE[index];
    }

    public static boolean knows(final int index) {
        return index >= 0 && index < TABLE.length;
    }

    // ----- currencies (YU17 FX-rate fix) -------------------------------------------------------
    //
    // The third index-addressed table, under the same append-only rule: a currency index is
    // journaled in every TYPE_FX_RATE event and in the T_FX_RATE snapshot record, so an index that
    // has ever been committed must keep its meaning forever. Index 0 is USD, the currency the
    // credit limit is denominated in — its rate is identity BY CONSTRUCTION and is never stored,
    // never settable and never absent.

    private static final String[] CURRENCIES = { "USD", "EUR", "GBP", "JPY" };

    public static int currencyCount() {
        return CURRENCIES.length;
    }

    /** Index for a currency code, or -1. Used at the boundary, before anything is sequenced. */
    public static int currencyIndexOf(final String code) {
        for (int i = 0; i < CURRENCIES.length; i++) {
            if (CURRENCIES[i].equalsIgnoreCase(code)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The currency index a convention's notional is denominated in, or -1 for a convention this
     * build does not know. -1 rather than a throw: the credit gate must refuse such a booking
     * deterministically (it cannot value a notional in an unknowable currency), not abort apply.
     */
    public static int currencyIndexOfConvention(final int conventionIndex) {
        return knows(conventionIndex) ? currencyIndexOf(TABLE[conventionIndex].currency()) : -1;
    }

    // ----- exercise style (YU17 phase 2, ADR-065) ----------------------------------------------
    //
    // The second index-addressed table in this class, under the same rules and for the same
    // reason: a swaption's exercise style is a small enum, it is committed to the log as an index,
    // and an index that has been journaled must keep its meaning forever.
    //
    // It is a TERM, not lifecycle. This state models no exercise EVENT — nobody exercises anything
    // (D6) — but a European and a Bermudan swaption on identical underlying terms are different
    // instruments with materially different value, and a risk file that could not tell them apart
    // would be describing one of them wrongly. So the style is published and the exercise is not.

    private static final String[] EXERCISE_STYLES = { "EUROPEAN", "BERMUDAN", "AMERICAN" };

    public static int exerciseStyleCount() {
        return EXERCISE_STYLES.length;
    }

    /** Index for an exercise style name, or -1. Used at the boundary, before anything is sequenced. */
    public static int exerciseStyleIndexOf(final String name) {
        for (int i = 0; i < EXERCISE_STYLES.length; i++) {
            if (EXERCISE_STYLES[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The exercise style at {@code index}.
     *
     * @throws IllegalStateException for an index this build does not know — for the same reason
     *     {@link #at(int)} refuses: publishing a Bermudan swaption as European is worse than
     *     refusing to publish it.
     */
    public static String exerciseStyleAt(final int index) {
        if (index < 0 || index >= EXERCISE_STYLES.length) {
            throw new IllegalStateException("unknown exercise style index " + index
                + " (this build knows 0.." + (EXERCISE_STYLES.length - 1) + "): the contract was"
                + " booked by a later build. Roll forward; do not reinterpret it.");
        }
        return EXERCISE_STYLES[index];
    }
}
