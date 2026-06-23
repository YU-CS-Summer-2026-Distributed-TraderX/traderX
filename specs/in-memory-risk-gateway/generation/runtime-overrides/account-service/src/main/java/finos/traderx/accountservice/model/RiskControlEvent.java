package finos.traderx.accountservice.model;

/** Durable risk-control source event. Version is the global monotonic control-source sequence. */
public record RiskControlEvent(long version, long sourceEpoch, String eventType, String aggregateKey,
                               boolean enabled, long policyVersion, int maxPositionQuantity,
                               long maxConcentrationNotionalTicks, String operator,
                               long sourceTimeMillis) {}
