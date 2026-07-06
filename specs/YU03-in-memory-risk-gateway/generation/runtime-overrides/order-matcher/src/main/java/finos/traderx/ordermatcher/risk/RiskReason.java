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
    INTERNAL_POLICY_ERROR
}
