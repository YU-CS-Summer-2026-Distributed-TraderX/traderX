package finos.traderx.ordermatcher.risk;

/** Domain rejection surfaced by the Gateway as a stable 4xx response. */
public final class RiskRejectedException extends RuntimeException {
    private final String clientOrderId;
    private final RiskReason reason;
    private final long policyVersion;
    private final long commandSequence;

    public RiskRejectedException(RiskReason reason, long policyVersion, long commandSequence) {
        this(null, reason, policyVersion, commandSequence);
    }

    public RiskRejectedException(String clientOrderId, RiskReason reason, long policyVersion,
                                 long commandSequence) {
        super(reason.name());
        this.clientOrderId = clientOrderId;
        this.reason = reason;
        this.policyVersion = policyVersion;
        this.commandSequence = commandSequence;
    }

    public String clientOrderId() {
        return clientOrderId;
    }

    public RiskReason reason() {
        return reason;
    }

    public long policyVersion() {
        return policyVersion;
    }

    public long commandSequence() {
        return commandSequence;
    }
}
