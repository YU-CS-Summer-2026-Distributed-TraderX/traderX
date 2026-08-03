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
 *
 * <p>YU15: the spike threshold is instrument-aware. A 20% day-over-day move is a data-quality
 * alarm for an equity and an ordinary Tuesday for a listed option — an option is leveraged, so a
 * 1% move in the underlying routinely moves the premium by tens of percent. Holding options to the
 * equity threshold would flag them SPIKE, and a single flagged instrument blocks publication of
 * the whole session (FR-EOD23) — so it would have taken the entire EOD chain down, equities
 * included, rather than just mis-flagging options.
 */
@Component
public class EodQualityChecker {

    private final long stalenessMillis;
    private final BigDecimal maxMovePct;
    private final BigDecimal maxMovePctOption;

    public EodQualityChecker(
        @Value("${eod.quality.staleness-seconds:300}") long stalenessSeconds,
        @Value("${eod.quality.max-move-pct:20}") BigDecimal maxMovePct,
        @Value("${eod.quality.max-move-pct-option:200}") BigDecimal maxMovePctOption) {
        this.stalenessMillis = stalenessSeconds * 1000L;
        this.maxMovePct = maxMovePct;
        this.maxMovePctOption = maxMovePctOption;
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
        if (priorClose.isPresent() && isSpike(s.price(), priorClose.get(), thresholdFor(security))) {
            return new EodPrice(security, s.price(), EodQuality.SPIKE, s.timestampMillis(), null);
        }
        return new EodPrice(security, s.price(), EodQuality.OK, s.timestampMillis(), null);
    }

    /** Options get their own, much wider band; everything else keeps the equity threshold. */
    private BigDecimal thresholdFor(String security) {
        return OccSymbols.isOption(security) ? maxMovePctOption : maxMovePct;
    }

    private boolean isSpike(BigDecimal price, BigDecimal priorClose, BigDecimal threshold) {
        if (priorClose.signum() == 0) {
            return false; // no meaningful percentage move off a zero baseline
        }
        BigDecimal movePct = price.subtract(priorClose).abs()
            .divide(priorClose.abs(), 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        return movePct.compareTo(threshold) > 0;
    }
}
