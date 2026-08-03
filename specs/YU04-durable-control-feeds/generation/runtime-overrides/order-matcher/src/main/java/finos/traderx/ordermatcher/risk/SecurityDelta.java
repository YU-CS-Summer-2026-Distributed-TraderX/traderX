package finos.traderx.ordermatcher.risk;

/** One security existence/identity fact from the durable reference-data control feed (ADR-021). */
public record SecurityDelta(String ticker, String companyName) {}
