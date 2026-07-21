package finos.traderx.ordermatcher.lmax;

/**
 * Unpadded OCC option-symbol parsing (YU14, ADR-052): {@code <root><yymmdd><C|P><strike x 1000,
 * 8 digits>}, e.g. {@code AAPL260918C00240000}. The fixed-width 15-character tail makes the
 * parse unambiguous from the right; the remaining prefix is the underlying root.
 *
 * <p>The one product of this class consumed in-cluster is {@link #multiplierFor(String)}: a pure
 * function of the ticker string (no clock, locale, or state), evaluated identically on every
 * member and replay when a committed symbol registration is applied. Registration is a cold
 * path, so parsing here never touches the apply hot loop. Underlying, strike, expiry, and
 * call/put accessors serve the reference-data layer only.
 */
public final class OccSymbol {
    /** Standard US listed-option deliverable: 100 shares per contract. */
    public static final long OPTION_MULTIPLIER = 100L;
    public static final long EQUITY_MULTIPLIER = 1L;

    private static final int TAIL = 15;   // yymmdd + C|P + 8-digit strike

    private OccSymbol() {
    }

    /** True when the ticker is an unpadded OCC option symbol. */
    public static boolean isOption(final String ticker) {
        if (ticker == null || ticker.length() < TAIL + 1) {
            return false;
        }
        final int rootEnd = ticker.length() - TAIL;
        for (int i = 0; i < rootEnd; i++) {   // root: uppercase letters only
            final char c = ticker.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        for (int i = rootEnd; i < rootEnd + 6; i++) {   // yymmdd
            if (!isDigit(ticker.charAt(i))) {
                return false;
            }
        }
        final char cp = ticker.charAt(rootEnd + 6);
        if (cp != 'C' && cp != 'P') {
            return false;
        }
        for (int i = rootEnd + 7; i < ticker.length(); i++) {   // strike x 1000
            if (!isDigit(ticker.charAt(i))) {
                return false;
            }
        }
        final int mm = digits(ticker, rootEnd + 2, 2);
        final int dd = digits(ticker, rootEnd + 4, 2);
        return mm >= 1 && mm <= 12 && dd >= 1 && dd <= 31;
    }

    /** Contract multiplier for a registering ticker: option → 100, anything else → 1. */
    public static long multiplierFor(final String ticker) {
        return isOption(ticker) ? OPTION_MULTIPLIER : EQUITY_MULTIPLIER;
    }

    // ----- reference-data accessors (call only when isOption) ---------------------------------

    public static String underlying(final String ticker) {
        return ticker.substring(0, ticker.length() - TAIL);
    }

    /** Expiry as yymmdd, e.g. 260918. */
    public static int expiryYymmdd(final String ticker) {
        return digits(ticker, ticker.length() - TAIL, 6);
    }

    public static boolean isCall(final String ticker) {
        return ticker.charAt(ticker.length() - 9) == 'C';
    }

    /** Strike in thousandths of a dollar (the OCC encoding), e.g. 240000 = $240.000. */
    public static long strikeThousandths(final String ticker) {
        return digits(ticker, ticker.length() - 8, 8);
    }

    private static boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    private static int digits(final String s, final int from, final int count) {
        int value = 0;
        for (int i = from; i < from + count; i++) {
            value = value * 10 + (s.charAt(i) - '0');
        }
        return value;
    }
}
