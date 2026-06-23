# ADR-019: Watermarked Snapshot plus Buffered Deltas

**Status:** Accepted for specification  
**Date:** 2026-06-22  
**State:** `in-memory-risk-gateway`

## Context

Gateway validation must use local account/reference/risk state and prove that the replica is complete.
Fetching `GET all` and subscribing afterward creates a race: a change committed between the snapshot
read and subscription can be lost. Subscribing after startup without a snapshot can require unbounded
replay. A time-to-live only detects silence; it cannot prove that all versions were received.

## Decision

For every mandatory external replica:

1. Subscribe to a durable versioned delta stream and buffer events.
2. Fetch a complete internally consistent snapshot with source epoch and watermark `W`.
3. Verify schema/count/checksum and atomically install the snapshot.
4. Apply buffered deltas with the same epoch and version greater than `W` in order.
5. Continue live consumption and mark ready only after reaching the observed stream high watermark.

Every source exposes monotonic versions and an epoch. Duplicate versions are idempotent. A gap,
regression, or epoch change invalidates readiness and forces re-bootstrap.

## Alternatives Considered

- **Fetch then subscribe:** rejected because it loses handoff-window changes.
- **Subscribe only and replay from origin:** potentially valid but impractical without guaranteed full
  retention and bounded recovery time.
- **TTL-only cache freshness:** rejected because it cannot detect missed versions.
- **Periodic full polling:** rejected because it adds load, update delay, allocation, and no exact
  command-time version.
- **Database read replica:** rejected as command-path coupling and does not provide event ordering with
  the BLP journal.

## Consequences

Positive:

- replica completeness is provable;
- restart/re-bootstrap is bounded by snapshot plus retained tail;
- stale/gapped state is explicit and observable;
- duplicate delivery is safe.

Costs:

- source services need snapshot watermark and durable change-log/outbox semantics;
- Gateways need a bounded bootstrap buffer and atomic image publication;
- epoch/version/checksum become durable contracts;
- buffer overflow or retention loss requires another snapshot rather than best-effort continuation.

## Validation

- Inject updates immediately before, during, and after snapshot creation.
- Duplicate/reorder/gap/epoch-change fixtures.
- Bootstrap buffer capacity/overflow test.
- Checksum/schema mismatch test.
- Readiness remains false until high-watermark catch-up.

