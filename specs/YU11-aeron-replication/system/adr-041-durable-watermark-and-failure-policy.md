# ADR-041: Exact journal watermark with degraded-solo default and strict opt-in

Status: Accepted

## Context

The inherited on-ring follower ACK means the input is queued, not forced to the follower journal.
YU10's durable mode uses the final ring gating sequence, which is safely post-force but also
post-apply. The primary journal already fsyncs before the matching path advances and is the
business durability authority. Halting on every follower reschedule would make two-replica HA
less available than single-BLP operation.

## Decision

- The follower ACK agent reads the exact `Journaler.journaledSeq()` post-force watermark and maps
  local sequences to contiguous primary `(epoch,inputSeq)` values.
- `BLP_REPLICATION_ACK_MODE=onring|durable` retains on-ring as the default and makes the exact
  durable watermark selectable for NATS and Aeron.
- `BLP_REPLICATION_FAILURE_POLICY=degraded-solo|strict` defaults to degraded-solo.
- Degraded-solo alerts and continues against the primary journal while peer reconnect/catch-up
  runs.
- Strict requires durable ACK and closes admission for peer connection, gap, schema, Archive, or
  ACK faults. Strict plus on-ring is rejected at startup.
- Promotion readiness remains stronger than durable ACK: journaled and applied watermarks must
  both reach the observed live high watermark.

## Consequences

ACK semantics become honest and independently measurable without charging routine follower loss
to total availability. Deployments can opt into synchronous replica durability. The configuration
matrix is validated at startup and exposed in health/metrics.
