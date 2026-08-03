package finos.traderx.ordermatcher.risk;

/** One account existence/identity fact from the durable account-service control feed (ADR-021). */
public record AccountDelta(int accountId, String displayName) {}
