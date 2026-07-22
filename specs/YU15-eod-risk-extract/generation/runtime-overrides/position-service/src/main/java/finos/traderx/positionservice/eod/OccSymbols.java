package finos.traderx.positionservice.eod;

/**
 * YU15: is this security identifier a listed option contract?
 *
 * <p>An option's identity IS its unpadded OCC symbol (YU14, ADR-052):
 * {@code <root><yymmdd><C|P><strike x 1000, 8 digits>}, e.g. {@code AAPL260918C00240000}. That is
 * an industry-fixed format, and deriving from the identifier is exactly what ADR-052 says every
 * consumer should do — so recognising one here needs no reference data and no lookup.
 *
 * <p>This deliberately duplicates the predicate in order-matcher's {@code lmax.OccSymbol}, which
 * is the canonical definition, and trade-processor's copy of the same check. The three services
 * are separate Gradle modules, and introducing a shared module to carry fifteen lines of
 * format check would couple the read-side services to the matching engine's build. Keep them in
 * agreement if the format ever changes — it is an OCC standard, so it will not.

 * <p>Also supplies the contract multiplier, which for this venue is a pure function of the
 * identifier (ADR-052/053): 100 for an OCC option, 1 otherwise.
 */
final class OccSymbols {

    private static final int TAIL = 15; // yymmdd + C|P + 8-digit strike

    private OccSymbols() {
    }

    /** Contract multiplier: 100 for a listed option, 1 for everything else. */
    static long contractMultiplier(String security) {
        return isOption(security) ? 100L : 1L;
    }

    static boolean isOption(String security) {
        if (security == null || security.length() <= TAIL) {
            return false;
        }
        int rootLength = security.length() - TAIL;
        for (int i = 0; i < rootLength; i++) {
            char c = security.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        int at = rootLength;
        if (!digits(security, at, 6)) {
            return false;
        }
        char callPut = security.charAt(at + 6);
        if (callPut != 'C' && callPut != 'P') {
            return false;
        }
        return digits(security, at + 7, 8);
    }

    private static boolean digits(String s, int from, int count) {
        for (int i = from; i < from + count; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
