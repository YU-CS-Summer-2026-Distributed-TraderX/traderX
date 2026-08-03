package finos.traderx.tradeprocessor.service;

import finos.traderx.tradeprocessor.model.EodPrice;
import finos.traderx.tradeprocessor.model.EodQuality;
import finos.traderx.tradeprocessor.service.PriceHistoryStore.PriceSample;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * YU06 (eod-price-production, ADR-026, FR-EOD10/11): classifies a candidate closing price as
 * {@link EodQuality#OK}/{@code STALE}/{@code SPIKE}/{@code MISSING}. Pure function of (sample,
 * prior published close, close instant, thresholds) — no I/O — so it is exhaustively unit-testable.
 *
 * <p>Precedence: {@code MISSING} (no sample at all) &gt; {@code STALE} (sample too old) &gt;
 * {@code SPIKE} (moved too far vs. the prior published close) &gt; {@code OK}. A first-ever session
 * for an instrument has no prior close, so the spike check is skipped (cannot be a spike vs.
 * nothing).
 */
@Component
public class EodQualityChecker {

    private final long stalenessMillis;
    private final BigDecimal maxMovePct;

    public EodQualityChecker(
        @Value("${eod.quality.staleness-seconds:300}") long stalenessSeconds,
        @Value("${eod.quality.max-move-pct:20}") BigDecimal maxMovePct) {
        this.stalenessMillis = stalenessSeconds * 1000L;
        this.maxMovePct = maxMovePct;
    }

    public EodPrice classify(String security, Optional<PriceSample> sample,
                             Optional<BigDecimal> priorClose, long closeMillis) {
        if (sample.isEmpty()) {
            return new EodPrice(security, null, EodQuality.MISSING, null, null);
        }
        PriceSample s = sample.get();
        if (closeMillis - s.timestampMillis() > stalenessMillis) {
            return new EodPrice(security, s.price(), EodQuality.STALE, s.timestampMillis(), null);
        }
        if (priorClose.isPresent() && isSpike(s.price(), priorClose.get())) {
            return new EodPrice(security, s.price(), EodQuality.SPIKE, s.timestampMillis(), null);
        }
        return new EodPrice(security, s.price(), EodQuality.OK, s.timestampMillis(), null);
    }

    private boolean isSpike(BigDecimal price, BigDecimal priorClose) {
        if (priorClose.signum() == 0) {
            return false; // no meaningful percentage move off a zero baseline
        }
        BigDecimal movePct = price.subtract(priorClose).abs()
            .divide(priorClose.abs(), 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        return movePct.compareTo(maxMovePct) > 0;
    }
}
