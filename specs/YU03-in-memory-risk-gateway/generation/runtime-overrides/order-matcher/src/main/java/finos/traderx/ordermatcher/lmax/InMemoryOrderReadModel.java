package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import finos.traderx.ordermatcher.risk.RiskReason;

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
    private final ConcurrentHashMap<Long, CompletableFuture<RiskReason>> pendingTradeAcks = new ConcurrentHashMap<>();
    private final LongAdder tradeSubmitFailures = new LongAdder();
    private final LongAdder accountTradePublishFailures = new LongAdder();
    private final LongAdder positionPublishFailures = new LongAdder();
    private final LongAdder natsErrors = new LongAdder();

    // Bounded terminal retention mirror of the BLP (state 009b Tier 2-B): the marshaller is the only
    // runtime writer of byRef, so a plain FIFO of the most recent TERMINAL_RETAIN terminal orderRefs
    // (no sync) bounds the read model the same way the BLP bounds ordersByRef. Older terminal orders are
    // removed from byRef; a GET for one then returns null (404), matching the BLP's aged-out behavior.
    // Mirrors the blp.terminal.retain default; reads stay correct for open orders + recent terminals.
    private static final int DEFAULT_TERMINAL_RETAIN = 262_144;
    private final int[] terminalRing;
    private int terminalHead;
    private int terminalCount;

    // True while the live engine is reconstructing from snapshot+journal at startup: the NATS-publishing
    // output handlers check this and skip, so recovery does not re-broadcast historical events.
    private volatile boolean replaying;

    public InMemoryOrderReadModel() {
        this(DEFAULT_TERMINAL_RETAIN);
    }

    InMemoryOrderReadModel(int terminalRetain) {
        if (terminalRetain < 1) {
            throw new IllegalArgumentException("terminalRetain must be positive");
        }
        terminalRing = new int[terminalRetain];
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

    /**
     * DB warm-up arrives newest-first. Retain every open order and only the configured newest
     * terminal window; prepend retained terminals so the runtime FIFO still evicts the oldest one
     * when a new terminal transition arrives after recovery.
     *
     * @return true when the BLP should retain this row too, false for an aged-out terminal row.
     */
    public boolean bootstrapNewestFirst(OrderSnapshot snapshot) {
        if (snapshot.isOpen()) {
            byRef.put(snapshot.orderRef, snapshot);
            return true;
        }
        if (terminalCount == terminalRing.length) {
            return false;
        }
        terminalHead = terminalHead == 0 ? terminalRing.length - 1 : terminalHead - 1;
        terminalRing[terminalHead] = snapshot.orderRef;
        terminalCount++;
        byRef.put(snapshot.orderRef, snapshot);
        return true;
    }

    public void apply(OutputEvent e, SymbolTable symbols) {
        OrderSnapshot snapshot = OrderSnapshot.fromEvent(e, symbols);
        byRef.put(snapshot.orderRef, snapshot);
        if (isTerminalTransition(e.flags)) {
            trackTerminal(snapshot.orderRef);   // bound retention: evict the oldest terminal when full
        }
        countFlags(e.flags);
        completeAck(e.inputSeq, snapshot);
    }

    /** A terminal lifecycle transition (fill-to-zero, cancel of an open order, or reject). A republished
     *  terminal order carries flags=0, so it is not re-tracked. Marshaller thread only (single writer). */
    private static boolean isTerminalTransition(int flags) {
        return (flags & (OutputEvent.FLAG_FILL | OutputEvent.FLAG_CANCEL | OutputEvent.FLAG_REJECT)) != 0;
    }

    /** FIFO-track a terminal order, evicting the oldest from byRef when the cap is full. Single-writer. */
    private void trackTerminal(int orderRef) {
        if (terminalCount == terminalRing.length) {
            int oldest = terminalRing[terminalHead];
            terminalHead = terminalHead + 1 == terminalRing.length ? 0 : terminalHead + 1;
            terminalCount--;
            byRef.remove(oldest);
        }
        int tail = terminalHead + terminalCount;
        if (tail >= terminalRing.length) {
            tail -= terminalRing.length;
        }
        terminalRing[tail] = orderRef;
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

    // Market-trade risk decision acks (in-memory-risk-gateway): TRADE_NEW commands correlate on
    // the BLP's authoritative accept/reject rather than an order snapshot (FR-IMRG15/20).

    public CompletableFuture<RiskReason> registerTradeAck(long inputSeq) {
        CompletableFuture<RiskReason> future = new CompletableFuture<>();
        pendingTradeAcks.put(inputSeq, future);
        return future;
    }

    public void completeTradeAck(long inputSeq, RiskReason decision) {
        CompletableFuture<RiskReason> ack = pendingTradeAcks.remove(inputSeq);
        if (ack != null) {
            ack.complete(decision);
        }
    }

    public void abandonTradeAck(long inputSeq) {
        pendingTradeAcks.remove(inputSeq);
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
}
