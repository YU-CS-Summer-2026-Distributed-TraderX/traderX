package finos.traderx.tradeprocessor.model;

import java.math.BigDecimal;

/**
 * YU06 (eod-price-production): one instrument's closing price in a versioned snapshot
 * ({@code eod_price_snapshot} row). Immutable. {@code closingPrice} is null only for a
 * {@link EodQuality#MISSING} instrument that has not been overridden; {@code sourceTickMillis} is
 * the event-time of the tick the price came from (staleness evidence); {@code overrideReason} is
 * set only for {@link EodQuality#OVERRIDDEN}.
 */
public record EodPrice(
    String security,
    BigDecimal closingPrice,
    EodQuality quality,
    Long sourceTickMillis,
    String overrideReason) {

    public boolean isFlagged() {
        return quality.isFlagged();
    }
}
