# ADR-019: Watermarked Snapshot plus Buffered Deltas

**Status:** Accepted for specification — **DEFERRED in YU03 slice 1** (see "Status" below)
**Date:** 2026-06-22 (forward-ported to YU03 2026-07-06)
**State:** `YU03-in-memory-risk-gateway` (parent `YU02-lmax-kubernetes`)

## Context

Gateway validation must use local account/reference/risk state and prove that the replica is
complete. Fetching `GET all` and subscribing afterward creates a race: a change committed between
the snapshot read and subscription can be lost. Subscribing after startup without a snapshot can
require unbounded replay. A time-to-live only detects silence; it cannot prove that all versions
were received.

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
- **Subscribe only and replay from origin:** valid only with guaranteed full retention and bounded
  recovery time.
- **TTL-only cache freshness:** rejected because it cannot detect missed versions.
- **Periodic full polling:** rejected because it adds load, update delay, allocation, and gives no
  exact command-time version.
- **Database read replica:** rejected as command-path coupling with no event ordering vs. the BLP journal.

## Status in YU03 slice 1 (DEFERRED)

This ADR describes the **target** replica-bootstrap contract, which requires durable, watermarked,
versioned change-log (outbox) sources on account-service and reference-data (FR-IMRG32/33). Those
source-side streams are **not** part of slice 1, so the full protocol is deferred to a later commit
of this roadmap item. What slice 1 ships instead, and why it is safe:

- **One-shot bootstrap sequenced through the journal.** `ReplicaBootstrap` fetches the account and
  security universe once at startup (cold path, PRIMARY only, before serving) via plain REST and
  sequences each record as a versioned control event (ADR-020). Because there is a single
  co-located control plane and no live external delta stream yet, there is no snapshot/subscribe
  handoff race for it to lose — the race ADR-019 solves does not exist until durable source deltas
  are added.
- **Fail-closed readiness.** The replica screens closed (`CONTROL_STATE_STALE` → HTTP 503) until the
  seed image + SymbolTable id alignment complete; names outside the installed image reject as
  `UNKNOWN_SECURITY`/`UNKNOWN_ACCOUNT`. Monotonic versions and the epoch field already exist on the
  replica records, so the epoch/gap machinery has a place to land.
- **Deterministic recovery** comes from the BLP journal + snapshot v3 (ADR-020), not from replica
  state, so replica bootstrap correctness is not on the recovery-determinism critical path.

Adopting the full ADR = adding the source-side outbox/snapshot-watermark APIs and switching
`ReplicaBootstrap` to subscribe-buffer-snapshot-catchup. No BLP or decision-path change is required.

## Validation (when adopted)

- Inject updates immediately before, during, and after snapshot creation.
- Duplicate/reorder/gap/epoch-change fixtures.
- Bootstrap buffer capacity/overflow test.
- Checksum/schema mismatch test.
- Readiness remains false until high-watermark catch-up.
