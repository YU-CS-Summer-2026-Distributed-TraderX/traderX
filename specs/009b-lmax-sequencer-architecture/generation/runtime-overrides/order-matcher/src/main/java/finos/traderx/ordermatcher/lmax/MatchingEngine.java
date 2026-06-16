package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * The Business Logic Processor (state 009b): single thread, entirely in memory,
 * event-sourced (FR-09B10..FR-09B16).
 *
 * Invariants (LMAX-BLP.md A2):
 *  - sole writer of the order book — no locks anywhere (009's orderMutationLock is gone);
 *  - no blocking external calls (no REST, no JPA, no NATS) — results are emitted as typed
 *    events into the output ring;
 *  - deterministic: time is event-carried (eventTimeMillis), ids derive from the order
 *    reference carried in the event, iteration is over arrays;
 *  - zero steady-state allocation: pooled RestingOrder entries, primitive structures,
 *    long fixed-point math (009's BigDecimal in-the-money test becomes integer compares).
 *
 * The matching policy is 009's, preserved exactly (FR-09B13): in-the-money when
 * Buy: px <= limit / Sell: px >= limit; remaining < threshold fills fully, otherwise
 * half rounded up — re-evaluated per relevant sequenced event instead of per polling tick.
 */
public final class MatchingEngine implements EventHandler<InputEvent> {
    private final OutputPublisher out;
    private final HotPathMetrics metrics;
    private final int fillFullThreshold;

    private RestingOrder[] ordersByRef;          // dense index: orderRef -> entry
    private final IntList[] openRefsBySecurity;  // per-security open-order index
    private final long[] lastPxBySecurity;       // long fixed-point; Px.NONE = unknown
    private RestingOrder freeList;               // pre-allocated pool (BLP thread only)
    private final PositionBook positions;        // net positions, single-writer (FR-09B08/B10)
    private long tradeCounter;                    // global trade number, single-writer (deterministic ids)

    // Single-writer telemetry: only the BLP thread writes, edge threads (REST /health,
    // /metrics) read racily-but-safely. The counters are plain longs published by the
    // once-per-event release-store of blpSeq (the Disruptor Sequence.set idiom); readers
    // acquire-load blpSeq first (readFence), so the hot path pays no per-counter fences.
    private long eventsProcessed;
    private long autoFillAttempts;
    private long autoFillSuccess;
    private long lastEventTimeMillis;
    private long ordersNew;
    private long ordersCancel;
    private long ordersForceFill;
    private long priceTicks;
    private long tradesNew;
    private volatile long blpSeq = -1;
    private volatile long blpThreadId;

    private static final VarHandle BLP_SEQ;

    static {
        try {
            BLP_SEQ = MethodHandles.lookup().findVarHandle(MatchingEngine.class, "blpSeq", long.class);
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    public MatchingEngine(OutputPublisher out, HotPathMetrics metrics, int maxSecurities,
                          int fillFullThreshold, int initialPoolSize, int positionCapacity) {
        this.out = out;
        this.metrics = metrics;
        this.fillFullThreshold = Math.max(1, fillFullThreshold);
        this.ordersByRef = new RestingOrder[16_384];
        this.openRefsBySecurity = new IntList[maxSecurities];
        this.lastPxBySecurity = new long[maxSecurities];
        this.positions = new PositionBook(positionCapacity);
        for (int i = 0; i < initialPoolSize; i++) {
            RestingOrder pooled = new RestingOrder();
            pooled.nextFree = freeList;
            freeList = pooled;
        }
    }

    /** BatchEventProcessor start hook: runs on the BLP thread before the first event. */
    @Override
    public void onStart() {
        blpThreadId = Thread.currentThread().threadId();
    }

    @Override
    public void onEvent(InputEvent e, long sequence, boolean endOfBatch) {
        switch (e.type) {
            case InputEvent.TYPE_ORDER_NEW -> { ordersNew++; onNewOrder(e); }
            case InputEvent.TYPE_ORDER_CANCEL -> { ordersCancel++; onCancel(e); }
            case InputEvent.TYPE_FORCE_FILL -> { ordersForceFill++; onForceFill(e); }
            case InputEvent.TYPE_PRICE_TICK -> { priceTicks++; onPriceTick(e); }
            case InputEvent.TYPE_TRADE_NEW -> { tradesNew++; onTradeNew(e); }
            default -> { /* ignore unknown event types */ }
        }
        eventsProcessed++;
        lastEventTimeMillis = e.eventTimeMillis;
        // Release-store: publishes this event's plain counter/time writes to edge readers
        // without the full volatile-store fence on the BLP thread.
        BLP_SEQ.setRelease(this, sequence);
        metrics.recordBlpEventLatency(System.nanoTime() - e.ingressNanos);
    }

    // ----- bootstrap (single-threaded, before the ring goes live) -----------------------

    /** Warm the in-memory book from the read-model at startup (spec: warm-on-start). */
    public void bootstrapOrder(int orderRef, int accountId, int securityId, byte side, int quantity,
                               int remaining, long limitPx, byte status, long lastExecPx,
                               int lastFillQty, long createdAtMillis, long updatedAtMillis) {
        RestingOrder o = takeFromPool();
        o.orderRef = orderRef;
        o.accountId = accountId;
        o.securityId = securityId;
        o.side = side;
        o.quantity = quantity;
        o.remaining = remaining;
        o.limitPx = limitPx;
        o.status = status;
        o.lastExecPx = lastExecPx;
        o.lastFillQty = lastFillQty;
        o.createdAtMillis = createdAtMillis;
        o.updatedAtMillis = updatedAtMillis;
        index(o);
        if (o.isOpen()) {
            openRefs(securityId).add(orderRef);
        }
    }

    /** Warm the in-memory net positions (quantity + cost basis) from the persisted POSITIONS read-model. */
    public void bootstrapPosition(int accountId, int securityId, int quantity, long avgCostTicks) {
        positions.put(accountId, securityId, quantity, avgCostTicks);
    }

    /** Resume the global trade counter above the persisted max so trade ids never collide across restarts. */
    public void bootstrapTradeCounter(long lastTradeSeq) {
        if (lastTradeSeq > tradeCounter) {
            tradeCounter = lastTradeSeq;
        }
    }

    // ----- event handling ----------------------------------------------------------------

    private void onNewOrder(InputEvent e) {
        RestingOrder o = takeFromPool();
        o.orderRef = e.orderRef;
        o.accountId = e.accountId;
        o.securityId = e.securityId;
        o.side = e.side;
        o.quantity = e.qty;
        o.remaining = e.qty;
        o.limitPx = e.limitPx;
        o.status = RestingOrder.STATUS_NEW;
        o.lastExecPx = Px.NONE;
        o.lastFillQty = 0;
        o.createdAtMillis = e.eventTimeMillis;
        o.updatedAtMillis = e.eventTimeMillis;
        index(o);
        IntList refs = openRefs(e.securityId);
        refs.add(e.orderRef);

        long px = lastPxBySecurity[e.securityId];
        // Ack first: the REST create response is the NEW order, exactly as in 009.
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CREATE, true, px, e.ingressNanos);

        // Event-driven matching: evaluate immediately instead of waiting for a poll tick.
        if (px != Px.NONE && isInTheMoney(o, px)) {
            autoFill(o, px, e, refs.size() - 1);
        }
    }

    private void onCancel(InputEvent e) {
        RestingOrder o = lookup(e.orderRef);
        if (o == null) {
            out.emitOrderNotFound(e.seq, e.ingressNanos);
            return;
        }
        long px = lastPxBySecurity[o.securityId];
        if (o.isOpen()) {
            o.status = RestingOrder.STATUS_CANCELED;
            o.remaining = 0;
            o.updatedAtMillis = e.eventTimeMillis;
            openRefs(o.securityId).removeValueUnordered(o.orderRef);
            out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CANCEL, true, px, e.ingressNanos);
        } else {
            // 009 parity: canceling a terminal order returns (and re-publishes) it unchanged.
            out.emitOrderUpdate(o, e.seq, 0, true, px, e.ingressNanos);
        }
    }

    private void onForceFill(InputEvent e) {
        RestingOrder o = lookup(e.orderRef);
        if (o == null) {
            out.emitOrderNotFound(e.seq, e.ingressNanos);
            return;
        }
        long px = lastPxBySecurity[o.securityId];
        if (!o.isOpen() || o.remaining <= 0) {
            out.emitOrderUpdate(o, e.seq, 0, true, px, e.ingressNanos);
            return;
        }
        // 009 parity: force-fill executes the full remaining quantity at the last market
        // price, falling back to the limit price when no tick has been seen yet.
        long execPx = px != Px.NONE ? px : o.limitPx;
        applyFill(o, o.remaining, execPx, true, e, px, -1);
    }

    private void onPriceTick(InputEvent e) {
        lastPxBySecurity[e.securityId] = e.priceTicks;
        IntList refs = openRefsBySecurity[e.securityId];
        if (refs == null) {
            return;
        }
        // Re-evaluate resting orders for this security. Index-based scan; applyFill removes
        // terminal orders from this list (swap-with-last), so the same index is re-checked.
        for (int i = 0; i < refs.size(); ) {
            RestingOrder o = lookup(refs.get(i));
            if (o == null || !o.isOpen() || o.remaining <= 0) {
                refs.removeAtUnordered(i);
                continue;
            }
            if (isInTheMoney(o, e.priceTicks)) {
                autoFill(o, e.priceTicks, e, i);
                if (!o.isOpen()) {
                    continue; // entry at index i was swap-removed by applyFill
                }
            }
            i++;
        }
    }

    /**
     * Market trade from the trade ticket (FR-09B08): no order and no matching — book the trade
     * and update the account's net position directly on the BLP thread (the single position
     * writer), then emit TradeBooked + PositionUpdated. Deterministic: quantity, side, and time
     * are carried in the event; the booking pipeline of trade-processor is fused in here.
     */
    private void onTradeNew(InputEvent e) {
        // Stamp the trade at the BLP's current market price for the security (FR-09B40: the
        // /trades payload carries the execution price); Px.NONE -> 0.000 at the edge when no
        // tick has been seen yet, matching 009's trade-processor null-price handling.
        long execPx = lastPxBySecurity[e.securityId];
        int signedQty = e.side == InputEvent.SIDE_BUY ? e.qty : -e.qty;
        int newPosition = positions.bookTrade(e.accountId, e.securityId, signedQty, execPx);
        long avgCostTicks = positions.lastAvgCostTicks();
        long tradeSeq = ++tradeCounter;
        out.emitMarketTrade(e.accountId, e.securityId, e.side, e.qty, execPx, tradeSeq, newPosition,
            avgCostTicks, e.seq, e.eventTimeMillis, e.ingressNanos);
        metrics.recordMatchLatency(System.nanoTime() - e.ingressNanos);
    }

    // ----- matching policy (preserved from 009, integer math) ----------------------------

    private boolean isInTheMoney(RestingOrder o, long px) {
        return o.side == InputEvent.SIDE_BUY ? px <= o.limitPx : px >= o.limitPx;
    }

    private void autoFill(RestingOrder o, long px, InputEvent e, int openIndex) {
        autoFillAttempts++;
        int remaining = o.remaining;
        int fillQty = remaining < fillFullThreshold ? remaining : Math.max(1, (remaining + 1) / 2);
        applyFill(o, fillQty, px, false, e, px, openIndex);
        autoFillSuccess++;
    }

    private void applyFill(RestingOrder o, int fillQty, long execPx, boolean force, InputEvent e,
                           long marketPx, int openIndex) {
        int remainingAfter = Math.max(0, o.remaining - fillQty);
        o.remaining = remainingAfter;
        o.lastExecPx = execPx;
        o.lastFillQty = fillQty;
        o.updatedAtMillis = e.eventTimeMillis;

        int flags;
        if (remainingAfter == 0) {
            o.status = RestingOrder.STATUS_FILLED;
            flags = OutputEvent.FLAG_FILL;
        } else {
            o.status = RestingOrder.STATUS_PARTIALLY_FILLED;
            flags = OutputEvent.FLAG_PARTIAL_FILL;
        }
        if (force) {
            flags |= OutputEvent.FLAG_FORCE_FILL;
        }
        if (!o.isOpen()) {
            removeOpenRef(o, openIndex);
        }

        // Booking + position-keeping are fused into the BLP (FR-09B08/B10): the fill books the
        // trade at its execution price and updates the account's net position + weighted cost
        // basis in memory (single writer, no DB hop), then the order update, TradeBooked, and
        // PositionUpdated events leave as one paired claim — a single publish, a single
        // consumer signal (FR-09B15: side-effects are typed output events).
        int signedQty = o.side == InputEvent.SIDE_BUY ? fillQty : -fillQty;
        int newPosition = positions.bookTrade(o.accountId, o.securityId, signedQty, execPx);
        long avgCostTicks = positions.lastAvgCostTicks();
        long tradeSeq = ++tradeCounter;
        out.emitFillWithTrade(o, fillQty, execPx, tradeSeq, newPosition, avgCostTicks, e.seq, flags,
            marketPx, e.ingressNanos);
        metrics.recordMatchLatency(System.nanoTime() - e.ingressNanos);
    }

    /** Drop a now-terminal order from its security's open index; O(1) when the caller knows the slot. */
    private void removeOpenRef(RestingOrder o, int openIndex) {
        IntList refs = openRefsBySecurity[o.securityId];
        if (refs == null) {
            return;
        }
        if (openIndex >= 0 && openIndex < refs.size() && refs.get(openIndex) == o.orderRef) {
            refs.removeAtUnordered(openIndex);
        } else {
            refs.removeValueUnordered(o.orderRef);
        }
    }

    // ----- internal structures ------------------------------------------------------------

    private RestingOrder lookup(int orderRef) {
        if (orderRef < 0 || orderRef >= ordersByRef.length) {
            return null;
        }
        return ordersByRef[orderRef];
    }

    private void index(RestingOrder o) {
        if (o.orderRef >= ordersByRef.length) {
            int newLength = ordersByRef.length;
            while (newLength <= o.orderRef) {
                newLength *= 2;
            }
            RestingOrder[] grown = new RestingOrder[newLength];
            System.arraycopy(ordersByRef, 0, grown, 0, ordersByRef.length);
            ordersByRef = grown;
        }
        ordersByRef[o.orderRef] = o;
    }

    private IntList openRefs(int securityId) {
        IntList refs = openRefsBySecurity[securityId];
        if (refs == null) {
            refs = new IntList(256);
            openRefsBySecurity[securityId] = refs;
        }
        return refs;
    }

    private RestingOrder takeFromPool() {
        RestingOrder o = freeList;
        if (o == null) {
            return new RestingOrder();
        }
        freeList = o.nextFree;
        o.nextFree = null;
        o.reset();
        return o;
    }

    // ----- edge-readable telemetry ----------------------------------------------------------

    /**
     * Acquire-load of blpSeq: orders the subsequent plain counter read after the BLP's
     * release-store, so edge threads see counters at least as fresh as the last published
     * sequence they observe.
     */
    private void readFence() {
        BLP_SEQ.getAcquire(this);
    }

    public long eventsProcessed() {
        readFence();
        return eventsProcessed;
    }

    public long autoFillAttempts() {
        readFence();
        return autoFillAttempts;
    }

    public long autoFillSuccess() {
        readFence();
        return autoFillSuccess;
    }

    public long lastEventTimeMillis() {
        readFence();
        return lastEventTimeMillis;
    }

    public long blpSeq() {
        return blpSeq;
    }

    public long countOrdersNew() {
        readFence();
        return ordersNew;
    }

    public long countOrdersCancel() {
        readFence();
        return ordersCancel;
    }

    public long countForceFills() {
        readFence();
        return ordersForceFill;
    }

    public long countPriceTicks() {
        readFence();
        return priceTicks;
    }

    public long countTradesNew() {
        readFence();
        return tradesNew;
    }

    /**
     * Net position for an account/security. Single-writer state read off the BLP thread; the
     * acquire-load orders the read after the BLP's last release-store. Intended for tests and
     * warm-start verification (read when the BLP is quiesced), not per-event edge polling.
     */
    public int positionQuantity(int accountId, int securityId) {
        readFence();
        return positions.get(accountId, securityId);
    }

    /** Weighted average cost basis (Px ticks) for an account/security; single-writer read off the BLP thread. */
    public long positionAvgCostTicks(int accountId, int securityId) {
        readFence();
        return positions.avgCostTicks(accountId, securityId);
    }

    public long blpThreadId() {
        return blpThreadId;
    }
}
