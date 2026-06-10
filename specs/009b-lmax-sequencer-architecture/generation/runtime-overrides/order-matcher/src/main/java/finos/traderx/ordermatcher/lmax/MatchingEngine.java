package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;

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

    // Single-writer counters; volatile so edge threads can read them racily-but-safely.
    private volatile long eventsProcessed;
    private volatile long autoFillAttempts;
    private volatile long autoFillSuccess;
    private volatile long lastEventTimeMillis;
    private volatile long blpSeq;
    private volatile long ordersNew;
    private volatile long ordersCancel;
    private volatile long ordersForceFill;
    private volatile long priceTicks;
    private volatile long blpThreadId;

    public MatchingEngine(OutputPublisher out, HotPathMetrics metrics, int maxSecurities,
                          int fillFullThreshold, int initialPoolSize) {
        this.out = out;
        this.metrics = metrics;
        this.fillFullThreshold = Math.max(1, fillFullThreshold);
        this.ordersByRef = new RestingOrder[16_384];
        this.openRefsBySecurity = new IntList[maxSecurities];
        this.lastPxBySecurity = new long[maxSecurities];
        for (int i = 0; i < initialPoolSize; i++) {
            RestingOrder pooled = new RestingOrder();
            pooled.nextFree = freeList;
            freeList = pooled;
        }
    }

    @Override
    public void onEvent(InputEvent e, long sequence, boolean endOfBatch) {
        if (blpThreadId == 0) {
            blpThreadId = Thread.currentThread().threadId();
        }
        switch (e.type) {
            case InputEvent.TYPE_ORDER_NEW -> { ordersNew++; onNewOrder(e); }
            case InputEvent.TYPE_ORDER_CANCEL -> { ordersCancel++; onCancel(e); }
            case InputEvent.TYPE_FORCE_FILL -> { ordersForceFill++; onForceFill(e); }
            case InputEvent.TYPE_PRICE_TICK -> { priceTicks++; onPriceTick(e); }
            default -> { /* ignore unknown event types */ }
        }
        eventsProcessed++;
        lastEventTimeMillis = e.eventTimeMillis;
        blpSeq = sequence;
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
        openRefs(e.securityId).add(e.orderRef);

        long px = lastPxBySecurity[e.securityId];
        // Ack first: the REST create response is the NEW order, exactly as in 009.
        out.emitOrderUpdate(o, e.seq, OutputEvent.FLAG_CREATE, true, px, e.ingressNanos);

        // Event-driven matching: evaluate immediately instead of waiting for a poll tick.
        if (px != Px.NONE && isInTheMoney(o, px)) {
            autoFill(o, px, e);
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
        applyFill(o, o.remaining, execPx, true, e, px);
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
                autoFill(o, e.priceTicks, e);
                if (!o.isOpen()) {
                    continue; // entry at index i was swap-removed by applyFill
                }
            }
            i++;
        }
    }

    // ----- matching policy (preserved from 009, integer math) ----------------------------

    private boolean isInTheMoney(RestingOrder o, long px) {
        return o.side == InputEvent.SIDE_BUY ? px <= o.limitPx : px >= o.limitPx;
    }

    private void autoFill(RestingOrder o, long px, InputEvent e) {
        autoFillAttempts++;
        int remaining = o.remaining;
        int fillQty = remaining < fillFullThreshold ? remaining : Math.max(1, (remaining + 1) / 2);
        applyFill(o, fillQty, px, false, e, px);
        autoFillSuccess++;
    }

    private void applyFill(RestingOrder o, int fillQty, long execPx, boolean force, InputEvent e, long marketPx) {
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
            openRefs(o.securityId).removeValueUnordered(o.orderRef);
        }

        out.emitOrderUpdate(o, e.seq, flags, true, marketPx, e.ingressNanos);
        // Booking leaves as a typed event (FR-09B15); the output bridge feeds the existing
        // trade pipeline off the acknowledgement path.
        out.emitTradeBooked(o, fillQty, e.seq, e.ingressNanos);
        metrics.recordMatchLatency(System.nanoTime() - e.ingressNanos);
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

    public long eventsProcessed() {
        return eventsProcessed;
    }

    public long autoFillAttempts() {
        return autoFillAttempts;
    }

    public long autoFillSuccess() {
        return autoFillSuccess;
    }

    public long lastEventTimeMillis() {
        return lastEventTimeMillis;
    }

    public long blpSeq() {
        return blpSeq;
    }

    public long countOrdersNew() {
        return ordersNew;
    }

    public long countOrdersCancel() {
        return ordersCancel;
    }

    public long countForceFills() {
        return ordersForceFill;
    }

    public long countPriceTicks() {
        return priceTicks;
    }

    public long blpThreadId() {
        return blpThreadId;
    }
}
