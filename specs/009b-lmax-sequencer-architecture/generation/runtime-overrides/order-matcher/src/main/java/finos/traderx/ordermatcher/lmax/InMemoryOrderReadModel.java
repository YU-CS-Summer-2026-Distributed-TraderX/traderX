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

    // Bounded terminal-order retention, mirroring the BLP's: refs in terminal-transition order,
    // written only by the single writer (bootstrap thread, then the marshaller handler). Without
    // it every terminal OrderSnapshot is retained forever and GC pressure collapses sustained
    // throughput. Evicted (ancient, terminal) refs answer not-found on GET; open orders are
    // never evicted.
    private final int[] terminalFifo;   // null = retain everything (cap <= 0)
    private final int terminalFifoMask;
    private int terminalHead;
    private int terminalCount;
    private final LongAdder evictedOrders = new LongAdder();

    // True while the live engine is reconstructing from snapshot+journal at startup: the NATS-publishing
    // output handlers check this and skip, so recovery does not re-broadcast historical events.
    private volatile boolean replaying;

    public InMemoryOrderReadModel() {
        this(MatchingEngine.DEFAULT_MAX_RETAINED_TERMINAL);
    }

    public InMemoryOrderReadModel(int maxRetainedTerminal) {
        for (String event : List.of("create", "partial_fill", "fill", "cancel", "reject", "force_fill")) {
            eventCounters.put(event, new LongAdder());
        }
        if (maxRetainedTerminal > 0) {
            int capacity = Integer.highestOneBit(maxRetainedTerminal);
            if (capacity < maxRetainedTerminal) {
                capacity <<= 1;
            }
            this.terminalFifo = new int[capacity];
            this.terminalFifoMask = capacity - 1;
        } else {
            this.terminalFifo = null;
            this.terminalFifoMask = 0;
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
        OrderSnapshot previous = byRef.put(snapshot.orderRef, snapshot);
        if (!snapshot.isOpen() && (previous == null || previous.isOpen())) {
            retainTerminal(snapshot.orderRef);
        }
    }

    public void apply(OutputEvent e, SymbolTable symbols) {
        OrderSnapshot snapshot = OrderSnapshot.fromEvent(e, symbols);
        OrderSnapshot previous = byRef.put(snapshot.orderRef, snapshot);
        // Push on the transition INTO terminal only: refs never reuse and a terminal order never
        // reopens, so each ref enters the FIFO at most once (re-publishes of an already-terminal
        // order, e.g. cancel-of-filled, must not double-enter).
        if (!snapshot.isOpen() && (previous == null || previous.isOpen())) {
            retainTerminal(snapshot.orderRef);
        }
        countFlags(e.flags);
        completeAck(e.inputSeq, snapshot);
    }

    /** Single-writer (bootstrap thread, then marshaller handler only). */
    private void retainTerminal(int orderRef) {
        if (terminalFifo == null) {
            return;
        }
        if (terminalCount == terminalFifo.length) {
            int victimRef = terminalFifo[terminalHead];
            terminalHead = (terminalHead + 1) & terminalFifoMask;
            terminalCount--;
            OrderSnapshot victim = byRef.get(victimRef);
            if (victim != null && !victim.isOpen()) {
                byRef.remove(victimRef);
                evictedOrders.increment();
            }
        }
        terminalFifo[(terminalHead + terminalCount) & terminalFifoMask] = orderRef;
        terminalCount++;
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
            if (s.isOpen() && s.remainingQuantity != null && s.remainingQuantity > 0) {
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

    /** Terminal orders dropped from the read model by bounded retention since start. */
    public long evictedOrders() {
        return evictedOrders.sum();
    }
}
