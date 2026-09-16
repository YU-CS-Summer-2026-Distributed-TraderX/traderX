package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.messaging.Envelope;
import finos.traderx.tradeprocessor.model.EodQuality;
import finos.traderx.tradeprocessor.model.PriceTick;
import finos.traderx.tradeprocessor.service.PriceHistoryStore.PriceSample;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Owned by YU15 because these cases integrate the YU05 handler with YU15 EOD quality rules.
 * The runtime handler stays in YU05; this test must not introduce EOD dependencies into YU05.
 *
 * The rule this guards was fixed on 2026-08-26 (fd1853a5) WITHOUT a test, and the gap cost three
 * weeks: the price history must stamp a tick's ARRIVAL, never the payload's {@code asOf}.
 *
 * <p>Why it matters, stated precisely. A replayed tape tick honestly carries the true tape
 * timestamp — 2025-03-31 for a tape held at its end — while the feed delivering it is live now.
 * Recording {@code asOf} made every replayed symbol read as a dead feed, so the EOD staleness gate
 * flagged all of them at every close, forever. Measured on the 2026-09-15 live close: 23 of 67
 * instruments STALE, every one of them carrying sourceTickMillis 1743451200000, and the 44 healthy
 * marks were exactly the instruments the tape does not carry.
 *
 * <p>What this does NOT claim, because the distinction is the whole point: arrival freshness proves
 * the FEED delivered recently. It says nothing about whether the price is current in the market. A
 * tape mark republished today is still a historical observation, and an OK arrival quality must
 * never be read as market-price currency.
 */
class PriceTickEodIntegrationTest {

    /** 2025-03-31T20:00:00Z — the tape's last window close, the exact value seen in the live run. */
    private static final long TAPE_ASOF_MILLIS = 1_743_451_200_000L;
    private static final String TAPE_ASOF = "2025-03-31T20:00:00.000Z";

    @Test
    void recordsArrivalTimeAndNeverThePayloadAsOf() {
        PriceHistoryStore store = new PriceHistoryStore(1000);
        PriceTickHandler handler = new PriceTickHandler(store);

        // Bounded assertion rather than a fixed instant: the recorded stamp must fall inside the
        // window this call actually spanned. No sleeping, no clock injection, no flakiness.
        long before = System.currentTimeMillis();
        handler.onMessage(envelope(), tick("AAPL", "222.47", TAPE_ASOF));
        long after = System.currentTimeMillis();

        PriceSample sample = store.priceAtOrBefore("AAPL", after).orElseThrow();
        assertTrue(sample.timestampMillis() >= before && sample.timestampMillis() <= after,
            "expected an arrival stamp inside [" + before + ", " + after + "], got " + sample.timestampMillis());
        assertEquals(new BigDecimal("222.47"), sample.price());

        // The payload's own timestamp must not have become the sample's time. Asserted against the
        // real tape value, so a regression reproduces the live failure exactly.
        assertFalse(sample.timestampMillis() == TAPE_ASOF_MILLIS,
            "the payload asOf was recorded as the arrival time — this is the 2026-09-15 defect");
        assertTrue(sample.timestampMillis() > TAPE_ASOF_MILLIS,
            "an arrival stamp must be later than an eighteen-month-old tape observation");
    }

    @Test
    void aTickRecordedOnArrivalIsFreshAtACloseTakenNow() {
        PriceHistoryStore store = new PriceHistoryStore(1000);
        PriceTickHandler handler = new PriceTickHandler(store);
        EodQualityChecker checker = new EodQualityChecker(300, new BigDecimal("20"), new BigDecimal("200"));

        handler.onMessage(envelope(), tick("AAPL", "222.47", TAPE_ASOF));
        long closeMillis = System.currentTimeMillis();

        // The same tape-dated payload that produced 23 STALE marks live is OK when its arrival is
        // what was stamped. This is a FEED-freshness statement only.
        assertEquals(EodQuality.OK,
            checker.classify("AAPL", store.priceAtOrBefore("AAPL", closeMillis), Optional.empty(), closeMillis).quality());
    }

    @Test
    void silenceStillTripsFreshness() {
        PriceHistoryStore store = new PriceHistoryStore(1000);
        PriceTickHandler handler = new PriceTickHandler(store);
        EodQualityChecker checker = new EodQualityChecker(300, new BigDecimal("20"), new BigDecimal("200"));

        handler.onMessage(envelope(), tick("AAPL", "222.47", TAPE_ASOF));
        long arrival = store.priceAtOrBefore("AAPL", System.currentTimeMillis()).orElseThrow().timestampMillis();

        // Arrival stamping must not blunt the gate: a feed that goes quiet is still caught. One
        // millisecond past the threshold, evaluated against the sample's own arrival time.
        long justInside = arrival + 300_000L;
        long justOutside = justInside + 1L;
        assertEquals(EodQuality.OK,
            checker.classify("AAPL", store.priceAtOrBefore("AAPL", justInside), Optional.empty(), justInside).quality());
        assertEquals(EodQuality.STALE,
            checker.classify("AAPL", store.priceAtOrBefore("AAPL", justOutside), Optional.empty(), justOutside).quality(),
            "a silent feed must still trip the staleness gate");

        // And an instrument that never ticked at all is MISSING, not silently fresh.
        assertEquals(EodQuality.MISSING,
            checker.classify("NEVERTICKED", store.priceAtOrBefore("NEVERTICKED", justOutside), Optional.empty(), justOutside).quality());
    }

    @Test
    void ticksWithoutATickerOrAPriceAreIgnored() {
        PriceHistoryStore store = new PriceHistoryStore(1000);
        PriceTickHandler handler = new PriceTickHandler(store);

        handler.onMessage(envelope(), tick(null, "222.47", TAPE_ASOF));
        handler.onMessage(envelope(), tick("AAPL", null, TAPE_ASOF));

        assertFalse(store.priceAtOrBefore("AAPL", System.currentTimeMillis()).isPresent(),
            "a tick with no price must not enter the history");
        assertTrue(store.tickers().isEmpty(), "nothing should have been recorded at all");
    }

    private static PriceTick tick(String ticker, String price, String asOf) {
        PriceTick tick = new PriceTick();
        tick.setTicker(ticker);
        tick.setPrice(price == null ? null : new BigDecimal(price));
        tick.setAsOf(asOf);
        tick.setSource("taq-replay-2025-02");
        return tick;
    }

    /** The handler reads nothing off the envelope; a minimal stub keeps the test to its subject. */
    private static Envelope<PriceTick> envelope() {
        return new Envelope<PriceTick>() {
            @Override public String getType() { return "PriceTick"; }
            @Override public String getTopic() { return "pricing.AAPL"; }
            @Override public PriceTick getPayload() { return null; }
            @Override public Date getDate() { return new Date(); }
            @Override public String getFrom() { return "price-publisher"; }
        };
    }
}
