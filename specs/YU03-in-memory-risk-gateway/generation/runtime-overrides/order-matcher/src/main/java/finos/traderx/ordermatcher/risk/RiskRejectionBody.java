package finos.traderx.ordermatcher.risk;

/** Machine-readable rejection response. */
public record RiskRejectionBody(
    String clientOrderId,
    String decision,
    RiskReason reason,
    long policyVersion,
    long commandSequence
) {}
