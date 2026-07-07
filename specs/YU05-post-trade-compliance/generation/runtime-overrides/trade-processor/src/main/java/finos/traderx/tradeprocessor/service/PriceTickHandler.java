package finos.traderx.tradeprocessor.service;

import finos.traderx.messaging.Envelope;
import finos.traderx.messaging.nats.NatsJSONSubscriber;
import finos.traderx.tradeprocessor.model.PriceTick;
import java.time.Instant;

/**
 * YU05 (post-trade-compliance, ADR-024): subscribes to price-publisher's existing {@code
 * pricing.*} NATS wildcard subject (the same JSON feed the Angular front-end's live ticker
 * consumes) and feeds every tick into {@link PriceHistoryStore}. No new data source, no order-
 * matcher/BLP involvement — TCA is read-side only (FR-PTC31).
 */
public class PriceTickHandler extends NatsJSONSubscriber<PriceTick> {
    private final PriceHistoryStore priceHistory;

    public PriceTickHandler(PriceHistoryStore priceHistory) {
        super(PriceTick.class);
        this.priceHistory = priceHistory;
    }

    @Override
    public void onMessage(Envelope<?> envelope, PriceTick tick) {
        if (tick.getTicker() == null || tick.getPrice() == null) {
            return;
        }
        priceHistory.record(tick.getTicker(), tick.getPrice(), parseAsOf(tick.getAsOf()));
    }

    private static long parseAsOf(String asOf) {
        if (asOf == null) {
            return System.currentTimeMillis();
        }
        try {
            return Instant.parse(asOf).toEpochMilli();
        } catch (Exception ex) {
            return System.currentTimeMillis();
        }
    }
}
