package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.Position;
import finos.traderx.ordermatcher.model.PositionID;
import finos.traderx.ordermatcher.model.Trade;
import finos.traderx.ordermatcher.model.TradeSide;
import finos.traderx.ordermatcher.model.TradeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lmax.disruptor.EventHandler;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Async read-model projector (FR-09B22..FR-09B24), DECOUPLED variant (throughput experiment).
 *
 * <p>Originally this handler did the DB writes inline on its output-ring consumer thread. Because
 * an LMAX ring producer cannot reclaim a slot until the slowest consumer releases it, the slow
 * per-row JPA writes (~1k rows/s) gated the entire single-writer engine: under sustained load the
 * output ring filled, the BLP stalled, and gateway acks timed out. The fix here unbinds the hot
 * path from the database:
 *
 * <ul>
 *   <li>The on-ring {@link #onEvent} only converts the event to a detached JPA entity and ENQUEUES
 *       it into a bounded in-memory queue — O(1), no DB. So the projector's ring sequence advances
 *       at ring speed and never drags down the producer's gating sequence.</li>
 *   <li>A single {@code projector-drain} thread batches rows out of the queue and writes them to
 *       Postgres at whatever rate the DB sustains. The DB falling behind becomes pure queue depth
 *       (measurable staleness), not engine backpressure.</li>
 *   <li>The queue is BOUNDED. If the DB stays slower than ingress long enough to fill it, the
 *       on-ring enqueue blocks (counted as a high-water event) — degrading to the old bounded
 *       backpressure rather than unbounded memory growth or dropped rows. No data is lost and the
 *       DB always reflects a consistent prefix (FIFO drain), just a staler one.</li>
 *   <li>The {@code projectedSeq} watermark advances only after a successful commit, so it marks
 *       exactly how far the durable projection has caught up (recovery resume point + the metric
 *       behind {@code traderx_projector_lag_seq}).</li>
 * </ul>
 *
 * Durability of accepted orders is unaffected — that lives on the INPUT ring (journaler +
 * replicator, gated before matching). This handler is only the queryable projection. Schema and
 * row semantics still match 009; only the writer thread changed.
 */
public final class ProjectorHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(ProjectorHandler.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;
    private final SymbolTable symbols;
    private final int batchSize;
    private final HotPathMetrics metrics;

    // One unit of pending work; exactly one of order/trade/position is non-null. Carries its ring
    // sequence so the drain thread can advance the durable watermark after a successful commit.
    private record ProjectionItem(long sequence, OrderRecord order, Trade trade, Position position) {}

    private final BlockingQueue<ProjectionItem> queue;
    private final int queueCapacity;
    private final Thread drainThread;
    private volatile boolean running;

    // Drain-thread-only state (single consumer): the in-flight flush batch.
    // Orders are deduped by orderId (last write wins): one order can transition
    // NEW->PARTIALLY_FILLED->FILLED inside a single flush window, and only its latest snapshot needs
    // to reach the DB. One row per key also guarantees the multi-row upsert never carries a duplicate
    // key within one INSERT (mirrors how positionBuffer already dedupes by PositionID).
    private final Map<String, OrderRecord> orderBuffer = new LinkedHashMap<>();
    private final List<Trade> tradeBuffer = new ArrayList<>();
    private final Map<PositionID, Position> positionBuffer = new LinkedHashMap<>();
    private long pendingSeq = -1;
    private boolean dbDown = false;

    private volatile long projectedSeq = -1;   // durable watermark: every event <= this is committed
    private volatile long pendingRows;          // queued + buffered rows = staleness window, in rows
    private volatile long tradesPersisted;      // trades actually committed (real DB booking rate)
    private final LongAdder enqueueBlocks = new LongAdder(); // times the bounded queue was full (ring stalled)

    public ProjectorHandler(JdbcTemplate jdbcTemplate, SymbolTable symbols, int batchSize,
                            int queueCapacity, HotPathMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        // One transaction manager over the same DataSource the JdbcTemplate uses, so the whole flush
        // (orders + trades + positions) runs on a single connection and commits exactly once.
        this.txTemplate = new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        this.symbols = symbols;
        this.batchSize = Math.max(1, batchSize);
        this.queueCapacity = Math.max(1024, queueCapacity);
        this.metrics = metrics;
        this.queue = new LinkedBlockingQueue<>(this.queueCapacity);
        this.drainThread = new Thread(this::drainLoop, "projector-drain");
        this.drainThread.setDaemon(true);
    }

    public void start() {
        running = true;
        drainThread.start();
    }

    public void stop() {
        running = false;
        drainThread.interrupt();
        try {
            drainThread.join(TimeUnit.SECONDS.toMillis(15));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ----- on-ring producer side (fast: convert + enqueue, no DB) ----------------------------

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        ProjectionItem item = toItem(e, sequence);
        if (item == null) {
            return;   // KIND_ORDER_NOT_FOUND or a flags==0 no-op update: nothing to persist
        }
        if (!queue.offer(item)) {
            // Bounded queue full: the DB has been slower than ingress for too long. Block here
            // (counted) — this is the deliberate backpressure fallback, never a dropped row.
            enqueueBlocks.increment();
            try {
                queue.put(item);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        pendingRows = queue.size();
    }

    private ProjectionItem toItem(OutputEvent e, long sequence) {
        switch (e.kind) {
            case OutputEvent.KIND_ORDER_ACCEPTED, OutputEvent.KIND_ORDER_REJECTED,
                 OutputEvent.KIND_ORDER_PARTIALLY_FILLED, OutputEvent.KIND_ORDER_FILLED,
                 OutputEvent.KIND_ORDER_CANCELED -> {
                if (e.flags != 0) {
                    return new ProjectionItem(sequence, OrderSnapshot.fromEvent(e, symbols).toRecord(), null, null);
                }
                return null;
            }
            case OutputEvent.KIND_TRADE_BOOKED -> {
                return new ProjectionItem(sequence, null, toTrade(e), null);
            }
            case OutputEvent.KIND_POSITION_UPDATED -> {
                return new ProjectionItem(sequence, null, null, toPosition(e));
            }
            default -> {
                return null;
            }
        }
    }

    // ----- drain thread (slow: the DB writes) ------------------------------------------------

    private void drainLoop() {
        final List<ProjectionItem> drained = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty() || hasBuffered()) {
            try {
                // While the DB is healthy, top the batch up from the queue. While it is down, stop
                // pulling (keep the batch small and bounded) and just retry the failed flush — the
                // queue then absorbs the backlog and provides the backpressure.
                if (!dbDown) {
                    int room = Math.max(1, batchSize - bufferedCount());
                    drained.clear();
                    queue.drainTo(drained, room);
                    if (drained.isEmpty() && !hasBuffered()) {
                        ProjectionItem item = queue.poll(50, TimeUnit.MILLISECONDS);
                        if (item == null) {
                            continue;
                        }
                        drained.add(item);
                    }
                    for (ProjectionItem it : drained) {
                        route(it);
                    }
                }
                boolean flushNow = dbDown || bufferedCount() >= batchSize || queue.isEmpty() || !running;
                if (hasBuffered() && flushNow) {
                    if (flush(pendingSeq)) {
                        dbDown = false;
                    } else {
                        dbDown = true;
                        Thread.sleep(50);   // back off, then retry the same rows (no data loss)
                    }
                }
                pendingRows = queue.size() + bufferedCount();
            } catch (InterruptedException ex) {
                if (!running) {
                    break;
                }
                Thread.currentThread().interrupt();
            }
        }
        if (hasBuffered()) {
            flush(pendingSeq);   // best-effort final drain on shutdown
        }
    }

    private void route(ProjectionItem it) {
        if (it.order() != null) {
            // Dedupe by orderId within the flush: only the latest order snapshot is written.
            orderBuffer.put(it.order().getOrderId(), it.order());
        } else if (it.trade() != null) {
            tradeBuffer.add(it.trade());
        } else if (it.position() != null) {
            // Dedupe within the flush: only the latest net quantity per (account, security) is written.
            positionBuffer.put(new PositionID(it.position().getAccountId(), it.position().getSecurity()), it.position());
        }
        if (it.sequence() > pendingSeq) {
            pendingSeq = it.sequence();
        }
    }

    private int bufferedCount() {
        return orderBuffer.size() + tradeBuffer.size() + positionBuffer.size();
    }

    private boolean hasBuffered() {
        return bufferedCount() > 0;
    }

    /** Returns true on a successful commit (buffers cleared, watermark advanced); false leaves them to retry. */
    private boolean flush(long sequence) {
        int rows = bufferedCount();
        int trades = tradeBuffer.size();
        try {
            // One transaction for the whole flush: orders + trades + positions for this sequence
            // range commit as a single DB-visible unit on one connection, so the read-model always
            // reflects a consistent PREFIX of the sequenced stream (never trades without the matching
            // positions). All three are blind multi-row upserts -> no per-row merge SELECT.
            txTemplate.executeWithoutResult(status -> {
                if (!orderBuffer.isEmpty()) {
                    insertOrdersBatch(orderBuffer.values());
                }
                if (!tradeBuffer.isEmpty()) {
                    insertTradesBatch(tradeBuffer);
                }
                if (!positionBuffer.isEmpty()) {
                    insertPositionsBatch(positionBuffer.values());
                }
            });
        } catch (Exception ex) {
            // Transaction rolled back: nothing was written. Keep every buffer intact and let the
            // caller back off and retry the same rows (FR-09B24). Memory stays bounded because we
            // stop draining the queue while down, and the queue itself is bounded (-> on-ring
            // backpressure). Nothing is dropped; the upserts make the retry idempotent.
            log.warn("Read-model projection failed at seq {} ({} rows buffered, {} queued): {}",
                sequence, bufferedCount(), queue.size(), ex.getMessage());
            return false;
        }
        // Commit succeeded: count trades (real booking rate), clear the buffers, advance the durable
        // watermark — every event <= this sequence is now committed.
        tradesPersisted += trades;
        orderBuffer.clear();
        tradeBuffer.clear();
        positionBuffer.clear();
        metrics.recordProjectorBatch(rows);
        projectedSeq = sequence;
        pendingSeq = -1;
        return true;
    }

    // Option 2 — trades are append-only, so skip JPA merge's per-row SELECT entirely: one multi-row
    // INSERT per chunk (a single DB round-trip), made idempotent for journal replay with
    // ON DUPLICATE KEY UPDATE id=id (a no-op on the PK). The whole flush stays one DB-visible unit alongside the batched
    // order/position writes (option 1).
    private static final int TRADE_INSERT_CHUNK = 500;
    private static final String TRADE_COLS =
        "(id, accountid, security, side, state, quantity, price, created, updated)";

    private void insertTradesBatch(List<Trade> trades) {
        for (int start = 0; start < trades.size(); start += TRADE_INSERT_CHUNK) {
            int end = Math.min(start + TRADE_INSERT_CHUNK, trades.size());
            StringBuilder sql = new StringBuilder(64 + (end - start) * 20)
                .append("INSERT INTO trades ").append(TRADE_COLS).append(" VALUES ");
            Object[] args = new Object[(end - start) * 9];
            int a = 0;
            for (int i = start; i < end; i++) {
                sql.append(i > start ? ",(?,?,?,?,?,?,?,?,?)" : "(?,?,?,?,?,?,?,?,?)");
                Trade t = trades.get(i);
                args[a++] = t.getId();
                args[a++] = t.getAccountId();
                args[a++] = t.getSecurity();
                args[a++] = t.getSide() == null ? null : t.getSide().name();
                args[a++] = t.getState() == null ? null : t.getState().name();
                args[a++] = t.getQuantity();
                args[a++] = t.getPrice();
                args[a++] = t.getCreated() == null ? null : new Timestamp(t.getCreated().getTime());
                args[a++] = t.getUpdated() == null ? null : new Timestamp(t.getUpdated().getTime());
            }
            sql.append(" ON DUPLICATE KEY UPDATE id=id");
            jdbcTemplate.update(sql.toString(), args);
        }
    }

    // Positions and orders are absolute state snapshots keyed by their PK, so each read-model write
    // is a blind multi-row upsert: overwrite the row with the event's values and never SELECT it
    // first (what JPA merge did per row). Each buffer is already deduped to one row per key (see
    // route()), so a key never repeats inside one statement. MariaDB's VALUES(col) references the
    // would-be-inserted value in the ON DUPLICATE KEY UPDATE clause.
    private static final int UPSERT_CHUNK = 500;

    private static final String POSITION_COLS = "(accountid, security, quantity, averagecostbasis, updated)";

    private void insertPositionsBatch(Collection<Position> positionsColl) {
        List<Position> positions = new ArrayList<>(positionsColl);
        for (int start = 0; start < positions.size(); start += UPSERT_CHUNK) {
            int end = Math.min(start + UPSERT_CHUNK, positions.size());
            StringBuilder sql = new StringBuilder(96 + (end - start) * 14)
                .append("INSERT INTO positions ").append(POSITION_COLS).append(" VALUES ");
            Object[] args = new Object[(end - start) * 5];
            int a = 0;
            for (int i = start; i < end; i++) {
                sql.append(i > start ? ",(?,?,?,?,?)" : "(?,?,?,?,?)");
                Position p = positions.get(i);
                args[a++] = p.getAccountId();
                args[a++] = p.getSecurity();
                args[a++] = p.getQuantity();
                args[a++] = p.getAverageCostBasis();
                args[a++] = p.getUpdated() == null ? null : new Timestamp(p.getUpdated().getTime());
            }
            sql.append(" ON DUPLICATE KEY UPDATE quantity=VALUES(quantity),"
                + " averagecostbasis=VALUES(averagecostbasis), updated=VALUES(updated)");
            jdbcTemplate.update(sql.toString(), args);
        }
    }

    // Only the mutable columns are refreshed on conflict; orderid (PK), accountid, security, side,
    // quantity and createdat are fixed at creation and stay out of the UPDATE clause.
    private static final String ORDER_COLS = "(orderid, accountid, security, side, quantity,"
        + " remainingquantity, limitprice, status, createdat, updatedat, lastexecutionprice, lastfillquantity)";

    private void insertOrdersBatch(Collection<OrderRecord> ordersColl) {
        List<OrderRecord> orders = new ArrayList<>(ordersColl);
        for (int start = 0; start < orders.size(); start += UPSERT_CHUNK) {
            int end = Math.min(start + UPSERT_CHUNK, orders.size());
            StringBuilder sql = new StringBuilder(176 + (end - start) * 28)
                .append("INSERT INTO orderbook ").append(ORDER_COLS).append(" VALUES ");
            Object[] args = new Object[(end - start) * 12];
            int a = 0;
            for (int i = start; i < end; i++) {
                sql.append(i > start ? ",(?,?,?,?,?,?,?,?,?,?,?,?)" : "(?,?,?,?,?,?,?,?,?,?,?,?)");
                OrderRecord o = orders.get(i);
                args[a++] = o.getOrderId();
                args[a++] = o.getAccountId();
                args[a++] = o.getSecurity();
                args[a++] = o.getSide() == null ? null : o.getSide().name();
                args[a++] = o.getQuantity();
                args[a++] = o.getRemainingQuantity();
                args[a++] = o.getLimitPrice();
                args[a++] = o.getStatus() == null ? null : o.getStatus().name();
                args[a++] = o.getCreatedAt() == null ? null : new Timestamp(o.getCreatedAt().toEpochMilli());
                args[a++] = o.getUpdatedAt() == null ? null : new Timestamp(o.getUpdatedAt().toEpochMilli());
                args[a++] = o.getLastExecutionPrice();
                args[a++] = o.getLastFillQuantity();
            }
            sql.append(" ON DUPLICATE KEY UPDATE status=VALUES(status),"
                + " remainingquantity=VALUES(remainingquantity), updatedat=VALUES(updatedat),"
                + " lastexecutionprice=VALUES(lastexecutionprice), lastfillquantity=VALUES(lastfillquantity)");
            jdbcTemplate.update(sql.toString(), args);
        }
    }

    private Trade toTrade(OutputEvent e) {
        Trade trade = new Trade();
        trade.setId(OrderSnapshot.tradeIdFor(e.tradeSeq));
        trade.setAccountId(e.accountId);
        trade.setSecurity(symbols.tickerFor(e.securityId));
        trade.setSide(e.side == InputEvent.SIDE_BUY ? TradeSide.Buy : TradeSide.Sell);
        trade.setQuantity(e.tradeQty);
        trade.setPrice(Px.toDecimalOrZero(e.tradePx));   // stamped execution price (0.000 if no tick), FR-09B40
        trade.setState(TradeState.Settled);
        Date when = new Date(e.updatedAtMillis);   // event-carried time (deterministic), not wall clock
        trade.setCreated(when);
        trade.setUpdated(when);
        return trade;
    }

    private Position toPosition(OutputEvent e) {
        Position position = new Position();
        position.setAccountId(e.accountId);
        position.setSecurity(symbols.tickerFor(e.securityId));
        position.setQuantity(e.positionQty);
        position.setAverageCostBasis(Px.toDecimalOrZero(e.positionAvgCostTicks));   // weighted cost basis, FR-09B40
        position.setUpdated(new Date(e.updatedAtMillis));
        return position;
    }

    // ----- telemetry (read by the scrape thread) ---------------------------------------------

    public long projectedSeq() {
        return projectedSeq;
    }

    public long pendingRows() {
        return pendingRows;
    }

    /** Trades committed to the DB since start — the real (Postgres-bound) booking counter. */
    public long tradesPersisted() {
        return tradesPersisted;
    }

    /** Current decoupling-queue depth (rows enqueued, not yet handed to a flush). */
    public long queueDepth() {
        return queue.size();
    }

    public long queueCapacity() {
        return queueCapacity;
    }

    /** Times the bounded queue was full and the on-ring handler had to block (ring backpressure). */
    public long enqueueBlocks() {
        return enqueueBlocks.sum();
    }
}
