package finos.traderx.ordermatcher.risk;

/** Stable bounded rejection reasons from the in-memory-risk-gateway contract. */
public enum RiskReason {
    ACCEPTED,
    INVALID,
    NOT_ENTITLED,
    UNKNOWN_ACCOUNT,
    ACCOUNT_DISABLED,
    UNKNOWN_SECURITY,
    SECURITY_DISABLED,
    RESTRICTED,
    KILL_SWITCH,
    PRICE_MISSING,
    PRICE_STALE,
    PRICE_COLLAR,
    ORDER_SIZE,
    ORDER_NOTIONAL,
    CREDIT_LIMIT,
    POSITION_LIMIT,
    CONCENTRATION_LIMIT,
    DUPLICATE,
    CONTROL_STATE_STALE,
    CAPACITY,
    INTERNAL_POLICY_ERROR,
    /**
     * YU13 / ADR-057: this resting order was cancelled by the venue because an aggressor from the
     * SAME account reached it — self-trade prevention, cancel-oldest. It is the only value here
     * that is not a pre-trade rejection: the order was accepted, rested, and was then removed by a
     * later event, so it rides a {@code STATUS_CANCELED} order rather than a {@code STATUS_REJECTED}
     * one. That is what lets a client tell "the venue prevented a self-trade" from "my cancel
     * succeeded" (reason ACCEPTED) and from "my order was rejected".
     *
     * <p>APPENDED, never inserted. The ordinal is serialized into every snapshot's order rows via
     * {@code RestingOrder.riskReason}; inserting a value renumbers every ordinal above it and
     * silently misdecodes every snapshot ever written.
     */
    SELF_TRADE_PREVENTED
}
