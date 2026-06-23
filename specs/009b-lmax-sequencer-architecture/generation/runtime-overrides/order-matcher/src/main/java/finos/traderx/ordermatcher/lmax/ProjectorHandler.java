package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.Position;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import finos.traderx.ordermatcher.model.Trade;
import finos.traderx.ordermatcher.model.TradeSide;
import finos.traderx.ordermatcher.model.TradeState;
import finos.traderx.ordermatcher.repository.OrderRepository;
import finos.traderx.ordermatcher.repository.PositionRepository;
import finos.traderx.ordermatcher.repository.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lmax.disruptor.EventHandler;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Async read-model projector (FR-09B22..FR-09B24): batch-writes the order, trade, and
 * position rows off the acknowledgement path. With booking + position-keeping fused into the
 * BLP, this handler is now the sole writer of the {@code OrderBook}, {@code TRADES}, and
 * {@code POSITIONS} tables (replacing trade-processor's inline JPA). Schema and row semantics
 * match 009; only the writer and path changed. A slow or down database degrades to projector
 * lag (rows buffered, retried next batch), never to matching failure.
 */
public final class ProjectorHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(ProjectorHandler.class);
    private static final int MAX_BUFFERED_ROWS = 10_000;
    private static final OrderSide[] ORDER_SIDES = OrderSide.values();
    private static final OrderStatus[] ORDER_STATUSES = OrderStatus.values();

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final PositionRepository positionRepository;
    private final SymbolTable symbols;
    private final int batchSize;
    private final HotPathMetrics metrics;
    private final Map<Integer, OrderRecord> orderBuffer = new LinkedHashMap<>();
    private final Map<Long, Trade> tradeBuffer = new LinkedHashMap<>();
    // Positions dedupe within a batch: only the latest net quantity per (account, security) needs writing.
    private final Map<PositionKey, Position> positionBuffer = new LinkedHashMap<>();
    private final OutputValueCache values = new OutputValueCache();
    private final PositionKeyCache positionKeys = new PositionKeyCache();
    private volatile long projectedSeq = -1;
    private volatile long pendingRows;

    public ProjectorHandler(OrderRepository orderRepository, TradeRepository tradeRepository,
                            PositionRepository positionRepository, SymbolTable symbols, int batchSize,
                            HotPathMetrics metrics) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.positionRepository = positionRepository;
        this.symbols = symbols;
        this.batchSize = Math.max(1, batchSize);
        this.metrics = metrics;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        switch (e.kind) {
            case OutputEvent.KIND_ORDER_ACCEPTED, OutputEvent.KIND_ORDER_REJECTED,
                 OutputEvent.KIND_ORDER_PARTIALLY_FILLED, OutputEvent.KIND_ORDER_FILLED,
                 OutputEvent.KIND_ORDER_CANCELED -> {
                // flags==0 updates are republished unchanged state (no persistence in 009 either)
                if (e.flags != 0) {
                    Integer key = values.integerFor(e.orderRef);
                    OrderRecord record = orderBuffer.get(key);
                    if (record == null) {
                        record = new OrderRecord();
                        orderBuffer.put(key, record);
                    }
                    copyOrder(e, record);
                }
            }
            case OutputEvent.KIND_TRADE_BOOKED -> {
                Long key = values.longFor(e.tradeSeq);
                Trade trade = tradeBuffer.get(key);
                if (trade == null) {
                    trade = new Trade();
                    tradeBuffer.put(key, trade);
                }
                copyTrade(e, trade);
            }
            case OutputEvent.KIND_POSITION_UPDATED -> {
                PositionKey key = positionKeys.keyFor(e.accountId, e.securityId);
                Position position = positionBuffer.get(key);
                if (position == null) {
                    position = new Position();
                    positionBuffer.put(key, position);
                }
                copyPosition(e, position);
            }
            default -> { /* KIND_ORDER_NOT_FOUND: nothing to persist */ }
        }
        int buffered = orderBuffer.size() + tradeBuffer.size() + positionBuffer.size();
        if (buffered > 0 && (endOfBatch || buffered >= batchSize)) {
            flush(sequence);
        }
        pendingRows = orderBuffer.size() + tradeBuffer.size() + positionBuffer.size();
    }

    private void copyOrder(OutputEvent e, OrderRecord record) {
        record.setOrderId(values.orderIdFor(e.orderRef));
        record.setAccountId(values.integerFor(e.accountId));
        record.setSecurity(symbols.tickerFor(e.securityId));
        record.setSide(ORDER_SIDES[e.side]);
        record.setQuantity(values.integerFor(e.quantity));
        record.setRemainingQuantity(values.integerFor(e.remainingQty));
        record.setLimitPrice(values.priceFor(e.limitPx));
        record.setStatus(ORDER_STATUSES[e.status]);
        record.setCreatedAt(values.instantFor(e.createdAtMillis));
        record.setUpdatedAt(values.instantFor(e.updatedAtMillis));
        record.setLastExecutionPrice(values.priceFor(e.lastExecPx));
        record.setLastFillQuantity(e.lastFillQty == 0 ? null : values.integerFor(e.lastFillQty));
    }

    private void copyTrade(OutputEvent e, Trade trade) {
        trade.setId(values.tradeIdFor(e.tradeSeq));
        trade.setAccountId(values.integerFor(e.accountId));
        trade.setSecurity(symbols.tickerFor(e.securityId));
        trade.setSide(e.side == InputEvent.SIDE_BUY ? TradeSide.Buy : TradeSide.Sell);
        trade.setQuantity(values.integerFor(e.tradeQty));
        trade.setPreScaledPrice(values.priceOrZeroFor(e.tradePx));   // stamped execution price (0.000 if no tick), FR-09B40
        trade.setState(TradeState.Settled);
        Date when = trade.getCreated();
        if (when == null) {
            when = new Date(e.updatedAtMillis);
        } else {
            when.setTime(e.updatedAtMillis);
        }
        trade.setCreated(when);
        trade.setUpdated(when);
    }

    private void copyPosition(OutputEvent e, Position position) {
        position.setAccountId(values.integerFor(e.accountId));
        position.setSecurity(symbols.tickerFor(e.securityId));
        position.setQuantity(values.integerFor(e.positionQty));
        position.setPreScaledAverageCostBasis(values.priceOrZeroFor(e.positionAvgCostTicks));   // weighted cost basis, FR-09B40
        Date updated = position.getUpdated();
        if (updated == null) {
            updated = new Date(e.updatedAtMillis);
        } else {
            updated.setTime(e.updatedAtMillis);
        }
        position.setUpdated(updated);
    }

    private void flush(long sequence) {
        try {
            int rows = orderBuffer.size() + tradeBuffer.size() + positionBuffer.size();
            if (!orderBuffer.isEmpty()) {
                orderRepository.saveAll(orderBuffer.values());
                orderBuffer.clear();
            }
            if (!tradeBuffer.isEmpty()) {
                tradeRepository.saveAll(tradeBuffer.values());
                tradeBuffer.clear();
            }
            if (!positionBuffer.isEmpty()) {
                positionRepository.saveAll(positionBuffer.values());
                positionBuffer.clear();
            }
            metrics.recordProjectorBatch(rows);
            projectedSeq = sequence;
        } catch (Exception ex) {
            // DB unavailable: lag and catch up later (FR-09B24). Bound each buffer so a long
            // outage cannot exhaust memory; oldest rows drop first (superseded by newer snapshots).
            trimMapToBound(orderBuffer);
            trimMapToBound(tradeBuffer);
            trimMapToBound(positionBuffer);
            log.warn("Read-model projection failed at seq {} ({}/{}/{} order/trade/position rows buffered): {}",
                sequence, orderBuffer.size(), tradeBuffer.size(), positionBuffer.size(), ex.getMessage());
        }
    }

    private static void trimMapToBound(Map<?, ?> buffer) {
        if (buffer.size() > MAX_BUFFERED_ROWS) {
            var it = buffer.keySet().iterator();
            int toDrop = buffer.size() - MAX_BUFFERED_ROWS;
            for (int i = 0; i < toDrop && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    public long projectedSeq() {
        return projectedSeq;
    }

    public long pendingRows() {
        return pendingRows;
    }

    private static final class PositionKeyCache {
        private static final int ACCOUNT_CAPACITY = 65_536;
        private static final int SECURITY_CAPACITY = 256;

        private final PositionKey[][] keys = new PositionKey[ACCOUNT_CAPACITY][];

        PositionKey keyFor(int accountId, int securityId) {
            if (accountId < 0 || accountId >= ACCOUNT_CAPACITY || securityId < 0 || securityId >= SECURITY_CAPACITY) {
                return new PositionKey(accountId, securityId);
            }
            PositionKey[] bySecurity = keys[accountId];
            if (bySecurity == null) {
                bySecurity = new PositionKey[SECURITY_CAPACITY];
                keys[accountId] = bySecurity;
            }
            PositionKey key = bySecurity[securityId];
            if (key == null) {
                key = new PositionKey(accountId, securityId);
                bySecurity[securityId] = key;
            }
            return key;
        }
    }

    private static final class PositionKey {
        private final int accountId;
        private final int securityId;
        private final int hash;

        private PositionKey(int accountId, int securityId) {
            this.accountId = accountId;
            this.securityId = securityId;
            this.hash = 31 * accountId + securityId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PositionKey that)) {
                return false;
            }
            return accountId == that.accountId && securityId == that.securityId;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
