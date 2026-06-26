package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Async read-model projector that keeps DB I/O off the output-ring consumer thread.
 */
public final class ProjectorHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(ProjectorHandler.class);
    private static final int TRADE_INSERT_CHUNK = 500;
    private static final String TRADE_COLS =
        "(id, accountid, security, side, state, quantity, price, created, updated)";
    private static final OrderSide[] ORDER_SIDES = OrderSide.values();
    private static final OrderStatus[] ORDER_STATUSES = OrderStatus.values();

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final PositionRepository positionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final SymbolTable symbols;
    private final int batchSize;
    private final int queueCapacity;
    private final HotPathMetrics metrics;
    private final BlockingQueue<ProjectionItem> queue;
    private final Thread drainThread;
    private final Map<String, OrderRecord> orderBuffer = new LinkedHashMap<>();
    private final Map<String, Trade> tradeBuffer = new LinkedHashMap<>();
    private final Map<PositionID, Position> positionBuffer = new LinkedHashMap<>();
    private final OutputValueCache values = new OutputValueCache();
    private final LongAdder enqueueBlocks = new LongAdder();

    private volatile boolean running;
    private volatile boolean dbDown;
    private volatile long projectedSeq = -1;
    private volatile long pendingSeq = -1;
    private volatile long pendingRows;
    private volatile long tradesPersisted;

    public ProjectorHandler(OrderRepository orderRepository, TradeRepository tradeRepository,
                            PositionRepository positionRepository, JdbcTemplate jdbcTemplate,
                            SymbolTable symbols, int batchSize, int queueCapacity,
                            HotPathMetrics metrics) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.positionRepository = positionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.symbols = symbols;
        this.batchSize = Math.max(1, batchSize);
        this.queueCapacity = Math.max(1024, queueCapacity);
        this.metrics = metrics;
        this.queue = new LinkedBlockingQueue<>(this.queueCapacity);
        this.drainThread = new Thread(this::drainLoop, "projector-drain");
        this.drainThread.setDaemon(true);
    }

    public ProjectorHandler(OrderRepository orderRepository, PositionRepository positionRepository,
                            JdbcTemplate jdbcTemplate, SymbolTable symbols, int batchSize,
                            HotPathMetrics metrics) {
        this(orderRepository, null, positionRepository, jdbcTemplate, symbols, batchSize, 1024, metrics);
    }

    public void start() {
        if (running) {
            return;
        }
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

    @Override
    public void onEvent(OutputEvent event, long sequence, boolean endOfBatch) {
        ProjectionItem item = toItem(event, sequence);
        if (item == null) {
            return;
        }
        if (!queue.offer(item)) {
            enqueueBlocks.increment();
            try {
                queue.put(item);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        pendingRows = queue.size() + bufferedCount();
    }

    public long projectedSeq() {
        return projectedSeq;
    }

    public long pendingRows() {
        return pendingRows;
    }

    public long tradesPersisted() {
        return tradesPersisted;
    }

    public long queueDepth() {
        return queue.size();
    }

    public long queueCapacity() {
        return queueCapacity;
    }

    public long enqueueBlocks() {
        return enqueueBlocks.sum();
    }

    private ProjectionItem toItem(OutputEvent event, long sequence) {
        return switch (event.kind) {
            case OutputEvent.KIND_ORDER_ACCEPTED, OutputEvent.KIND_ORDER_REJECTED,
                 OutputEvent.KIND_ORDER_PARTIALLY_FILLED, OutputEvent.KIND_ORDER_FILLED,
                 OutputEvent.KIND_ORDER_CANCELED -> event.flags == 0
                    ? null
                    : new ProjectionItem(sequence, toOrder(event), null, null);
            case OutputEvent.KIND_TRADE_BOOKED -> new ProjectionItem(sequence, null, toTrade(event), null);
            case OutputEvent.KIND_POSITION_UPDATED -> new ProjectionItem(sequence, null, null, toPosition(event));
            default -> null;
        };
    }

    private void drainLoop() {
        List<ProjectionItem> drained = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty() || hasBuffered()) {
            try {
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
                    for (ProjectionItem item : drained) {
                        route(item);
                    }
                }

                boolean flushNow = dbDown || bufferedCount() >= batchSize || queue.isEmpty() || !running;
                if (hasBuffered() && flushNow) {
                    if (flush(pendingSeq)) {
                        dbDown = false;
                    } else {
                        dbDown = true;
                        Thread.sleep(50);
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
            flush(pendingSeq);
        }
    }

    private void route(ProjectionItem item) {
        if (item.order() != null) {
            orderBuffer.put(item.order().getOrderId(), item.order());
        } else if (item.trade() != null) {
            tradeBuffer.put(item.trade().getId(), item.trade());
        } else if (item.position() != null) {
            positionBuffer.put(new PositionID(item.position().getAccountId(), item.position().getSecurity()),
                item.position());
        }
        if (item.sequence() > pendingSeq) {
            pendingSeq = item.sequence();
        }
    }

    private boolean flush(long sequence) {
        try {
            int rows = bufferedCount();
            if (!orderBuffer.isEmpty()) {
                orderRepository.saveAll(orderBuffer.values());
                orderBuffer.clear();
            }
            if (!tradeBuffer.isEmpty()) {
                List<Trade> trades = new ArrayList<>(tradeBuffer.values());
                insertTradesBatch(trades);
                tradesPersisted += trades.size();
                tradeBuffer.clear();
            }
            if (!positionBuffer.isEmpty()) {
                positionRepository.saveAll(positionBuffer.values());
                positionBuffer.clear();
            }
            metrics.recordProjectorBatch(rows);
            projectedSeq = sequence;
            pendingSeq = -1;
            pendingRows = queue.size();
            return true;
        } catch (Exception ex) {
            log.warn("Read-model projection failed at seq {} ({} rows buffered, {} queued): {}",
                sequence, bufferedCount(), queue.size(), ex.getMessage());
            return false;
        }
    }

    private void insertTradesBatch(List<Trade> trades) {
        for (int start = 0; start < trades.size(); start += TRADE_INSERT_CHUNK) {
            int end = Math.min(start + TRADE_INSERT_CHUNK, trades.size());
            List<Trade> chunk = trades.subList(start, end);
            try {
                StringBuilder sql = new StringBuilder(64 + chunk.size() * 20)
                    .append("INSERT INTO trades ").append(TRADE_COLS).append(" VALUES ");
                Object[] args = new Object[chunk.size() * 9];
                int a = 0;
                for (int i = 0; i < chunk.size(); i++) {
                    if (i > 0) {
                        sql.append(',');
                    }
                    sql.append("(?,?,?,?,?,?,?,?,?)");
                    Trade trade = chunk.get(i);
                    args[a++] = trade.getId();
                    args[a++] = trade.getAccountId();
                    args[a++] = trade.getSecurity();
                    args[a++] = trade.getSide() == null ? null : trade.getSide().name();
                    args[a++] = trade.getState() == null ? null : trade.getState().name();
                    args[a++] = trade.getQuantity();
                    args[a++] = trade.getPrice();
                    args[a++] = trade.getCreated() == null ? null : new Timestamp(trade.getCreated().getTime());
                    args[a++] = trade.getUpdated() == null ? null : new Timestamp(trade.getUpdated().getTime());
                }
                sql.append(" ON CONFLICT (id) DO NOTHING");
                jdbcTemplate.update(sql.toString(), args);
            } catch (RuntimeException ex) {
                if (tradeRepository == null) {
                    throw ex;
                }
                tradeRepository.saveAll(chunk);
            }
        }
    }

    private int bufferedCount() {
        return orderBuffer.size() + tradeBuffer.size() + positionBuffer.size();
    }

    private boolean hasBuffered() {
        return bufferedCount() > 0;
    }

    private OrderRecord toOrder(OutputEvent event) {
        OrderRecord record = new OrderRecord();
        record.setOrderId(values.orderIdFor(event.orderRef));
        record.setAccountId(values.integerFor(event.accountId));
        record.setSecurity(symbols.tickerFor(event.securityId));
        record.setSide(ORDER_SIDES[event.side]);
        record.setQuantity(values.integerFor(event.quantity));
        record.setRemainingQuantity(values.integerFor(event.remainingQty));
        record.setLimitPrice(values.priceFor(event.limitPx));
        record.setStatus(ORDER_STATUSES[event.status]);
        record.setCreatedAt(values.instantFor(event.createdAtMillis));
        record.setUpdatedAt(values.instantFor(event.updatedAtMillis));
        record.setLastExecutionPrice(values.priceFor(event.lastExecPx));
        record.setLastFillQuantity(event.lastFillQty == 0 ? null : values.integerFor(event.lastFillQty));
        return record;
    }

    private Trade toTrade(OutputEvent event) {
        Trade trade = new Trade();
        trade.setId(values.tradeIdFor(event.tradeSeq));
        trade.setAccountId(values.integerFor(event.accountId));
        trade.setSecurity(symbols.tickerFor(event.securityId));
        trade.setSide(event.side == InputEvent.SIDE_BUY ? TradeSide.Buy : TradeSide.Sell);
        trade.setState(TradeState.Settled);
        trade.setQuantity(values.integerFor(event.tradeQty));
        trade.setPreScaledPrice(values.priceOrZeroFor(event.tradePx));
        Date when = new Date(event.updatedAtMillis);
        trade.setCreated(when);
        trade.setUpdated(when);
        return trade;
    }

    private Position toPosition(OutputEvent event) {
        Position position = new Position();
        position.setAccountId(values.integerFor(event.accountId));
        position.setSecurity(symbols.tickerFor(event.securityId));
        position.setQuantity(values.integerFor(event.positionQty));
        position.setPreScaledAverageCostBasis(values.priceOrZeroFor(event.positionAvgCostTicks));
        position.setUpdated(new Date(event.updatedAtMillis));
        return position;
    }

    private record ProjectionItem(long sequence, OrderRecord order, Trade trade, Position position) {
    }
}
