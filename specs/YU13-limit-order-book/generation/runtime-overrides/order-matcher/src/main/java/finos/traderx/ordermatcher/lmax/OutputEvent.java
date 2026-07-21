package finos.traderx.ordermatcher.lmax;

/**
 * Output-ring slot holder (single producer: the BLP — FR-09B20). Carries a full order
 * snapshot so downstream handlers (marshaller/read-model, NATS bridge, projector) never
 * read BLP state. {@code ingressNanos} is carried through from the input event so true
 * end-to-end latency is recorded at egress (NFR-09B01).
 */
public final class OutputEvent {
    public static final byte KIND_ORDER_ACCEPTED = 1;
    public static final byte KIND_ORDER_REJECTED = 2;
    public static final byte KIND_ORDER_PARTIALLY_FILLED = 3;
    public static final byte KIND_ORDER_FILLED = 4;
    public static final byte KIND_ORDER_CANCELED = 5;
    public static final byte KIND_TRADE_BOOKED = 6;
    public static final byte KIND_POSITION_UPDATED = 7;
    public static final byte KIND_ORDER_NOT_FOUND = 8;
    /** Market-trade risk decision acks (in-memory-risk-gateway): correlate the gateway's
     *  TRADE_NEW ack future; REJECTED never books a trade or moves a position (FR-IMRG23). */
    public static final byte KIND_TRADE_ACCEPTED = 9;
    public static final byte KIND_TRADE_REJECTED = 10;

    // Lifecycle counter flags (parity with 009's traderx_order_events_total labels).
    public static final int FLAG_CREATE = 1;
    public static final int FLAG_PARTIAL_FILL = 1 << 1;
    public static final int FLAG_FILL = 1 << 2;
    public static final int FLAG_CANCEL = 1 << 3;
    public static final int FLAG_REJECT = 1 << 4;
    public static final int FLAG_FORCE_FILL = 1 << 5;
    /**
     * YU13 crossing book: this order update belongs to a RESTING order hit by someone else's
     * aggressor, not to the input that is being acked. A session's direct lifecycle responses
     * (create/reject/cancel/own-fill) never carry it; gateway ack correlation counts only
     * non-resting order-lifecycle acks, so counterparty updates interleaving on the shared
     * egress stream can no longer skew offer/ack accounting (FR-LOB07).
     */
    public static final int FLAG_RESTING_UPDATE = 1 << 6;

    public long inputSeq;         // correlates gateway acks (request/response events)
    public byte kind;
    public int flags;
    public boolean publishNats;   // bridge this update onto the 009 NATS subjects

    public int orderRef;
    public int accountId;
    public int securityId;
    public byte side;
    public int quantity;
    public int remainingQty;
    public long limitPx;
    public byte status;           // OrderStatus ordinal
    public byte riskReason;       // RiskReason ordinal (order lifecycle + trade decision kinds)
    public long lastExecPx;       // Px.NONE when absent
    public int lastFillQty;       // 0 when absent
    public long createdAtMillis;
    public long updatedAtMillis;
    public long marketPx;         // BLP's last trade price for the security (Px.NONE when unknown)

    public int tradeQty;          // KIND_TRADE_BOOKED: fill quantity booked as a trade
    public long tradeSeq;         // KIND_TRADE_BOOKED: BLP-assigned global trade number (deterministic id)
    public long tradePx;          // KIND_TRADE_BOOKED: stamped execution price (Px ticks; Px.NONE if no tick)
    public int positionQty;       // KIND_POSITION_UPDATED: account's net position after the fill/trade
    public long positionAvgCostTicks; // KIND_POSITION_UPDATED: weighted average cost basis (Px ticks)
    public long averageCostBasisPx;   // alias retained for direct UI payloads/tests
    public long ingressNanos;

    public static OutputEvent newInstance() {
        return new OutputEvent();
    }

    public static boolean isOrderLifecycleKind(byte kind) {
        return kind >= KIND_ORDER_ACCEPTED && kind <= KIND_ORDER_CANCELED;
    }
}
