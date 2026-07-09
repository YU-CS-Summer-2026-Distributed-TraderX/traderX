package finos.traderx.tradeprocessor.model;

/**
 * YU06 (eod-price-production, ADR-026): data-quality classification of a closing price.
 * {@code STALE}/{@code SPIKE}/{@code MISSING} are "flagged" — a version carrying any of them is
 * blocked from publication (fail-safe, FR-EOD23) until an operator overrides it. {@code OVERRIDDEN}
 * is a clean, operator-supplied correction. {@code OK} is a clean, fresh, in-bounds price.
 */
public enum EodQuality {
    OK,
    STALE,
    SPIKE,
    MISSING,
    OVERRIDDEN;

    /** Blocks publication until resolved (FR-EOD23). {@code OVERRIDDEN} and {@code OK} do not. */
    public boolean isFlagged() {
        return this == STALE || this == SPIKE || this == MISSING;
    }
}
