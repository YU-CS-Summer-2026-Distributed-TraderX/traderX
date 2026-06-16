package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.RingBuffer;

/**
 * The BLP's only side-effect channel (FR-09B15): claim/write/publish into the output ring.
 * The BLP is the sole producer (ProducerType.SINGLE). No allocation per emit — fields are
 * written into the pre-allocated slot in place.
 *
 * Booking and position-keeping are fused into the BLP (FR-09B08/FR-09B10): a fill emits its
 * order update, the {@code TradeBooked} event, and the resulting {@code PositionUpdated} as
 * one claim ({@link #emitFillWithTrade}); a market trade emits {@code TradeBooked} +
 * {@code PositionUpdated} ({@link #emitMarketTrade}). One publish per business event keeps
 * the consumer signaled once.
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

    /** Paired claim: fill order-update + TradeBooked + PositionUpdated, signaled once. */
    public void emitFillWithTrade(RestingOrder order, int fillQty, long tradePx, long tradeSeq,
                                  int newPosition, long avgCostTicks, long inputSeq, int flags,
                                  long marketPx, long ingressNanos) {
        long hi = ring.next(3);
        long updateSlot = hi - 2;
        long tradeSlot = hi - 1;
        long positionSlot = hi;
        try {
            writeOrderUpdate(ring.get(updateSlot), order, inputSeq, flags, true, marketPx, ingressNanos);
            writeTradeBooked(ring.get(tradeSlot), order.accountId, order.securityId, order.side, fillQty,
                tradePx, tradeSeq, inputSeq, order.updatedAtMillis, ingressNanos);
            writePositionUpdated(ring.get(positionSlot), order.accountId, order.securityId, newPosition,
                avgCostTicks, inputSeq, order.updatedAtMillis, ingressNanos);
        } finally {
            ring.publish(updateSlot, positionSlot);
        }
    }

    /** Market trade (FR-09B08): no order, no matching — TradeBooked + PositionUpdated. */
    public void emitMarketTrade(int accountId, int securityId, byte side, int qty, long tradePx,
                                long tradeSeq, int newPosition, long avgCostTicks, long inputSeq,
                                long eventTimeMillis, long ingressNanos) {
        long hi = ring.next(2);
        long tradeSlot = hi - 1;
        long positionSlot = hi;
        try {
            writeTradeBooked(ring.get(tradeSlot), accountId, securityId, side, qty, tradePx, tradeSeq,
                inputSeq, eventTimeMillis, ingressNanos);
            writePositionUpdated(ring.get(positionSlot), accountId, securityId, newPosition, avgCostTicks,
                inputSeq, eventTimeMillis, ingressNanos);
        } finally {
            ring.publish(tradeSlot, positionSlot);
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
            e.tradeSeq = 0;
            e.tradePx = Px.NONE;
            e.positionQty = 0;
            e.positionAvgCostTicks = 0L;
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
        e.tradeSeq = 0;
        e.tradePx = Px.NONE;
        e.positionQty = 0;
        e.positionAvgCostTicks = 0L;
        e.ingressNanos = ingressNanos;
    }

    private static void writeTradeBooked(OutputEvent e, int accountId, int securityId, byte side,
                                         int fillQty, long tradePx, long tradeSeq, long inputSeq,
                                         long updatedAtMillis, long ingressNanos) {
        e.kind = OutputEvent.KIND_TRADE_BOOKED;
        e.inputSeq = inputSeq;
        e.flags = 0;
        e.publishNats = true;            // booking leaves on the 009 /trades + /accounts/{id}/trades subjects
        e.orderRef = 0;
        e.accountId = accountId;
        e.securityId = securityId;
        e.side = side;
        e.quantity = fillQty;
        e.remainingQty = 0;
        e.limitPx = Px.NONE;
        e.status = 0;
        e.lastExecPx = tradePx;
        e.lastFillQty = fillQty;
        e.createdAtMillis = updatedAtMillis;
        e.updatedAtMillis = updatedAtMillis;
        e.marketPx = Px.NONE;
        e.tradeQty = fillQty;
        e.tradeSeq = tradeSeq;
        e.tradePx = tradePx;
        e.positionQty = 0;
        e.positionAvgCostTicks = 0L;
        e.ingressNanos = ingressNanos;
    }

    private static void writePositionUpdated(OutputEvent e, int accountId, int securityId, int positionQty,
                                             long avgCostTicks, long inputSeq, long updatedAtMillis,
                                             long ingressNanos) {
        e.kind = OutputEvent.KIND_POSITION_UPDATED;
        e.inputSeq = inputSeq;
        e.flags = 0;
        e.publishNats = true;            // position leaves on the 009 /accounts/{id}/positions subject
        e.orderRef = 0;
        e.accountId = accountId;
        e.securityId = securityId;
        e.side = 0;
        e.quantity = 0;
        e.remainingQty = 0;
        e.limitPx = Px.NONE;
        e.status = 0;
        e.lastExecPx = Px.NONE;
        e.lastFillQty = 0;
        e.createdAtMillis = updatedAtMillis;
        e.updatedAtMillis = updatedAtMillis;
        e.marketPx = Px.NONE;
        e.tradeQty = 0;
        e.tradeSeq = 0;
        e.tradePx = Px.NONE;
        e.positionQty = positionQty;
        e.positionAvgCostTicks = avgCostTicks;
        e.ingressNanos = ingressNanos;
    }
}
