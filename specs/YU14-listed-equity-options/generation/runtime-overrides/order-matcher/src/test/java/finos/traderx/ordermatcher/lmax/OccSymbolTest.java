package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unpadded OCC option-symbol parsing (FR-LEO01/02): the multiplier decision and the derived
 *  reference-data fields are pure functions of the ticker string. */
class OccSymbolTest {

    @Test
    void parsesStandardOptionSymbols() {
        assertTrue(OccSymbol.isOption("AAPL260918C00240000"));
        assertTrue(OccSymbol.isOption("MSFT261218P00390000"));
        assertTrue(OccSymbol.isOption("F260918C00012500"));          // 1-char root, $12.50 strike
        assertEquals(100L, OccSymbol.multiplierFor("AAPL260918C00240000"));
    }

    @Test
    void derivesReferenceDataFields() {
        final String t = "AAPL260918C00240000";
        assertEquals("AAPL", OccSymbol.underlying(t));
        assertEquals(260918, OccSymbol.expiryYymmdd(t));
        assertTrue(OccSymbol.isCall(t));
        assertEquals(240_000L, OccSymbol.strikeThousandths(t));   // $240.000
        assertFalse(OccSymbol.isCall("MSFT261218P00390000"));
        assertEquals(390_000L, OccSymbol.strikeThousandths("MSFT261218P00390000"));
    }

    @Test
    void equityTickersAreNotOptions() {
        assertFalse(OccSymbol.isOption("AAPL"));
        assertFalse(OccSymbol.isOption("MSFT"));
        assertFalse(OccSymbol.isOption("BRKB"));
        assertEquals(1L, OccSymbol.multiplierFor("JPM"));
        assertEquals(1L, OccSymbol.multiplierFor(""));
        assertEquals(1L, OccSymbol.multiplierFor(null));
    }

    @Test
    void malformedNearMissesAreNotOptions() {
        assertFalse(OccSymbol.isOption("260918C00240000"), "no root");
        assertFalse(OccSymbol.isOption("AAPL260918X00240000"), "bad call/put flag");
        assertFalse(OccSymbol.isOption("AAPL260918C0024000"), "short tail");
        assertFalse(OccSymbol.isOption("AAPL260918C0024000A"), "non-digit strike");
        assertFalse(OccSymbol.isOption("AAPL261318C00240000"), "month 13");
        assertFalse(OccSymbol.isOption("AAPL260900C00240000"), "day 0");
        assertFalse(OccSymbol.isOption("aapl260918C00240000"), "lowercase root");
        assertFalse(OccSymbol.isOption("AAPL2609181C00240000AAPL"), "digits in root position");
    }
}
