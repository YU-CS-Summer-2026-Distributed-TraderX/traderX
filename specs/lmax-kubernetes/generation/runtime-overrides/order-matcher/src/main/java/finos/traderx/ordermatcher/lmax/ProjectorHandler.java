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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Async read-model projector (FR-09B22..FR-09B24), NON-GATING COALESCING-TAP variant (step 1 of
 * the "take the DB off the hot path" plan).
 *
 * <p>The earlier decoupled variant moved the DB writes to a drain thread but still fed it through a
 * BOUNDED FIFO queue holding one entry per event. Under sustained load that queue filled, the
 * on-ring {@code onEvent} blocked on {@code queue.put}, the projector's ring sequence froze, and —
 * because an LMAX ring producer cannot reclaim a slot until its slowest consumer releases it — the
 * single-writer BLP stalled at the database write rate. The DB was reaching back through the output
 * ring and throttling an engine that runs millions/sec. This variant severs that path:
 *
 * <ul>
 *   <li><b>Non-gating.</b> {@link #onEvent} only converts the event to a detached row and drops it
 *       into an in-memory buffer under a nanosecond lock — never any DB, never a blocking enqueue.
 *       So the projector's ring sequence advances at ring speed and never drags the producer's
 *       gating sequence. {@link #enqueueBlocks()} stays 0; that is the proof the ring no longer
 *       blocks on the DB.</li>
 *   <li><b>Coalescing.</b> Positions and orders carry ABSOLUTE state, so only the latest value per
 *       key ever needs to reach the DB. They buffer into maps keyed by PK (last write wins), so a
 *       key's memory footprint is one row no matter how many intermediate updates churn through —
 *       buffering is bounded by the number of distinct open orders / positions, not by event
 *       volume. Many updates per key collapse to a single upsert, which also cuts DB work.</li>
 *   <li><b>Double-buffered swap.</b> The drain thread atomically swaps the live buffers out for
 *       fresh empties (lock held only for the reference swap), then writes the swapped-out copy to
 *       the DB with no lock held, in one transaction. Producer and writer never contend on the DB;
 *       the lock guards only O(1) map puts and the swap.</li>
 *   <li><b>Consistent prefix watermark.</b> Each swap captures {@code bufferedMaxSeq} (the highest
 *       sequence absorbed, advanced on every event incl. no-ops). {@code projectedSeq} advances to
 *       that target only after the flush commits, so it still marks exactly how far the durable
 *       projection has caught up (recovery resume point + {@code traderx_projector_lag_seq}).</li>
 * </ul>
 *
 * <p><b>Trades are the one exception.</b> They are append-only, so they cannot coalesce — the trade
 * buffer grows with volume until the DB drains it. It is unbounded here (never blocks), which is
 * fine for bench/demo bursts but is the firehose that step 3 (trades-as-async-archive / journal as
 * source of truth) removes from the DB hot path entirely. Orders and positions are already bounded.
 *
 * <p>Durability of accepted orders is unaffected — that lives on the INPUT ring (journaler +
 * replicator, gated before matching). This handler is only the queryable projection; schema and row
 * semantics still match 009. Live consumers already get real-time data over NATS, so the DB lagging
 * the engine (now pure, measurable buffer depth rather than engine backpressure) is invisible to
 * them.
 */
public final class ProjectorHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(ProjectorHandler.class);

    /** Idle poll when there is nothing to flush (under load the drain always has work, never sleeps). */
    private static final long IDLE_SLEEP_MS = 1;
    /** Back-off after a failed flush before retrying the same (idempotent) rows. */
    private static final long RETRY_SLEEP_MS = 50;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;
    private final SymbolTable symbols;
    private final int batchSize;
    private final int queueCapacity;
    private final HotPathMetrics metrics;

    // ----- live buffers (producer writes, drain swaps), all guarded by `lock` -------------------
    // Orders/positions coalesce by PK (last write wins): one row per key bounds memory regardless of
    // churn, and guarantees a key never repeats inside one multi-row upsert. Trades are append-only.
    private final Object lock = new Object();
    private Map<String, OrderRecord> orders = new HashMap<>();
    private List<Trade> trades = new ArrayList<>();
    private Map<PositionID, Position> positions = new HashMap<>();
    private long bufferedMaxSeq = -1;   // highest sequence absorbed (advanced on EVERY event)

    private final Thread drainThread;
    private volatile boolean running;

    private volatile long projectedSeq = -1;   // durable watermark: every event <= this is committed
    private volatile long pendingRows;          // buffered rows = staleness window, in rows
    private volatile long tradesPersisted;      // trades actually committed (real DB booking rate)
    private final LongAdder enqueueBlocks = new LongAdder(); // ring stalls on the DB; stays 0 by design now

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

    // ----- on-ring producer side (fast: convert + buffer under a nanosecond lock, no DB) ---------

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        // Convert OUTSIDE the lock: the ring slot is reused once we return, so the event's data must
        // be extracted into a detached row now; only the cheap buffer put happens under the lock.
        OrderRecord order = null;
        Trade trade = null;
        Position position = null;
        switch (e.kind) {
            case OutputEvent.KIND_ORDER_ACCEPTED, OutputEvent.KIND_ORDER_REJECTED,
                 OutputEvent.KIND_ORDER_PARTIALLY_FILLED, OutputEvent.KIND_ORDER_FILLED,
                 OutputEvent.KIND_ORDER_CANCELED -> {
                if (e.flags != 0) {   // flags==0 is a no-op update with nothing to persist
                    order = OrderSnapshot.fromEvent(e, symbols).toRecord();
                }
            }
            case OutputEvent.KIND_TRADE_BOOKED -> trade = toTrade(e);
            case OutputEvent.KIND_POSITION_UPDATED -> position = toPosition(e);
            default -> { /* KIND_ORDER_NOT_FOUND etc.: nothing to persist, still advance the watermark */ }
        }

        long buffered;
        synchronized (lock) {
            if (order != null) {
                orders.put(order.getOrderId(), order);   // coalesce by orderId, last write wins
            } else if (trade != null) {
                trades.add(trade);                        // append-only firehose (bounded by step 3)
            } else if (position != null) {
                positions.put(new PositionID(position.getAccountId(), position.getSecurity()), position);
            }
            bufferedMaxSeq = sequence;   // advance for EVERY event so the watermark tracks real progress
            buffered = orders.size() + trades.size() + positions.size();
        }
        pendingRows = buffered;
    }

    // ----- drain thread (slow: the DB writes, off the ring) --------------------------------------

    private void drainLoop() {
        while (running || hasBuffered()) {
            Map<String, OrderRecord> orderFlush;
            List<Trade> tradeFlush;
            Map<PositionID, Position> positionFlush;
            long target;
            synchronized (lock) {
                if (orders.isEmpty() && trades.isEmpty() && positions.isEmpty()) {
                    orderFlush = null;
                    tradeFlush = null;
                    positionFlush = null;
                    target = -1;
                } else {
                    // Atomic swap: take the whole live set, install fresh empties for the producer.
                    orderFlush = orders;
                    orders = new HashMap<>();
                    tradeFlush = trades;
                    trades = new ArrayList<>();
                    positionFlush = positions;
                    positions = new HashMap<>();
                    target = bufferedMaxSeq;
                }
            }

            if (orderFlush == null) {
                sleep(IDLE_SLEEP_MS);   // nothing buffered; brief idle (skipped entirely under load)
                continue;
            }

            int rows = orderFlush.size() + tradeFlush.size() + positionFlush.size();
            int tradeCount = tradeFlush.size();
            try {
                // One transaction for the whole flush: orders + trades + positions commit as a single
                // DB-visible unit on one connection, so the read-model always reflects a consistent
                // PREFIX of the sequenced stream. All three are blind multi-row upserts (no per-row
                // merge SELECT).
                txTemplate.executeWithoutResult(status -> {
                    if (!orderFlush.isEmpty()) {
                        insertOrdersBatch(orderFlush.values());
                    }
                    if (!tradeFlush.isEmpty()) {
                        insertTradesBatch(tradeFlush);
                    }
                    if (!positionFlush.isEmpty()) {
                        insertPositionsBatch(positionFlush.values());
                    }
                });
            } catch (Exception ex) {
                // Rolled back: nothing was written. Fold the failed batch back UNDER any newer updates
                // that arrived during the flush (newer wins for coalesced keys; trades are restored
                // ahead of newer trades), then back off and retry. Idempotent upserts make the retry
                // safe; the watermark does not advance, so no consumer sees a gap. No row is dropped.
                log.warn("Read-model projection failed at seq {} ({} rows): {}", target, rows, ex.getMessage());
                synchronized (lock) {
                    for (Map.Entry<String, OrderRecord> en : orderFlush.entrySet()) {
                        orders.putIfAbsent(en.getKey(), en.getValue());
                    }
                    for (Map.Entry<PositionID, Position> en : positionFlush.entrySet()) {
                        positions.putIfAbsent(en.getKey(), en.getValue());
                    }
                    if (!tradeFlush.isEmpty()) {
                        tradeFlush.addAll(trades);   // restored (older) trades first, then newer
                        trades = tradeFlush;
                    }
                    pendingRows = orders.size() + trades.size() + positions.size();
                }
                sleep(RETRY_SLEEP_MS);
                continue;
            }

            // Commit succeeded: count trades, advance the durable watermark — every event <= target is
            // now committed.
            tradesPersisted += tradeCount;
            metrics.recordProjectorBatch(rows);
            projectedSeq = target;
            synchronized (lock) {
                pendingRows = orders.size() + trades.size() + positions.size();
            }
        }
    }

    private boolean hasBuffered() {
        synchronized (lock) {
            return !orders.isEmpty() || !trades.isEmpty() || !positions.isEmpty();
        }
    }

    /** Sleep that treats interruption as the stop signal — the loop re-checks running/hasBuffered. */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            // shutdown: do not re-arm the interrupt (we would just re-throw on the next sleep); the
            // while-condition drains any remaining buffer, then exits because running is false.
        }
    }

    // Option 2 — trades are append-only, so skip JPA merge's per-row SELECT entirely: one multi-row
    // INSERT per chunk (a single DB round-trip), made idempotent for journal replay with
    // ON CONFLICT (id) DO NOTHING. The whole flush stays one DB-visible unit alongside the batched
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
            sql.append(" ON CONFLICT (id) DO NOTHING");
            jdbcTemplate.update(sql.toString(), args);
        }
    }

    // Positions and orders are absolute state snapshots keyed by their PK, so each read-model write
    // is a blind multi-row upsert: overwrite the row with the event's values and never SELECT it
    // first (what JPA merge did per row). Each buffer is already deduped to one row per key, so a key
    // never repeats inside one statement. Postgres EXCLUDED.col references the would-be-inserted value
    // in the ON CONFLICT DO UPDATE clause.
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
            sql.append(" ON CONFLICT (accountid, security) DO UPDATE SET quantity=EXCLUDED.quantity,"
                + " averagecostbasis=EXCLUDED.averagecostbasis, updated=EXCLUDED.updated");
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
            sql.append(" ON CONFLICT (orderid) DO UPDATE SET status=EXCLUDED.status,"
                + " remainingquantity=EXCLUDED.remainingquantity, updatedat=EXCLUDED.updatedat,"
                + " lastexecutionprice=EXCLUDED.lastexecutionprice, lastfillquantity=EXCLUDED.lastfillquantity");
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

    /** Trades committed to the DB since start — the real booking counter. */
    public long tradesPersisted() {
        return tradesPersisted;
    }

    /** Current buffered row count (orders + trades + positions not yet committed) = staleness depth. */
    public long queueDepth() {
        return pendingRows;
    }

    /** Advisory only now: the coalescing buffers are unbounded (the ring never blocks on the DB). */
    public long queueCapacity() {
        return queueCapacity;
    }

    /** Times the on-ring handler had to block on the DB. Stays 0 by design — the proof of non-gating. */
    public long enqueueBlocks() {
        return enqueueBlocks.sum();
    }
}
