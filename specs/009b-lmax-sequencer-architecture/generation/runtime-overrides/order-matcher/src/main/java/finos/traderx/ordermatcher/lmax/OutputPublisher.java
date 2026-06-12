package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.RingBuffer;

/**
 * The BLP's only side-effect channel (FR-09B15): claim/write/publish into the output ring.
 * The BLP is the sole producer (ProducerType.SINGLE). No allocation per emit — fields are
 * written into the pre-allocated slot in place. A fill's order update and its TradeBooked
 * event are claimed as one pair ({@link #emitFillWithTrade}) so the BLP pays a single
 * publish (one consumer signal) per fill.
 */
public final class OutputPublisher {
    private final RingBuffer<OutputEvent> ring;

    public OutputPublisher(RingBuffer<OutputEvent> ring) {
        this.ring = ring;
    }

    public void emitOrderUpdate(RestingOrder order, long inputSeq, int flags, boolean publishNats,
                                long marketPx, long ingressNanos) {
        long seq = ring.next();
        try {
            writeOrderUpdate(ring.get(seq), order, inputSeq, flags, publishNats, marketPx, ingressNanos);
        } finally {
            ring.publish(seq);
        }
    }

    /** Paired claim: fill update + TradeBooked published together, signalled once. */
    public void emitFillWithTrade(RestingOrder order, int fillQty, long inputSeq, int flags,
                                  long marketPx, long ingressNanos) {
        long tradeSeq = ring.next(2);
        long updateSeq = tradeSeq - 1;
        try {
            writeOrderUpdate(ring.get(updateSeq), order, inputSeq, flags, true, marketPx, ingressNanos);
            writeTradeBooked(ring.get(tradeSeq), order, fillQty, inputSeq, ingressNanos);
        } finally {
            ring.publish(updateSeq, tradeSeq);
        }
    }

    public void emitTradeBooked(RestingOrder order, int fillQty, long inputSeq, long ingressNanos) {
        long seq = ring.next();
        try {
            writeTradeBooked(ring.get(seq), order, fillQty, inputSeq, ingressNanos);
        } finally {
            ring.publish(seq);
        }
    }

    public void emitOrderNotFound(long inputSeq, long ingressNanos) {
        long seq = ring.next();
        try {
            OutputEvent e = ring.get(seq);
            e.kind = OutputEvent.KIND_ORDER_NOT_FOUND;
            e.inputSeq = inputSeq;
            e.flags = 0;
            e.publishNats = false;
            e.orderRef = 0;
            e.tradeQty = 0;
            e.marketPx = Px.NONE;
            e.ingressNanos = ingressNanos;
        } finally {
            ring.publish(seq);
        }
    }

    private static void writeOrderUpdate(OutputEvent e, RestingOrder order, long inputSeq, int flags,
                                         boolean publishNats, long marketPx, long ingressNanos) {
        e.kind = OutputEvent.KIND_ORDER_UPDATE;
        e.inputSeq = inputSeq;
        e.flags = flags;
        e.publishNats = publishNats;
        e.orderRef = order.orderRef;
        e.accountId = order.accountId;
        e.securityId = order.securityId;
        e.side = order.side;
        e.quantity = order.quantity;
        e.remainingQty = order.remaining;
        e.limitPx = order.limitPx;
        e.status = order.status;
        e.lastExecPx = order.lastExecPx;
        e.lastFillQty = order.lastFillQty;
        e.createdAtMillis = order.createdAtMillis;
        e.updatedAtMillis = order.updatedAtMillis;
        e.marketPx = marketPx;
        e.tradeQty = 0;
        e.ingressNanos = ingressNanos;
    }

    private static void writeTradeBooked(OutputEvent e, RestingOrder order, int fillQty, long inputSeq,
                                         long ingressNanos) {
        e.kind = OutputEvent.KIND_TRADE_BOOKED;
        e.inputSeq = inputSeq;
        e.flags = 0;
        e.publishNats = false;
        e.orderRef = order.orderRef;
        e.accountId = order.accountId;
        e.securityId = order.securityId;
        e.side = order.side;
        e.quantity = order.quantity;
        e.remainingQty = order.remaining;
        e.limitPx = order.limitPx;
        e.status = order.status;
        e.lastExecPx = order.lastExecPx;
        e.lastFillQty = order.lastFillQty;
        e.createdAtMillis = order.createdAtMillis;
        e.updatedAtMillis = order.updatedAtMillis;
        e.marketPx = Px.NONE;
        e.tradeQty = fillQty;
        e.ingressNanos = ingressNanos;
    }
}
