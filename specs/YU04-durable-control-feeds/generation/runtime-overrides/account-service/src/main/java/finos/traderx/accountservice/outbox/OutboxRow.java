package finos.traderx.accountservice.outbox;

import java.time.Instant;

/** One unpublished-or-published row of {@code account_control_outbox} (ADR-021). */
public record OutboxRow(long version, int accountId, String displayName, Instant createdAt) {}
