package finos.traderx.tradeprocessor.service;

import finos.traderx.messaging.Envelope;
import finos.traderx.messaging.nats.NatsJSONSubscriber;
import finos.traderx.tradeprocessor.model.PriceTick;

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
        // ARRIVAL time, not the payload's asOf. YU17 (ADR-070) split the two: a replayed tick
        // carries the TRUE tape timestamp in asOf — February 2025, honestly — while the feed
        // delivering it is live right now. Recording asOf here made every consumer of this
        // history misread the replay as a dead feed: the EOD staleness gate flagged all 18
        // replayed equities STALE at every close forever (measured 2026-08-26, quality-gate
        // step 6), and a TCA window keyed on wall-clock trades could never overlap ticks stamped
        // eighteen months ago. Freshness is a property of the FEED (arrival); when-was-this-true
        // is the payload's to keep saying. On the walk the two are milliseconds apart and this
        // line changes nothing.
        priceHistory.record(tick.getTicker(), tick.getPrice(), System.currentTimeMillis());
    }
}
