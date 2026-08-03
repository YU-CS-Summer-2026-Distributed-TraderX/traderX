package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Output-event-fed in-memory read model. Serves all REST reads (list/get/counts/metrics
 * gauges) without touching the BLP or the database, and correlates gateway request/response
 * acks (the LMAX "request and response events" pattern — LMAX-BLP.md A7).
 *
 * Bootstrapped from the persisted read-model at startup; thereafter the marshaller handler
 * on the output ring is its only writer.
 */
public final class InMemoryOrderReadModel {
    /** Completion signal for commands referencing an unknown order (-> HTTP 404). */
    public static final class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException() {
            super("order not found");
        }
    }

    private final ConcurrentHashMap<Integer, OrderSnapshot> byRef = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> eventCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<OrderSnapshot>> pendingAcks = new ConcurrentHashMap<>();
    private final LongAdder tradeSubmitFailures = new LongAdder();
    private final LongAdder accountTradePublishFailures = new LongAdder();
    private final LongAdder positionPublishFailures = new LongAdder();
    private final LongAdder natsErrors = new LongAdder();

    // True while the live engine is reconstructing from snapshot+journal at startup: the NATS-publishing
    // output handlers check this and skip, so recovery does not re-broadcast historical events.
    private volatile boolean replaying;

    public InMemoryOrderReadModel() {
        for (String event : List.of("create", "partial_fill", "fill", "cancel", "reject", "force_fill")) {
            eventCounters.put(event, new LongAdder());
        }
    }

    public boolean isReplaying() {
        return replaying;
    }

    public void setReplaying(boolean replaying) {
        this.replaying = replaying;
    }

    // ----- writes (bootstrap thread, then marshaller handler only) -----------------------

    public void bootstrap(OrderSnapshot snapshot) {
        byRef.put(snapshot.orderRef, snapshot);
    }

    public void apply(OutputEvent e, SymbolTable symbols) {
        OrderSnapshot snapshot = byRef.get(e.orderRef);
        if (snapshot == null) {
            snapshot = OrderSnapshot.fromEvent(e, symbols);
            byRef.put(snapshot.orderRef, snapshot);
        } else {
            snapshot.updateFromEvent(e, symbols);
        }
        countFlags(e.flags);
        completeAck(e.inputSeq, snapshot);
    }

    public void notFound(long inputSeq) {
        CompletableFuture<OrderSnapshot> ack = pendingAcks.remove(inputSeq);
        if (ack != null) {
            ack.completeExceptionally(new OrderNotFoundException());
        }
    }

    private void completeAck(long inputSeq, OrderSnapshot snapshot) {
        CompletableFuture<OrderSnapshot> ack = pendingAcks.remove(inputSeq);
        if (ack != null) {
            ack.complete(snapshot);
        }
    }

    private void countFlags(int flags) {
        if ((flags & OutputEvent.FLAG_CREATE) != 0) {
            increment("create");
        }
        if ((flags & OutputEvent.FLAG_PARTIAL_FILL) != 0) {
            increment("partial_fill");
        }
        if ((flags & OutputEvent.FLAG_FILL) != 0) {
            increment("fill");
        }
        if ((flags & OutputEvent.FLAG_CANCEL) != 0) {
            increment("cancel");
        }
        if ((flags & OutputEvent.FLAG_REJECT) != 0) {
            increment("reject");
        }
        if ((flags & OutputEvent.FLAG_FORCE_FILL) != 0) {
            increment("force_fill");
        }
    }

    // ----- gateway correlation ------------------------------------------------------------

    public CompletableFuture<OrderSnapshot> registerAck(long inputSeq) {
        CompletableFuture<OrderSnapshot> future = new CompletableFuture<>();
        pendingAcks.put(inputSeq, future);
        return future;
    }

    public void abandonAck(long inputSeq) {
        pendingAcks.remove(inputSeq);
    }

    // ----- reads ----------------------------------------------------------------------------

    public OrderSnapshot get(int orderRef) {
        return byRef.get(orderRef);
    }

    public List<OrderSnapshot> all() {
        return new ArrayList<>(byRef.values());
    }

    public long countOpen() {
        long count = 0;
        for (OrderSnapshot s : byRef.values()) {
            if (s.isOpen()) {
                count++;
            }
        }
        return count;
    }

    public long countUnfilled() {
        long count = 0;
        for (OrderSnapshot s : byRef.values()) {
            if (s.isOpen() && s.remainingQuantity > 0) {
                count++;
            }
        }
        return count;
    }

    public long countPendingBySide(OrderSide side) {
        long count = 0;
        for (OrderSnapshot s : byRef.values()) {
            if (s.isOpen() && s.side == side) {
                count++;
            }
        }
        return count;
    }

    public long countByStatus(OrderStatus status) {
        long count = 0;
        for (OrderSnapshot s : byRef.values()) {
            if (s.status == status) {
                count++;
            }
        }
        return count;
    }

    public long totalOrders() {
        return byRef.size();
    }

    // ----- prices (gateway-stamped, mirrors 009's lastPrices semantics) ---------------------

    public void recordPrice(String ticker, BigDecimal price) {
        lastPrices.put(ticker, price);
    }

    public BigDecimal lastPrice(String ticker) {
        return ticker == null ? null : lastPrices.get(ticker);
    }

    // ----- counters ---------------------------------------------------------------------------

    public void increment(String event) {
        eventCounters.computeIfAbsent(event, ignored -> new LongAdder()).increment();
    }

    public void setCounter(String event, long value) {
        LongAdder adder = eventCounters.computeIfAbsent(event, ignored -> new LongAdder());
        adder.reset();
        adder.add(value);
    }

    public long counterValue(String event) {
        LongAdder adder = eventCounters.get(event);
        return adder == null ? 0 : adder.sum();
    }

    public LongAdder tradeSubmitFailures() {
        return tradeSubmitFailures;
    }

    public LongAdder accountTradePublishFailures() {
        return accountTradePublishFailures;
    }

    public LongAdder positionPublishFailures() {
        return positionPublishFailures;
    }

    public LongAdder natsErrors() {
        return natsErrors;
    }
}
