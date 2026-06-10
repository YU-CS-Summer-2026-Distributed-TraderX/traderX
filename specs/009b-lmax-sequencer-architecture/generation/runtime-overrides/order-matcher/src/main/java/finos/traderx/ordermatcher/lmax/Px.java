package finos.traderx.ordermatcher.lmax;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fixed-point price arithmetic for the LMAX hot path (state 009b, FR-09B05 / NGC-03).
 *
 * Prices travel through the rings and the BLP as {@code long} "ticks" (price x 1e6).
 * BigDecimal conversion happens only at the edges (gateway in, read-model/NATS out) and
 * rounds to 3dp HALF_UP, matching state 009's roundPrice() for penny parity (SC-09B04).
 */
public final class Px {
    public static final long SCALE = 1_000_000L;
    /** Sentinel for "no price available". Real prices are strictly positive. */
    public static final long NONE = 0L;

    private Px() {
    }

    /** Edge conversion in: BigDecimal -> ticks, applying 009's 3dp HALF_UP rounding. */
    public static long toTicks(BigDecimal price) {
        if (price == null) {
            return NONE;
        }
        return price.setScale(3, RoundingMode.HALF_UP).movePointRight(6).longValueExact();
    }

    /** Edge conversion out: ticks -> BigDecimal at the external 3dp scale. */
    public static BigDecimal toBigDecimal(long ticks) {
        if (ticks == NONE) {
            return null;
        }
        return BigDecimal.valueOf(ticks, 6).setScale(3, RoundingMode.HALF_UP);
    }
}
