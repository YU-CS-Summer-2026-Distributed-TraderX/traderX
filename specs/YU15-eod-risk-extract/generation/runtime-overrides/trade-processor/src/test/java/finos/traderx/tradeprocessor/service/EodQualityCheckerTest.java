package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import finos.traderx.tradeprocessor.model.EodPrice;
import finos.traderx.tradeprocessor.model.EodQuality;
import finos.traderx.tradeprocessor.service.PriceHistoryStore.PriceSample;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** YU06 (FR-EOD10/11): quality classification is a pure function — exhaustively unit-tested here. */
class EodQualityCheckerTest {

    private static final long CLOSE = 1_000_000_000_000L;
    private final EodQualityChecker checker =
        new EodQualityChecker(300, new BigDecimal("20"), new BigDecimal("200"));

    @Test
    void freshInBoundsIsOk() {
        EodPrice p = checker.classify("AAA",
            Optional.of(new PriceSample(new BigDecimal("100"), CLOSE - 1000)),
            Optional.of(new BigDecimal("95")), CLOSE);
        assertEquals(EodQuality.OK, p.quality());
        assertEquals(new BigDecimal("100"), p.closingPrice());
    }

    @Test
    void noSampleIsMissing() {
        EodPrice p = checker.classify("AAA", Optional.empty(), Optional.empty(), CLOSE);
        assertEquals(EodQuality.MISSING, p.quality());
        assertEquals(null, p.closingPrice());
    }

    @Test
    void oldSampleIsStale() {
        // 400s old > 300s threshold
        EodPrice p = checker.classify("AAA",
            Optional.of(new PriceSample(new BigDecimal("100"), CLOSE - 400_000)),
            Optional.of(new BigDecimal("100")), CLOSE);
        assertEquals(EodQuality.STALE, p.quality());
    }

    @Test
    void bigMoveVsPriorCloseIsSpike() {
        // 100 -> 130 is +30% > 20%
        EodPrice p = checker.classify("AAA",
            Optional.of(new PriceSample(new BigDecimal("130"), CLOSE - 1000)),
            Optional.of(new BigDecimal("100")), CLOSE);
        assertEquals(EodQuality.SPIKE, p.quality());
    }

    @Test
    void bigDownMoveVsPriorCloseIsSpike() {
        EodPrice p = checker.classify("AAA",
            Optional.of(new PriceSample(new BigDecimal("79"), CLOSE - 1000)),
            Optional.of(new BigDecimal("100")), CLOSE);
        assertEquals(EodQuality.SPIKE, p.quality());
    }

    @Test
    void moveExactlyAtThresholdIsOk() {
        EodPrice up = checker.classify("AAA",
            Optional.of(new PriceSample(new BigDecimal("120"), CLOSE - 1000)),
            Optional.of(new BigDecimal("100")), CLOSE);
        EodPrice down = checker.classify("AAA",
            Optional.of(new PriceSample(new BigDecimal("80"), CLOSE - 1000)),
            Optional.of(new BigDecimal("100")), CLOSE);
        assertEquals(EodQuality.OK, up.quality());
        assertEquals(EodQuality.OK, down.quality());
    }

    @Test
    void moveWithinBoundIsOk() {
        // 100 -> 115 is +15% < 20%
        EodPrice p = checker.classify("AAA",
            Optional.of(new PriceSample(new BigDecimal("115"), CLOSE - 1000)),
            Optional.of(new BigDecimal("100")), CLOSE);
        assertEquals(EodQuality.OK, p.quality());
    }

    @Test
    void firstSessionHasNoSpikeBaseline() {
        // No prior close -> cannot be a spike however far it is from anything.
        EodPrice p = checker.classify("AAA",
            Optional.of(new PriceSample(new BigDecimal("9999"), CLOSE - 1000)),
            Optional.empty(), CLOSE);
        assertEquals(EodQuality.OK, p.quality());
    }

    @Test
    void stalenessTakesPrecedenceOverSpike() {
        // Old AND a big move -> STALE wins (precedence).
        EodPrice p = checker.classify("AAA",
            Optional.of(new PriceSample(new BigDecimal("130"), CLOSE - 400_000)),
            Optional.of(new BigDecimal("100")), CLOSE);
        assertEquals(EodQuality.STALE, p.quality());
    }

    // ----- YU15: options carry their own spike band -----------------------------------------

    @Test
    void anOptionMoveThatWouldFlagAnEquityIsClean() {
        // A 65% premium move off a ~1% move in the underlying is ordinary for a leveraged
        // contract. Under the equity band this was SPIKE, and one flagged instrument blocks
        // publication of the whole session — equities included.
        EodPrice p = checker.classify("AAPL260918C00240000",
            Optional.of(new PriceSample(new BigDecimal("6.20"), CLOSE - 1000)),
            Optional.of(new BigDecimal("3.80")), CLOSE);
        assertEquals(EodQuality.OK, p.quality());
    }

    @Test
    void anEquityKeepsTheTighterBand() {
        // Same relative move, ordinary ticker: still a spike. The equity gate is unchanged.
        EodPrice p = checker.classify("AAPL",
            Optional.of(new PriceSample(new BigDecimal("6.20"), CLOSE - 1000)),
            Optional.of(new BigDecimal("3.80")), CLOSE);
        assertEquals(EodQuality.SPIKE, p.quality());
    }

    @Test
    void anOptionStillSpikesOnAnAbsurdMove() {
        // The wider band is not no band: a 100x premium is still bad data.
        EodPrice p = checker.classify("MSFT261218P00390000",
            Optional.of(new PriceSample(new BigDecimal("380.00"), CLOSE - 1000)),
            Optional.of(new BigDecimal("3.80")), CLOSE);
        assertEquals(EodQuality.SPIKE, p.quality());
    }

    @Test
    void anOptionWithNoTickIsStillMissing() {
        // The wider spike band must not weaken the fail-safe: no sample is still no price.
        EodPrice p = checker.classify("AAPL260918C00240000", Optional.empty(),
            Optional.of(new BigDecimal("3.80")), CLOSE);
        assertEquals(EodQuality.MISSING, p.quality());
    }

    @Test
    void aStaleOptionIsStillStale() {
        EodPrice p = checker.classify("AAPL260918C00240000",
            Optional.of(new PriceSample(new BigDecimal("3.90"), CLOSE - 400_000)),
            Optional.of(new BigDecimal("3.80")), CLOSE);
        assertEquals(EodQuality.STALE, p.quality());
    }

    @Test
    void anEquityTickerIsNeverMistakenForAContract() {
        // If a plain ticker were read as an option it would silently get the loose band.
        EodPrice p = checker.classify("C",
            Optional.of(new PriceSample(new BigDecimal("60"), CLOSE - 1000)),
            Optional.of(new BigDecimal("40")), CLOSE);
        assertEquals(EodQuality.SPIKE, p.quality());
    }
}
