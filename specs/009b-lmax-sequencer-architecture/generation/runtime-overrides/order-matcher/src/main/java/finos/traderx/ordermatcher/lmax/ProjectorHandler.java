package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.Position;
import finos.traderx.ordermatcher.model.PositionID;
import finos.traderx.ordermatcher.model.Trade;
import finos.traderx.ordermatcher.model.TradeSide;
import finos.traderx.ordermatcher.model.TradeState;
import finos.traderx.ordermatcher.repository.OrderRepository;
import finos.traderx.ordermatcher.repository.PositionRepository;
import finos.traderx.ordermatcher.repository.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lmax.disruptor.EventHandler;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
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

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final PositionRepository positionRepository;
    private final SymbolTable symbols;
    private final int batchSize;
    private final HotPathMetrics metrics;
    private final List<OrderRecord> orderBuffer = new ArrayList<>();
    private final List<Trade> tradeBuffer = new ArrayList<>();
    // Positions dedupe within a batch: only the latest net quantity per (account, security) needs writing.
    private final Map<PositionID, Position> positionBuffer = new LinkedHashMap<>();
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
            case OutputEvent.KIND_ORDER_UPDATE -> {
                // flags==0 updates are republished unchanged state (no persistence in 009 either)
                if (e.flags != 0) {
                    orderBuffer.add(OrderSnapshot.fromEvent(e, symbols).toRecord());
                }
            }
            case OutputEvent.KIND_TRADE_BOOKED -> tradeBuffer.add(toTrade(e));
            case OutputEvent.KIND_POSITION_UPDATED -> {
                Position p = toPosition(e);
                positionBuffer.put(new PositionID(p.getAccountId(), p.getSecurity()), p);
            }
            default -> { /* KIND_ORDER_NOT_FOUND: nothing to persist */ }
        }
        int buffered = orderBuffer.size() + tradeBuffer.size() + positionBuffer.size();
        if (buffered > 0 && (endOfBatch || buffered >= batchSize)) {
            flush(sequence);
        }
        pendingRows = orderBuffer.size() + tradeBuffer.size() + positionBuffer.size();
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

    private void flush(long sequence) {
        try {
            int rows = orderBuffer.size() + tradeBuffer.size() + positionBuffer.size();
            if (!orderBuffer.isEmpty()) {
                orderRepository.saveAll(orderBuffer);
                orderBuffer.clear();
            }
            if (!tradeBuffer.isEmpty()) {
                tradeRepository.saveAll(tradeBuffer);
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
            trimToBound(orderBuffer);
            trimToBound(tradeBuffer);
            trimMapToBound(positionBuffer);
            log.warn("Read-model projection failed at seq {} ({}/{}/{} order/trade/position rows buffered): {}",
                sequence, orderBuffer.size(), tradeBuffer.size(), positionBuffer.size(), ex.getMessage());
        }
    }

    private static void trimToBound(List<?> buffer) {
        if (buffer.size() > MAX_BUFFERED_ROWS) {
            buffer.subList(0, buffer.size() - MAX_BUFFERED_ROWS).clear();
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
}
