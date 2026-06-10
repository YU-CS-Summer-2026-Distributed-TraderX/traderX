package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lmax.disruptor.EventHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Async read-model projector (FR-09B22..FR-09B24): batch-writes order rows into the shared
 * `OrderBook` table off the acknowledgement path. The schema and writer semantics match
 * 009's inline JPA persistence; only the path changes. A slow or down database degrades to
 * projector lag (rows buffered and retried on the next batch), never to matching failure.
 */
public final class ProjectorHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(ProjectorHandler.class);
    private static final int MAX_BUFFERED_ROWS = 10_000;

    private final OrderRepository repository;
    private final SymbolTable symbols;
    private final int batchSize;
    private final List<OrderRecord> buffer = new ArrayList<>();
    private volatile long projectedSeq = -1;
    private volatile long pendingRows;

    public ProjectorHandler(OrderRepository repository, SymbolTable symbols, int batchSize) {
        this.repository = repository;
        this.symbols = symbols;
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        if (e.kind == OutputEvent.KIND_ORDER_UPDATE && e.flags != 0) {
            // flags==0 updates are republished unchanged state (no persistence in 009 either)
            buffer.add(OrderSnapshot.fromEvent(e, symbols).toRecord());
        }
        if (!buffer.isEmpty() && (endOfBatch || buffer.size() >= batchSize)) {
            flush(sequence);
        }
        pendingRows = buffer.size();
    }

    private void flush(long sequence) {
        try {
            repository.saveAll(buffer);
            buffer.clear();
            projectedSeq = sequence;
        } catch (Exception ex) {
            // DB unavailable: lag and catch up later (FR-09B24). Bound the buffer so a long
            // outage cannot exhaust memory; oldest rows drop first (they are superseded by
            // newer snapshots of the same orders in most cases).
            if (buffer.size() > MAX_BUFFERED_ROWS) {
                buffer.subList(0, buffer.size() - MAX_BUFFERED_ROWS).clear();
            }
            log.warn("Read-model projection failed at seq {} ({} rows buffered): {}", sequence,
                buffer.size(), ex.getMessage());
        }
    }

    public long projectedSeq() {
        return projectedSeq;
    }

    public long pendingRows() {
        return pendingRows;
    }
}
