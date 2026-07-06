package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Penny-parity fixture (SC-09B04 / NGC-03): long fixed-point arithmetic must reproduce
 * state 009's BigDecimal behavior (roundPrice = 3dp HALF_UP; in-the-money via compareTo).
 */
class PxTest {

    @Test
    void roundTripMatches009Rounding() {
        String[] fixtures = {
            "187.250", "187.2499", "187.2495", "412.000", "0.001", "0.0005",
            "905.125", "61.5", "507.88", "99.9994", "99.9995", "123456789.999"
        };
        for (String raw : fixtures) {
            BigDecimal input = new BigDecimal(raw);
            BigDecimal expected = input.setScale(3, RoundingMode.HALF_UP); // 009 roundPrice
            assertEquals(0, expected.compareTo(Px.toBigDecimal(Px.toTicks(input))),
                "penny drift for " + raw);
        }
    }

    @Test
    void inTheMoneyComparesMatchBigDecimal() {
        String[] prices = {"99.999", "100.000", "100.001", "187.250", "0.001", "905.125"};
        String[] limits = {"100.000", "187.250", "0.001", "905.125"};
        for (String p : prices) {
            for (String l : limits) {
                BigDecimal pb = new BigDecimal(p);
                BigDecimal lb = new BigDecimal(l);
                long pt = Px.toTicks(pb);
                long lt = Px.toTicks(lb);
                // Buy in-the-money: market <= limit; Sell: market >= limit (009 semantics)
                assertEquals(pb.compareTo(lb) <= 0, pt <= lt, "buy compare " + p + " vs " + l);
                assertEquals(pb.compareTo(lb) >= 0, pt >= lt, "sell compare " + p + " vs " + l);
            }
        }
    }

    @Test
    void sentinelRoundTrips() {
        assertNull(Px.toBigDecimal(Px.NONE));
        assertEquals(Px.NONE, Px.toTicks(null));
    }
}
