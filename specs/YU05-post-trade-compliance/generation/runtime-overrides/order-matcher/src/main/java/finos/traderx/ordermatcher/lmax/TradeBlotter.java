package finos.traderx.ordermatcher.lmax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * YU05 (post-trade-compliance, ADR-022): a bounded, replay-safe record of every booked trade,
 * keyed by the BLP's deterministic {@code tradeSeq}. Populated by {@link TradeBlotterHandler} on
 * the output-ring consumer thread (single writer, same threading model as every other output
 * handler); read concurrently from HTTP request threads via {@link
 * finos.traderx.ordermatcher.controller.ReconController}.
 *
 * <p>Unlike {@code BlpRiskState}/{@code SnapshotStore}, this is deliberately NOT part of the
 * snapshot/journal wire format — it only needs to be correct by the time reconciliation next
 * runs (always after recovery completes), not instantly on restart, so it is simply rebuilt by
 * replaying the journal through the existing output-ring handler chain (see research.md).</p>
 */
@Component
public final class TradeBlotter {

    /** One booked trade, exactly as reconciliation/settlement need to see it. */
    public record TradeRecord(String id, long tradeSeq, int accountId, String security,
                               String side, int quantity, BigDecimal price, long execTimeMillis) {}

    private final ConcurrentSkipListMap<Long, TradeRecord> byTradeSeq = new ConcurrentSkipListMap<>();
    private final int capacity;
    private final LongAdder evictions = new LongAdder();

    public TradeBlotter(@Value("${recon.blotter.capacity:500000}") int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /** Called only from the output-ring consumer thread (single writer). */
    public void record(TradeRecord trade) {
        byTradeSeq.put(trade.tradeSeq(), trade);
        while (byTradeSeq.size() > capacity) {
            Map.Entry<Long, TradeRecord> oldest = byTradeSeq.pollFirstEntry();
            if (oldest != null) {
                evictions.increment();
            }
        }
    }

    /** Ascending by tradeSeq, strictly greater than {@code sinceSeq}, capped at {@code limit}. */
    public List<TradeRecord> since(long sinceSeq, int limit) {
        List<TradeRecord> out = new ArrayList<>(Math.min(limit, 256));
        for (TradeRecord trade : byTradeSeq.tailMap(sinceSeq, false).values()) {
            if (out.size() >= limit) {
                break;
            }
            out.add(trade);
        }
        return out;
    }

    public int size() {
        return byTradeSeq.size();
    }

    public long evictionCount() {
        return evictions.sum();
    }
}
