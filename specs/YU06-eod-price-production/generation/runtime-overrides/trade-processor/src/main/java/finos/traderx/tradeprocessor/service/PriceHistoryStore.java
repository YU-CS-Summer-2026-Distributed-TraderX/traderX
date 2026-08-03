package finos.traderx.tradeprocessor.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * YU05 (post-trade-compliance, ADR-024, FR-PTC30-32): a bounded, per-ticker price history, fed by
 * {@link PriceTickHandler} from the same {@code pricing.<ticker>} NATS feed price-publisher
 * already broadcasts (JSON envelope; no new data source, no BLP hot-path involvement). This is
 * the "synthetic price-publisher history" ADR-024 names as slice 1's benchmark source — swapping
 * in real historical tick data later only means replacing what feeds {@link #record}, not this
 * class's read contract.
 *
 * <p>Low write frequency (each ticker updates roughly every 750-1500ms) makes a simple
 * synchronized deque correct and fast enough; no need for a lock-free structure here.
 *
 * <p>YU06 (eod-price-production) adds only {@link #tickers()} — a read accessor over the observed
 * tickers so EOD production can default its instrument universe to "everything the feed has seen"
 * (FR-EOD06). No change to the recording/read-benchmark contract YU05 relies on.
 */
@Component
public class PriceHistoryStore {

    public record PriceSample(BigDecimal price, long timestampMillis) { }

    public record TwapResult(BigDecimal twap, int sampleCount) { }

    private final ConcurrentHashMap<String, Deque<PriceSample>> byTicker = new ConcurrentHashMap<>();
    private final int capacityPerTicker;

    public PriceHistoryStore(@Value("${tca.price-history.capacity-per-ticker:10000}") int capacityPerTicker) {
        this.capacityPerTicker = capacityPerTicker;
    }

    public synchronized void record(String ticker, BigDecimal price, long timestampMillis) {
        Deque<PriceSample> samples = byTicker.computeIfAbsent(ticker, k -> new ArrayDeque<>());
        samples.addLast(new PriceSample(price, timestampMillis));
        while (samples.size() > capacityPerTicker) {
            samples.removeFirst();
        }
    }

    /** The most recent sample at or before {@code atMillis}, or empty if none exists that early. */
    public synchronized Optional<PriceSample> priceAtOrBefore(String ticker, long atMillis) {
        Deque<PriceSample> samples = byTicker.get(ticker);
        if (samples == null) {
            return Optional.empty();
        }
        PriceSample best = null;
        for (PriceSample s : samples) {
            if (s.timestampMillis() <= atMillis) {
                best = s;
            } else {
                break; // insertion order is time-ordered
            }
        }
        return Optional.ofNullable(best);
    }

    /** YU06 (FR-EOD06): the set of tickers observed so far — the default EOD instrument universe. */
    public synchronized Set<String> tickers() {
        return new HashSet<>(byTicker.keySet());
    }

    /** Time-weighted average price over samples in {@code [fromMillis, toMillis]]. */
    public synchronized Optional<TwapResult> twap(String ticker, long fromMillis, long toMillis) {
        Deque<PriceSample> samples = byTicker.get(ticker);
        if (samples == null) {
            return Optional.empty();
        }
        List<PriceSample> window = samples.stream()
            .filter(s -> s.timestampMillis() >= fromMillis && s.timestampMillis() <= toMillis)
            .collect(Collectors.toList());
        if (window.isEmpty()) {
            return Optional.empty();
        }
        if (window.size() == 1) {
            return Optional.of(new TwapResult(window.get(0).price(), 1));
        }
        BigDecimal weightedSum = BigDecimal.ZERO;
        long totalWeight = 0;
        for (int i = 0; i < window.size() - 1; i++) {
            PriceSample a = window.get(i);
            PriceSample b = window.get(i + 1);
            long weight = Math.max(1, b.timestampMillis() - a.timestampMillis());
            weightedSum = weightedSum.add(a.price().multiply(BigDecimal.valueOf(weight)));
            totalWeight += weight;
        }
        BigDecimal twap = weightedSum.divide(BigDecimal.valueOf(totalWeight), 6, RoundingMode.HALF_UP);
        return Optional.of(new TwapResult(twap, window.size()));
    }
}
