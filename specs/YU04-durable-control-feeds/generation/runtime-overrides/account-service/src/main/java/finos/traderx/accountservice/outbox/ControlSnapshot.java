package finos.traderx.accountservice.outbox;

import finos.traderx.accountservice.model.Account;
import java.util.List;

/**
 * Watermarked snapshot response for {@code GET /account/control-snapshot} (ADR-019 step 2,
 * ADR-021). Additive/new endpoint — the existing {@code GET /account/} array shape is untouched.
 */
public record ControlSnapshot(
    int schemaVersion,
    long sourceEpoch,
    long watermark,
    int count,
    String checksum,
    List<Account> records) {}
