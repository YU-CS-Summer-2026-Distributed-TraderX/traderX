# ADR-085: reference-data audits the control stream against its published flags

Status: accepted (yaakov, 2026-10-27). Requirements: YU04 FR-IMRG36, YU18 FR-CFR-01..05.

## Context

`stocks_control_outbox.published` is set once a row's JetStream publish is acknowledged and never revisited.
On 2026-10-27 the traderx NATS store moved to a new PVC (roll 2): all 533 rows stayed `published`, the new store
had no `TRADERX_CONTROL_SECURITY`, and nothing was ever published again until one new security. The gateway
replays the stream from its start, so its control replica held that one security.

## Decision

On the first poll and every `SECURITY_OUTBOX_AUDIT_INTERVAL_MS`, compare the published rows with the stream:
missing, fewer messages than published rows, or last version below the published watermark → mark every row
unpublished and let the existing ordered poller republish. A stream ahead of the watermark is an ALERT only.

The message count is the decisive test. A stream recreated by one newer delta has a last version equal to the
watermark while holding one message, which is exactly the production case; a last-version check alone passes it.

## Alternatives not taken

- **Last version only:** misses the case above.
- **Republish only the missing versions:** needs a per-version scan of the stream; a full republish is the same
  ordered path and the consumer applies duplicates idempotently.
- **Republish on a stream-ahead mismatch:** the outbox was restored behind the stream (versions may have been
  reused); republishing would interleave two histories. Operator action, not automatic repair.
- **Carry halts in the feed:** a republish, like any gateway restart, replays `enabled=true`. Making halts survive
  a replay is a change to the feed's payload and the gateway's apply, recorded as NFR-CFR-01, not made here.

## Consequences

A replaced NATS store is refilled by `reference-data` within one audit interval. The consumer side is not covered
here: on 2026-10-27 the gateway's push subscription did not come back after its stream was deleted under a live
connection (0 consumers until the gateway restarted), so a running gateway re-subscribing after a store replacement
is unverified. Each republish sends two sequenced events per security through consensus (a registration and an
enable).
