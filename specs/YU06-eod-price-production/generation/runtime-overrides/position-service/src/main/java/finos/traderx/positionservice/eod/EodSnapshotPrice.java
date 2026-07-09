package finos.traderx.positionservice.eod;

import java.math.BigDecimal;

/**
 * YU06 (eod-price-production): one instrument's closing price as read from the versioned
 * {@code eod_price_snapshot} the gate event named. {@link #isUsable()} is the consumer-side
 * fail-safe gate (FR-EOD32): only a non-null price with clean quality ({@code OK}/{@code OVERRIDDEN})
 * may be used to mark a position — anything else (or a security absent from the snapshot) halts the
 * account.
 */
public record EodSnapshotPrice(String security, BigDecimal closingPrice, String quality) {

    public boolean isUsable() {
        return closingPrice != null && ("OK".equals(quality) || "OVERRIDDEN".equals(quality));
    }
}
