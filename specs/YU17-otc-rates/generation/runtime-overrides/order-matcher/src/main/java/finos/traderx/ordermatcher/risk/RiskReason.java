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
    SELF_TRADE_PREVENTED,
    /**
     * YU17 (ADR-069, format-8 scope section 1.3): refused because the VENUE IS CLOSED -- not a
     * price, a limit or an entitlement. Distinguishable from {@code PRICE_COLLAR} and from every
     * risk cap at the engine ack's reason byte, which is what keeps a session refusal out of
     * {@code the-audit-surface-records-that-an-order-was-refused-not-why}.
     *
     * <p>APPENDED, never inserted -- see {@link #SELF_TRADE_PREVENTED} for why the ordinals are
     * load-bearing.
     */
    MARKET_CLOSED,
    /**
     * YU17 (ADR-069, decision b): this order was CANCELED because the session halted while it sat
     * in the pre-open queue. Deliberately NOT {@code MARKET_CLOSED}, though both are session
     * events: a client that was refused at the door ("we were closed, resubmit when we open") and a
     * client whose accepted, ref-holding order was cancelled out from under it ("the order you
     * hold is gone") must take different actions, and one reason for both would make them
     * indistinguishable. Like {@link #SELF_TRADE_PREVENTED} it rides a {@code STATUS_CANCELED}
     * order rather than a rejected one -- the order was accepted and then removed by a later event.
     *
     * <p>APPENDED, never inserted.
     */
    SESSION_CANCELED
}
