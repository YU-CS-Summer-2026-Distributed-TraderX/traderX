package finos.traderx.accountservice.model;

/** Durable account-control outbox row; version is the monotonic source sequence. */
public record AccountControlEvent(long version, long sourceEpoch, String eventType, int accountId,
                                  String principal, boolean enabled, long sourceTimeMillis) {}
