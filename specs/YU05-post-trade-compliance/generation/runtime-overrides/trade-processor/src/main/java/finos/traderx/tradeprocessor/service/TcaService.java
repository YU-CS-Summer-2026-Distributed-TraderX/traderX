package finos.traderx.tradeprocessor.service;

import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * YU05 (post-trade-compliance, ADR-024, FR-PTC30-32): execution-quality computation for a single
 * trade. Pure read-side (never on the order admission path, FR-PTC31) — reads the trade from
 * MariaDB and the benchmark price from {@link PriceHistoryStore}, both already-persisted/observed
 * data; never calls back into order-matcher.
 *
 * <p>Slice-1 simplification: this system books a trade synchronously with its originating order
 * (no separately-tracked "order arrival" timestamp reaches trade-processor), so the trade's own
 * {@code created} timestamp stands in for arrival time. Benchmark is TWAP over the {@code
 * tca.window-minutes} preceding execution; VWAP is deferred (FR-PTC32) — the synthetic price feed
 * carries no per-tick volume to weight by. Both are computed the same way once the professor's
 * TAQ dataset (real trades + volume) is available, without touching this class's contract.
 */
@Service
public class TcaService {
    private final TradeRepository tradeRepository;
    private final PriceHistoryStore priceHistory;
    private final long windowMillis;

    public TcaService(TradeRepository tradeRepository, PriceHistoryStore priceHistory,
                      @Value("${tca.window-minutes:5}") long windowMinutes) {
        this.tradeRepository = tradeRepository;
        this.priceHistory = priceHistory;
        this.windowMillis = windowMinutes * 60_000L;
    }

    public TcaReport computeForTrade(String tradeId) {
        Trade trade = tradeRepository.findById(tradeId)
            .orElseThrow(() -> new NoSuchElementException("unknown trade: " + tradeId));

        long execMillis = trade.getCreated().getTime();
        long fromMillis = execMillis - windowMillis;

        Optional<PriceHistoryStore.PriceSample> arrival = priceHistory.priceAtOrBefore(trade.getSecurity(), fromMillis);
        Optional<PriceHistoryStore.TwapResult> twap = priceHistory.twap(trade.getSecurity(), fromMillis, execMillis);

        BigDecimal benchmark = twap.map(PriceHistoryStore.TwapResult::twap)
            .or(() -> arrival.map(PriceHistoryStore.PriceSample::price))
            .orElse(null);

        BigDecimal slippageBps = null;
        if (benchmark != null && benchmark.signum() != 0) {
            BigDecimal diff = trade.getPrice().subtract(benchmark);
            // Positive slippage always means "worse than benchmark": a buy paying more, or a
            // sell receiving less.
            BigDecimal signedDiff = trade.getSide() == TradeSide.Sell ? diff.negate() : diff;
            slippageBps = signedDiff.divide(benchmark, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(10_000))
                .setScale(2, RoundingMode.HALF_UP);
        }

        return new TcaReport(
            trade.getId(),
            trade.getSecurity(),
            trade.getSide().name(),
            trade.getQuantity(),
            trade.getPrice(),
            benchmark,
            arrival.map(PriceHistoryStore.PriceSample::price).orElse(null),
            slippageBps,
            twap.map(PriceHistoryStore.TwapResult::sampleCount).orElse(0));
    }

    /**
     * {@code benchmarkPrice}/{@code slippageBps} are {@code null} when no price history covers
     * the trade's execution window (e.g. a security that never ticked, or a window predating any
     * recorded sample) — an honest "unknown," not a fabricated zero.
     */
    public record TcaReport(String tradeId, String security, String side, int quantity,
                             BigDecimal executionPrice, BigDecimal benchmarkPrice, BigDecimal arrivalPrice,
                             BigDecimal slippageBps, int benchmarkSampleCount) { }
}
