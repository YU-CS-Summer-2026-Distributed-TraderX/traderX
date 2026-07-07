package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** YU05 (post-trade-compliance, ADR-024): bounded per-ticker price history + TWAP math. */
class PriceHistoryStoreTest {

    @Test
    void priceAtOrBeforeReturnsTheMostRecentSampleNotAfterTheTimestamp() {
        PriceHistoryStore store = new PriceHistoryStore(1000);
        store.record("IBM", new BigDecimal("100.000"), 1000L);
        store.record("IBM", new BigDecimal("101.000"), 2000L);
        store.record("IBM", new BigDecimal("102.000"), 3000L);

        assertEquals(new BigDecimal("101.000"), store.priceAtOrBefore("IBM", 2500L).orElseThrow().price());
        assertEquals(new BigDecimal("102.000"), store.priceAtOrBefore("IBM", 5000L).orElseThrow().price());
        assertFalse(store.priceAtOrBefore("IBM", 500L).isPresent());
    }

    @Test
    void twapIsTimeWeightedNotSimpleAverage() {
        PriceHistoryStore store = new PriceHistoryStore(1000);
        // Price stays at 100 for 9 of the 10 seconds, then jumps to 200 for the last 1 second.
        // A simple average would be 150; the time-weighted average should be close to 100.
        store.record("IBM", new BigDecimal("100.000"), 0L);
        store.record("IBM", new BigDecimal("200.000"), 9000L);
        store.record("IBM", new BigDecimal("200.000"), 10000L);

        // Trapezoid weighting: [0,9000)@100 contributes 900,000; [9000,10000)@200 contributes
        // 200,000; total 1,100,000 / 10,000ms = 110.000 — well below the naive 150 average.
        PriceHistoryStore.TwapResult result = store.twap("IBM", 0L, 10000L).orElseThrow();
        assertTrue(result.twap().compareTo(new BigDecimal("150.000")) < 0);
        assertEquals(0, result.twap().compareTo(new BigDecimal("110.000")));
        assertEquals(3, result.sampleCount());
    }

    @Test
    void twapWithASingleSampleInWindowReturnsThatPrice() {
        PriceHistoryStore store = new PriceHistoryStore(1000);
        store.record("IBM", new BigDecimal("100.000"), 5000L);

        PriceHistoryStore.TwapResult result = store.twap("IBM", 0L, 10000L).orElseThrow();
        assertEquals(new BigDecimal("100.000"), result.twap());
        assertEquals(1, result.sampleCount());
    }

    @Test
    void unknownTickerReturnsEmpty() {
        PriceHistoryStore store = new PriceHistoryStore(1000);
        assertFalse(store.priceAtOrBefore("UNKNOWN", 1000L).isPresent());
        assertFalse(store.twap("UNKNOWN", 0L, 1000L).isPresent());
    }

    @Test
    void boundedCapacityEvictsOldestSamplesFirst() {
        PriceHistoryStore store = new PriceHistoryStore(3);
        for (long t = 0; t < 5; t++) {
            store.record("IBM", BigDecimal.valueOf(100 + t), t * 1000);
        }
        // Only the last 3 samples (t=2,3,4) should remain.
        assertFalse(store.priceAtOrBefore("IBM", 1000L).isPresent());
        assertEquals(BigDecimal.valueOf(102), store.priceAtOrBefore("IBM", 2000L).orElseThrow().price());
    }
}
