package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.Trade;
import finos.traderx.ordermatcher.model.TradeSide;
import finos.traderx.ordermatcher.model.TradeState;
import finos.traderx.ordermatcher.repository.OrderRepository;
import finos.traderx.ordermatcher.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lmax.disruptor.EventHandler;

import java.sql.Timestamp;
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
 * single-writer BLP stalled at MariaDB's write rate. The DB was reaching back through the output
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
 *   <li><b>Retry without loss (FR-09B24).</b> A rolled-back flush is folded back UNDER whatever
 *       arrived during it and retried after a short back-off. That is what the older drain thread's
 *       {@code dbDown} flag bought — a DB outage costs staleness, never a dropped row — without a
 *       second stalled-DB code path.</li>
 * </ul>
 *
 * <p><b>Trades are the one exception.</b> They are append-only, so they cannot coalesce — the trade
 * buffer grows with volume until the DB drains it. It is unbounded here (never blocks), which is
 * fine for bench/demo bursts but is the firehose that step 3 (trades-as-async-archive / journal as
 * source of truth) removes from the DB hot path entirely. Orders and positions are already bounded.
 *
 * <p>All three tables are written as blind multi-row upserts in MariaDB dialect
 * ({@code ON DUPLICATE KEY UPDATE}) straight through the {@link JdbcTemplate}; the JPA repositories
 * are no longer on this path — their per-row merge SELECT is exactly the cost this variant removes.
 *
 * <p>Durability of accepted orders is unaffected — that lives on the INPUT ring (journaler +
 * replicator, gated before matching). This handler is only the queryable projection; schema and row
 * semantics still match 009. Live consumers already get real-time data over NATS, so the DB lagging
 * the engine (now pure, measurable buffer depth rather than engine backpressure) is invisible to
 * them. Journal replay writes through here too — the projection is what a replay rebuilds, so
 * unlike the NATS-facing handlers this one is deliberately NOT gated on {@code isReplaying()}.
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
    //
    // The buffers hold PRIMITIVE COLUMNS, not entity objects, and the two buffer instances are
    // allocated once and swapped rather than replaced. That is what keeps onEvent allocation-free:
    // OrderRecord/Trade/Position and every derived value they carry — the id Strings, the BigDecimal
    // prices, the Instant/Timestamp stamps — are built at FLUSH time, off the ring thread, from these
    // columns. Buffering an entity per event instead costs ~900 bytes/event on the order path and is
    // what OutputHandlerAllocationGateTest's 512-byte budget exists to prevent.
    private final Object lock = new Object();
    private RowBuffer live;
    private RowBuffer spare;
    private long bufferedMaxSeq = -1;   // highest sequence absorbed (advanced on EVERY event)

    private final Thread drainThread;
    private volatile boolean running;

    private volatile long projectedSeq = -1;   // durable watermark: every event <= this is committed
    private volatile long pendingRows;          // buffered rows = staleness window, in rows
    private volatile long tradesPersisted;      // trades actually committed (real DB booking rate)
    private static final finos.traderx.ordermatcher.model.OrderStatus[] STATUSES =
        finos.traderx.ordermatcher.model.OrderStatus.values();
    private static final finos.traderx.ordermatcher.model.OrderSide[] SIDES =
        finos.traderx.ordermatcher.model.OrderSide.values();

    private final LongAdder enqueueBlocks = new LongAdder(); // ring stalls on the DB; stays 0 by design now

    public ProjectorHandler(JdbcTemplate jdbcTemplate, SymbolTable symbols, int batchSize,
                            int queueCapacity, HotPathMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        // One transaction manager over the same DataSource the JdbcTemplate uses, so the whole flush
        // (orders + trades + positions) runs on a single connection and commits exactly once. Null
        // is the no-DB harness below: it never starts the drain, so no manager is needed.
        this.txTemplate = jdbcTemplate == null ? null
            : new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        this.symbols = symbols;
        this.batchSize = Math.max(1, batchSize);
        this.queueCapacity = Math.max(1024, queueCapacity);
        this.metrics = metrics;
        // Sized per row kind, to the shape each one actually has. Orders and positions COALESCE, so
        // their depth is the number of distinct open orders / positions, not event volume — a fixed
        // starting capacity covers them. Only trades are append-only and grow with volume, so they
        // start at the batch this flush cycle exists to hold (capped: batchSize is Integer.MAX_VALUE
        // in the no-DB harness). Growth is amortised doubling and only happens when the DB is behind.
        int coalescedRows = 1024;
        int tradeRows = Math.max(1024, Math.min(this.batchSize, 8192));
        this.live = new RowBuffer(coalescedRows, tradeRows);
        this.spare = new RowBuffer(coalescedRows, tradeRows);
        this.drainThread = new Thread(this::drainLoop, "projector-drain");
        this.drainThread.setDaemon(true);
    }

    /**
     * No-DB harness constructor for the output-handler allocation gate, attribution and topology
     * benchmarks (ODL-05 / SC-NGC-04), which drive {@link #onEvent} with null repositories and no
     * JdbcTemplate and never {@link #start()} the drain. The repository arguments are inert — this
     * variant upserts every table through the JdbcTemplate — and stay in the signature only so
     * those gates keep compiling against the projector they were written for.
     */
    public ProjectorHandler(OrderRepository orderRepository, PositionRepository positionRepository,
                            JdbcTemplate jdbcTemplate, SymbolTable symbols, int batchSize,
                            HotPathMetrics metrics) {
        this(jdbcTemplate, symbols, batchSize, 0, metrics);   // capacity is advisory only now
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
        // The ring slot is reused once we return, so the event's data must be copied out now — but it
        // is copied as PRIMITIVES straight into the buffer's columns. Nothing is constructed here: no
        // row object, no id String, no BigDecimal, no Instant. Those are derived at flush, off this
        // thread. Coalescing keys are the event's own ints (orderRef; accountId+securityId), so even
        // the map keys cost nothing.
        long buffered;
        synchronized (lock) {
            switch (e.kind) {
                case OutputEvent.KIND_ORDER_ACCEPTED, OutputEvent.KIND_ORDER_REJECTED,
                     OutputEvent.KIND_ORDER_PARTIALLY_FILLED, OutputEvent.KIND_ORDER_FILLED,
                     OutputEvent.KIND_ORDER_CANCELED -> {
                    if (e.flags != 0) {   // flags==0 is a no-op update with nothing to persist
                        live.putOrder(e);   // coalesce by orderRef, last write wins
                    }
                }
                case OutputEvent.KIND_TRADE_BOOKED -> live.addTrade(e);
                case OutputEvent.KIND_POSITION_UPDATED -> live.putPosition(e);
                default -> { /* KIND_ORDER_NOT_FOUND etc.: nothing to persist, still advance the watermark */ }
            }
            bufferedMaxSeq = sequence;   // advance for EVERY event so the watermark tracks real progress
            buffered = live.rowCount();
        }
        pendingRows = buffered;
    }

    // ----- drain thread (slow: the DB writes, off the ring) --------------------------------------

    private void drainLoop() {
        while (running || hasBuffered()) {
            RowBuffer flush;
            long target;
            synchronized (lock) {
                if (live.isEmpty()) {
                    flush = null;
                    target = -1;
                } else {
                    // Atomic swap: the producer takes the (already empty) spare, we take the live set.
                    // Both instances are long-lived — swapping references, not allocating new buffers.
                    flush = live;
                    live = spare;
                    spare = flush;
                    target = bufferedMaxSeq;
                }
            }

            if (flush == null) {
                sleep(IDLE_SLEEP_MS);   // nothing buffered; brief idle (skipped entirely under load)
                continue;
            }

            int rows = flush.rowCount();
            int tradeCount = flush.tradeCount;
            try {
                // One transaction for the whole flush: orders + trades + positions commit as a single
                // DB-visible unit on one connection, so the read-model always reflects a consistent
                // PREFIX of the sequenced stream. All three are blind multi-row upserts (no per-row
                // merge SELECT), chunked so no single statement grows unbounded.
                txTemplate.executeWithoutResult(status -> {
                    if (flush.orderCount > 0) {
                        insertOrdersBatch(flush);
                    }
                    if (flush.tradeCount > 0) {
                        insertTradesBatch(flush);
                    }
                    if (flush.positionCount > 0) {
                        insertPositionsBatch(flush);
                    }
                });
            } catch (Exception ex) {
                // Rolled back: nothing was written. Fold the failed batch back UNDER any newer updates
                // that arrived during the flush (newer wins for coalesced keys; trades are restored
                // ahead of newer trades), then back off and retry. Idempotent upserts make the retry
                // safe; the watermark does not advance, so no consumer sees a gap. No row is dropped
                // (FR-09B24), and memory stays bounded for orders/positions because they coalesce.
                // Log the most specific cause, not just Spring's wrapper. The wrapper says
                // "bad SQL grammar" for a plain missing table, which cost real time here: the flush
                // was failing with Table "ORDERBOOK" not found and the message read like a dialect
                // problem. YU02's layer already carries this; YU01's did not.
                log.warn("Read-model projection failed at seq {} ({} rows): {} || cause: {}", target, rows,
                    ex.getMessage(), NestedExceptionUtils.getMostSpecificCause(ex).toString());
                synchronized (lock) {
                    // Same semantics as before the columns change: coalesced keys keep the NEWER value
                    // (fold-under, not overwrite), and restored trades go AHEAD of newer ones.
                    live.foldUnder(flush);
                    flush.clear();
                    pendingRows = live.rowCount();
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
                flush.clear();   // written and committed: reset for its next turn as the live buffer
                pendingRows = live.rowCount();
            }
        }
    }

    private boolean hasBuffered() {
        synchronized (lock) {
            return !live.isEmpty();
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
    // ON DUPLICATE KEY UPDATE id=id (a no-op on the PK). The whole flush stays one DB-visible unit alongside the batched
    // order/position writes (option 1).
    private static final int TRADE_INSERT_CHUNK = 500;
    private static final String TRADE_COLS =
        "(id, accountid, security, side, state, quantity, price, created, updated)";

    private void insertTradesBatch(RowBuffer b) {
        for (int start = 0; start < b.tradeCount; start += TRADE_INSERT_CHUNK) {
            int end = Math.min(start + TRADE_INSERT_CHUNK, b.tradeCount);
            StringBuilder sql = new StringBuilder(64 + (end - start) * 20)
                .append("INSERT INTO trades ").append(TRADE_COLS).append(" VALUES ");
            Object[] args = new Object[(end - start) * 9];
            int a = 0;
            for (int i = start; i < end; i++) {
                sql.append(i > start ? ",(?,?,?,?,?,?,?,?,?)" : "(?,?,?,?,?,?,?,?,?)");
                // Derived here, not per event: the id String, the BigDecimal price and the Timestamps
                // are the allocation the buffer exists to keep off the ring thread.
                args[a++] = OrderSnapshot.tradeIdFor(b.tSeq[i]);
                args[a++] = b.tAccountId[i];
                args[a++] = symbols.tickerFor(b.tSecurityId[i]);
                args[a++] = (b.tSide[i] == InputEvent.SIDE_BUY ? TradeSide.Buy : TradeSide.Sell).name();
                args[a++] = TradeState.Settled.name();
                args[a++] = b.tQty[i];
                args[a++] = Px.toDecimalOrZero(b.tPx[i]);   // stamped execution price (0.000 if no tick), FR-09B40
                Timestamp when = new Timestamp(b.tUpdatedAt[i]);   // event-carried time, not wall clock
                args[a++] = when;
                args[a++] = when;
            }
            sql.append(" ON DUPLICATE KEY UPDATE id=id");
            jdbcTemplate.update(sql.toString(), args);
        }
    }

    // Positions and orders are absolute state snapshots keyed by their PK, so each read-model write
    // is a blind multi-row upsert: overwrite the row with the event's values and never SELECT it
    // first (what JPA merge did per row). Each buffer is already deduped to one row per key, so a key
    // never repeats inside one statement. MariaDB's VALUES(col) references the would-be-inserted value
    // in the ON DUPLICATE KEY UPDATE clause.
    private static final int UPSERT_CHUNK = 500;

    private static final String POSITION_COLS = "(accountid, security, quantity, averagecostbasis, updated)";

    private void insertPositionsBatch(RowBuffer b) {
        for (int start = 0; start < b.positionCount; start += UPSERT_CHUNK) {
            int end = Math.min(start + UPSERT_CHUNK, b.positionCount);
            StringBuilder sql = new StringBuilder(96 + (end - start) * 14)
                .append("INSERT INTO positions ").append(POSITION_COLS).append(" VALUES ");
            Object[] args = new Object[(end - start) * 5];
            int a = 0;
            for (int i = start; i < end; i++) {
                sql.append(i > start ? ",(?,?,?,?,?)" : "(?,?,?,?,?)");
                args[a++] = b.pAccountId[i];
                args[a++] = symbols.tickerFor(b.pSecurityId[i]);
                args[a++] = b.pQty[i];
                args[a++] = Px.toDecimalOrZero(b.pAvgCostTicks[i]);   // weighted cost basis, FR-09B40
                args[a++] = new Timestamp(b.pUpdatedAt[i]);
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

    private void insertOrdersBatch(RowBuffer b) {
        for (int start = 0; start < b.orderCount; start += UPSERT_CHUNK) {
            int end = Math.min(start + UPSERT_CHUNK, b.orderCount);
            StringBuilder sql = new StringBuilder(176 + (end - start) * 28)
                .append("INSERT INTO orderbook ").append(ORDER_COLS).append(" VALUES ");
            Object[] args = new Object[(end - start) * 12];
            int a = 0;
            for (int i = start; i < end; i++) {
                sql.append(i > start ? ",(?,?,?,?,?,?,?,?,?,?,?,?)" : "(?,?,?,?,?,?,?,?,?,?,?,?)");
                // Same column values OrderSnapshot.toRecord() produced, derived from the buffered
                // primitives at flush time instead of from an OrderRecord built per event.
                args[a++] = OrderSnapshot.orderIdFor(b.oRef[i]);
                args[a++] = b.oAccountId[i];
                args[a++] = symbols.tickerFor(b.oSecurityId[i]);
                args[a++] = SIDES[b.oSide[i]].name();
                args[a++] = b.oQty[i];
                args[a++] = b.oRemainingQty[i];
                args[a++] = Px.toBigDecimal(b.oLimitPx[i]);
                args[a++] = STATUSES[b.oStatus[i]].name();
                args[a++] = new Timestamp(b.oCreatedAt[i]);
                args[a++] = new Timestamp(b.oUpdatedAt[i]);
                args[a++] = b.oLastExecPx[i] == Px.NONE ? null : Px.toBigDecimal(b.oLastExecPx[i]);
                args[a++] = b.oLastFillQty[i] == 0 ? null : b.oLastFillQty[i];
            }
            sql.append(" ON DUPLICATE KEY UPDATE status=VALUES(status),"
                + " remainingquantity=VALUES(remainingquantity), updatedat=VALUES(updatedat),"
                + " lastexecutionprice=VALUES(lastexecutionprice), lastfillquantity=VALUES(lastfillquantity)");
            jdbcTemplate.update(sql.toString(), args);
        }
    }



    // ----- telemetry (read by the scrape thread) ---------------------------------------------

    public long projectedSeq() {
        return projectedSeq;
    }

    public long pendingRows() {
        return pendingRows;
    }

    /** Trades committed to the DB since start — the real (MariaDB-bound) booking counter. */
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

    /**
     * One flush cycle's rows, held as primitive columns rather than entity objects.
     *
     * <p>This exists so {@link ProjectorHandler#onEvent} allocates nothing. Buffering an
     * {@code OrderRecord}/{@code Trade}/{@code Position} per event costs an object plus every derived
     * value it carries — the id String, the {@code BigDecimal} price, the {@code Instant} stamps —
     * which measured ~900/240/216 bytes per event on the three paths against a 512-byte-per-1000
     * budget. Columns move that construction to flush time, on the drain thread.
     *
     * <p>Two instances are allocated up front and swapped by the drain, so a flush cycle allocates
     * nothing either. Arrays grow by doubling and are never shrunk, so growth is a warmup cost that
     * amortises to zero; steady state reuses the same arrays forever.
     *
     * <p>Coalescing uses the event's own integer keys — {@code orderRef} for orders, the
     * {@code (accountId, securityId)} pair packed into a long for positions — through small
     * open-addressed maps, so even the map keys cost nothing. Not thread-safe: every access is under
     * the handler's {@code lock}.
     */
    private static final class RowBuffer {
        private static final int EMPTY = -1;

        // orders, coalesced by orderRef (last write wins)
        int[] oRef, oAccountId, oSecurityId, oQty, oRemainingQty, oLastFillQty;
        byte[] oSide, oStatus;
        long[] oLimitPx, oCreatedAt, oUpdatedAt, oLastExecPx;
        int orderCount;
        private int[] orderKeys, orderSlots;
        private int orderMask;

        // trades, append-only (cannot coalesce: every fill is its own row)
        long[] tSeq, tPx, tUpdatedAt;
        int[] tAccountId, tSecurityId, tQty;
        byte[] tSide;
        int tradeCount;

        // positions, coalesced by (accountId, securityId)
        int[] pAccountId, pSecurityId, pQty;
        long[] pAvgCostTicks, pUpdatedAt;
        int positionCount;
        private long[] positionKeys;
        private int[] positionSlots;
        private int positionMask;

        RowBuffer(int coalescedRows, int tradeRows) {
            growOrders(coalescedRows);
            growTrades(tradeRows);
            growPositions(coalescedRows);
        }

        int rowCount() {
            return orderCount + tradeCount + positionCount;
        }

        boolean isEmpty() {
            return orderCount == 0 && tradeCount == 0 && positionCount == 0;
        }

        void putOrder(OutputEvent e) {
            int i = orderIndexOf(e.orderRef);
            if (i < 0) {
                if (orderCount == oRef.length) {
                    growOrders(oRef.length << 1);
                }
                i = orderCount++;
                indexOrder(e.orderRef, i);
            }
            oRef[i] = e.orderRef;
            oAccountId[i] = e.accountId;
            oSecurityId[i] = e.securityId;
            oSide[i] = e.side;
            oQty[i] = e.quantity;
            oRemainingQty[i] = e.remainingQty;
            oLimitPx[i] = e.limitPx;
            oStatus[i] = e.status;
            oCreatedAt[i] = e.createdAtMillis;
            oUpdatedAt[i] = e.updatedAtMillis;
            oLastExecPx[i] = e.lastExecPx;
            oLastFillQty[i] = e.lastFillQty;
        }

        void addTrade(OutputEvent e) {
            if (tradeCount == tSeq.length) {
                growTrades(tSeq.length << 1);
            }
            int i = tradeCount++;
            tSeq[i] = e.tradeSeq;
            tAccountId[i] = e.accountId;
            tSecurityId[i] = e.securityId;
            tSide[i] = e.side;
            tQty[i] = e.tradeQty;
            tPx[i] = e.tradePx;
            tUpdatedAt[i] = e.updatedAtMillis;
        }

        void putPosition(OutputEvent e) {
            long key = positionKey(e.accountId, e.securityId);
            int i = positionIndexOf(key);
            if (i < 0) {
                if (positionCount == pAccountId.length) {
                    growPositions(pAccountId.length << 1);
                }
                i = positionCount++;
                indexPosition(key, i);
            }
            pAccountId[i] = e.accountId;
            pSecurityId[i] = e.securityId;
            pQty[i] = e.positionQty;
            pAvgCostTicks[i] = e.positionAvgCostTicks;
            pUpdatedAt[i] = e.updatedAtMillis;
        }

        /**
         * Restore a rolled-back flush beneath whatever arrived while it was in flight: a coalesced key
         * present here already holds the NEWER value and is left alone, and restored trades are placed
         * AHEAD of newer ones so the append order still matches sequence order.
         */
        void foldUnder(RowBuffer older) {
            for (int i = 0; i < older.orderCount; i++) {
                if (orderIndexOf(older.oRef[i]) >= 0) {
                    continue;   // newer update already buffered for this order
                }
                if (orderCount == oRef.length) {
                    growOrders(oRef.length << 1);
                }
                int j = orderCount++;
                indexOrder(older.oRef[i], j);
                oRef[j] = older.oRef[i];
                oAccountId[j] = older.oAccountId[i];
                oSecurityId[j] = older.oSecurityId[i];
                oSide[j] = older.oSide[i];
                oQty[j] = older.oQty[i];
                oRemainingQty[j] = older.oRemainingQty[i];
                oLimitPx[j] = older.oLimitPx[i];
                oStatus[j] = older.oStatus[i];
                oCreatedAt[j] = older.oCreatedAt[i];
                oUpdatedAt[j] = older.oUpdatedAt[i];
                oLastExecPx[j] = older.oLastExecPx[i];
                oLastFillQty[j] = older.oLastFillQty[i];
            }
            for (int i = 0; i < older.positionCount; i++) {
                long key = positionKey(older.pAccountId[i], older.pSecurityId[i]);
                if (positionIndexOf(key) >= 0) {
                    continue;
                }
                if (positionCount == pAccountId.length) {
                    growPositions(pAccountId.length << 1);
                }
                int j = positionCount++;
                indexPosition(key, j);
                pAccountId[j] = older.pAccountId[i];
                pSecurityId[j] = older.pSecurityId[i];
                pQty[j] = older.pQty[i];
                pAvgCostTicks[j] = older.pAvgCostTicks[i];
                pUpdatedAt[j] = older.pUpdatedAt[i];
            }
            if (older.tradeCount > 0) {
                int needed = older.tradeCount + tradeCount;
                if (needed > tSeq.length) {
                    int cap = tSeq.length;
                    while (cap < needed) {
                        cap <<= 1;
                    }
                    growTrades(cap);
                }
                int shift = older.tradeCount;
                System.arraycopy(tSeq, 0, tSeq, shift, tradeCount);
                System.arraycopy(tAccountId, 0, tAccountId, shift, tradeCount);
                System.arraycopy(tSecurityId, 0, tSecurityId, shift, tradeCount);
                System.arraycopy(tSide, 0, tSide, shift, tradeCount);
                System.arraycopy(tQty, 0, tQty, shift, tradeCount);
                System.arraycopy(tPx, 0, tPx, shift, tradeCount);
                System.arraycopy(tUpdatedAt, 0, tUpdatedAt, shift, tradeCount);
                System.arraycopy(older.tSeq, 0, tSeq, 0, shift);
                System.arraycopy(older.tAccountId, 0, tAccountId, 0, shift);
                System.arraycopy(older.tSecurityId, 0, tSecurityId, 0, shift);
                System.arraycopy(older.tSide, 0, tSide, 0, shift);
                System.arraycopy(older.tQty, 0, tQty, 0, shift);
                System.arraycopy(older.tPx, 0, tPx, 0, shift);
                System.arraycopy(older.tUpdatedAt, 0, tUpdatedAt, 0, shift);
                tradeCount = needed;
            }
        }

        /** Reset counts and indexes; the arrays themselves are kept for the next cycle. */
        void clear() {
            orderCount = 0;
            tradeCount = 0;
            positionCount = 0;
            java.util.Arrays.fill(orderSlots, EMPTY);
            java.util.Arrays.fill(positionSlots, EMPTY);
        }

        private static long positionKey(int accountId, int securityId) {
            return ((long) accountId << 32) | (securityId & 0xFFFFFFFFL);
        }

        /** Fibonacci-style spread: securityIds and orderRefs are dense small ints, so raw values collide. */
        private static int spread(int h) {
            h *= 0x9E3779B9;
            return (h ^ (h >>> 16)) & 0x7FFFFFFF;
        }

        private int orderIndexOf(int ref) {
            int i = spread(ref) & orderMask;
            while (orderSlots[i] != EMPTY) {
                if (orderKeys[i] == ref) {
                    return orderSlots[i];
                }
                i = (i + 1) & orderMask;
            }
            return -1;
        }

        private void indexOrder(int ref, int slot) {
            int i = spread(ref) & orderMask;
            while (orderSlots[i] != EMPTY) {
                i = (i + 1) & orderMask;
            }
            orderKeys[i] = ref;
            orderSlots[i] = slot;
        }

        private int positionIndexOf(long key) {
            int i = spread((int) (key ^ (key >>> 32))) & positionMask;
            while (positionSlots[i] != EMPTY) {
                if (positionKeys[i] == key) {
                    return positionSlots[i];
                }
                i = (i + 1) & positionMask;
            }
            return -1;
        }

        private void indexPosition(long key, int slot) {
            int i = spread((int) (key ^ (key >>> 32))) & positionMask;
            while (positionSlots[i] != EMPTY) {
                i = (i + 1) & positionMask;
            }
            positionKeys[i] = key;
            positionSlots[i] = slot;
        }

        private void growOrders(int rows) {
            oRef = grow(oRef, rows);
            oAccountId = grow(oAccountId, rows);
            oSecurityId = grow(oSecurityId, rows);
            oQty = grow(oQty, rows);
            oRemainingQty = grow(oRemainingQty, rows);
            oLastFillQty = grow(oLastFillQty, rows);
            oSide = grow(oSide, rows);
            oStatus = grow(oStatus, rows);
            oLimitPx = grow(oLimitPx, rows);
            oCreatedAt = grow(oCreatedAt, rows);
            oUpdatedAt = grow(oUpdatedAt, rows);
            oLastExecPx = grow(oLastExecPx, rows);
            // Index kept at 2x rows (power of two) so load factor stays <= 0.5 and probes stay short.
            int cap = Integer.highestOneBit(Math.max(16, rows) - 1) << 2;
            orderKeys = new int[cap];
            orderSlots = new int[cap];
            orderMask = cap - 1;
            java.util.Arrays.fill(orderSlots, EMPTY);
            for (int i = 0; i < orderCount; i++) {
                indexOrder(oRef[i], i);   // rehash the rows already buffered
            }
        }

        private void growTrades(int rows) {
            tSeq = grow(tSeq, rows);
            tPx = grow(tPx, rows);
            tUpdatedAt = grow(tUpdatedAt, rows);
            tAccountId = grow(tAccountId, rows);
            tSecurityId = grow(tSecurityId, rows);
            tQty = grow(tQty, rows);
            tSide = grow(tSide, rows);
        }

        private void growPositions(int rows) {
            pAccountId = grow(pAccountId, rows);
            pSecurityId = grow(pSecurityId, rows);
            pQty = grow(pQty, rows);
            pAvgCostTicks = grow(pAvgCostTicks, rows);
            pUpdatedAt = grow(pUpdatedAt, rows);
            int cap = Integer.highestOneBit(Math.max(16, rows) - 1) << 2;
            positionKeys = new long[cap];
            positionSlots = new int[cap];
            positionMask = cap - 1;
            java.util.Arrays.fill(positionSlots, EMPTY);
            for (int i = 0; i < positionCount; i++) {
                indexPosition(positionKey(pAccountId[i], pSecurityId[i]), i);
            }
        }

        private static int[] grow(int[] a, int n) {
            return a == null ? new int[n] : java.util.Arrays.copyOf(a, n);
        }

        private static long[] grow(long[] a, int n) {
            return a == null ? new long[n] : java.util.Arrays.copyOf(a, n);
        }

        private static byte[] grow(byte[] a, int n) {
            return a == null ? new byte[n] : java.util.Arrays.copyOf(a, n);
        }
    }
}
