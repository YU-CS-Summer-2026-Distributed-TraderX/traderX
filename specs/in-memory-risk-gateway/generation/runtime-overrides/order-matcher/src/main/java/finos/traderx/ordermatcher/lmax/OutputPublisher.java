package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.RingBuffer;
import finos.traderx.ordermatcher.risk.RiskReason;

/**
 * The BLP's only side-effect channel (FR-09B15): claim/write/publish into the output ring.
 * The BLP is the sole producer (ProducerType.SINGLE). No allocation per emit — fields are
 * written into the pre-allocated slot in place.
 *
 * Booking and position-keeping are fused into the BLP (FR-09B08/FR-09B10): a fill emits its
 * order update, the {@code TradeBooked} event, and the resulting {@code PositionUpdated} as
 * one claim; a market trade emits {@code TradeBooked} + {@code PositionUpdated}.
 */
public final class OutputPublisher {
    @FunctionalInterface
    public interface RecoverySink {
        void onRecovered(OutputEvent event, long sequence, boolean endOfBatch);
    }

    private RingBuffer<OutputEvent> ring;
    private RecoverySink recoverySink;
    private final OutputEvent recoveryEvent = new OutputEvent();
    private long recoverySequence;

    public OutputPublisher(RingBuffer<OutputEvent> ring) {
        this.ring = ring;
    }

    public void attach(RingBuffer<OutputEvent> ring) {
        this.ring = ring;
        this.recoverySink = null;
    }

    public void attachRecoverySink(RecoverySink sink) {
        this.recoverySink = sink;
    }

    public void emitOrderUpdate(RestingOrder order, long inputSeq, int flags, boolean publishNats,
                                long marketPx, long ingressNanos) {
        if (ring == null) {
            if (recoverySink != null) {
                writeOrderUpdate(recoveryEvent, order, inputSeq, flags, publishNats, marketPx, ingressNanos);
                recovered(true);
            }
            return;
        }
        long seq = ring.next();
        try {
            writeOrderUpdate(ring.get(seq), order, inputSeq, flags, publishNats, marketPx, ingressNanos);
        } finally {
            ring.publish(seq);
        }
    }

    /** Paired claim: fill order-update + TradeBooked + PositionUpdated, signaled once. */
    public void emitFillWithTradeAndPosition(RestingOrder order, int fillQty, long tradePx, long tradeSeq,
                                             int newPosition, long avgCostTicks, long inputSeq, int flags,
                                             long marketPx, long ingressNanos) {
        if (ring == null) {
            if (recoverySink != null) {
                writeOrderUpdate(recoveryEvent, order, inputSeq, flags, true, marketPx, ingressNanos);
                recovered(false);
                writeTradeBooked(recoveryEvent, order.accountId, order.securityId, order.side, fillQty,
                    tradePx, tradeSeq, inputSeq, order.updatedAtMillis, ingressNanos);
                recovered(false);
                writePositionUpdated(recoveryEvent, order.accountId, order.securityId, newPosition,
                    avgCostTicks, inputSeq, order.updatedAtMillis, ingressNanos);
                recovered(true);
            }
            return;
        }
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
        if (ring == null) {
            if (recoverySink != null) {
                writeTradeBooked(recoveryEvent, accountId, securityId, side, qty, tradePx, tradeSeq,
                    inputSeq, eventTimeMillis, ingressNanos);
                recovered(false);
                writePositionUpdated(recoveryEvent, accountId, securityId, newPosition, avgCostTicks,
                    inputSeq, eventTimeMillis, ingressNanos);
                recovered(true);
            }
            return;
        }
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
        if (ring == null) {
            if (recoverySink != null) {
                writeOrderNotFound(recoveryEvent, inputSeq, ingressNanos);
                recovered(true);
            }
            return;
        }
        long seq = ring.next();
        try {
            writeOrderNotFound(ring.get(seq), inputSeq, ingressNanos);
        } finally {
            ring.publish(seq);
        }
    }

    public void emitTradeDecision(long inputSeq, RiskReason decision, long ingressNanos) {
        if (ring == null) {
            if (recoverySink != null) {
                writeTradeDecision(recoveryEvent, inputSeq, decision, ingressNanos);
                recovered(true);
            }
            return;
        }
        long seq = ring.next();
        try {
            writeTradeDecision(ring.get(seq), inputSeq, decision, ingressNanos);
        } finally {
            ring.publish(seq);
        }
    }

    private void recovered(boolean endOfBatch) {
        recoverySink.onRecovered(recoveryEvent, recoverySequence++, endOfBatch);
    }

    private static void writeOrderNotFound(OutputEvent e, long inputSeq, long ingressNanos) {
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
        e.averageCostBasisPx = Px.NONE;
        e.riskReason = (byte) RiskReason.ACCEPTED.ordinal();
        e.marketPx = Px.NONE;
        e.ingressNanos = ingressNanos;
    }

    private static void writeTradeDecision(OutputEvent e, long inputSeq, RiskReason decision,
                                           long ingressNanos) {
        e.kind = decision == RiskReason.ACCEPTED
            ? OutputEvent.KIND_TRADE_ACCEPTED : OutputEvent.KIND_TRADE_REJECTED;
        e.inputSeq = inputSeq;
        e.flags = 0;
        e.publishNats = false;
        e.orderRef = 0;
        e.accountId = 0;
        e.securityId = 0;
        e.quantity = 0;
        e.remainingQty = 0;
        e.status = 0;
        e.riskReason = (byte) decision.ordinal();
        e.tradeQty = 0;
        e.tradeSeq = 0;
        e.tradePx = Px.NONE;
        e.marketPx = Px.NONE;
        e.ingressNanos = ingressNanos;
    }

    private static void writeOrderUpdate(OutputEvent e, RestingOrder order, long inputSeq, int flags,
                                         boolean publishNats, long marketPx, long ingressNanos) {
        e.kind = orderKind(order, flags);
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
        e.riskReason = order.riskReason;
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
        e.averageCostBasisPx = Px.NONE;
        e.ingressNanos = ingressNanos;
    }

    private static byte orderKind(RestingOrder order, int flags) {
        if ((flags & OutputEvent.FLAG_REJECT) != 0) {
            return OutputEvent.KIND_ORDER_REJECTED;
        }
        if ((flags & OutputEvent.FLAG_CANCEL) != 0) {
            return OutputEvent.KIND_ORDER_CANCELED;
        }
        if ((flags & OutputEvent.FLAG_FILL) != 0) {
            return OutputEvent.KIND_ORDER_FILLED;
        }
        if ((flags & OutputEvent.FLAG_PARTIAL_FILL) != 0) {
            return OutputEvent.KIND_ORDER_PARTIALLY_FILLED;
        }
        if ((flags & OutputEvent.FLAG_CREATE) != 0) {
            return OutputEvent.KIND_ORDER_ACCEPTED;
        }
        return switch (order.status) {
            case RestingOrder.STATUS_CANCELED -> OutputEvent.KIND_ORDER_CANCELED;
            case RestingOrder.STATUS_FILLED -> OutputEvent.KIND_ORDER_FILLED;
            case RestingOrder.STATUS_PARTIALLY_FILLED -> OutputEvent.KIND_ORDER_PARTIALLY_FILLED;
            default -> OutputEvent.KIND_ORDER_ACCEPTED;
        };
    }

    private static void writeTradeBooked(OutputEvent e, int accountId, int securityId, byte side,
                                         int fillQty, long tradePx, long tradeSeq, long inputSeq,
                                         long updatedAtMillis, long ingressNanos) {
        e.kind = OutputEvent.KIND_TRADE_BOOKED;
        e.inputSeq = inputSeq;
        e.flags = 0;
        e.publishNats = false;
        e.orderRef = 0;
        e.accountId = accountId;
        e.securityId = securityId;
        e.side = side;
        e.quantity = fillQty;
        e.remainingQty = 0;
        e.limitPx = Px.NONE;
        e.status = 0;
        e.riskReason = (byte) RiskReason.ACCEPTED.ordinal();
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
        e.averageCostBasisPx = Px.NONE;
        e.ingressNanos = ingressNanos;
    }

    private static void writePositionUpdated(OutputEvent e, int accountId, int securityId, int positionQty,
                                             long avgCostTicks, long inputSeq, long updatedAtMillis,
                                             long ingressNanos) {
        e.kind = OutputEvent.KIND_POSITION_UPDATED;
        e.inputSeq = inputSeq;
        e.flags = 0;
        e.publishNats = false;
        e.orderRef = 0;
        e.accountId = accountId;
        e.securityId = securityId;
        e.side = 0;
        e.quantity = 0;
        e.remainingQty = 0;
        e.limitPx = Px.NONE;
        e.status = 0;
        e.riskReason = (byte) RiskReason.ACCEPTED.ordinal();
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
        e.averageCostBasisPx = avgCostTicks;
        e.ingressNanos = ingressNanos;
    }
}
