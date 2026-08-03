# Runtime Topology & Startup/Degraded Matrix: YU04

Extends `specs/YU03-in-memory-risk-gateway/system/runtime-topology.md`'s matrix (FR-IMRG35) with
the rows this state's durable-feed machinery adds. The YU03 matrix's existing rows (kill switch,
unknown/disabled account, restricted security, price staleness, gateway/BLP disagreement,
idempotency capacity) are unchanged and not repeated here.

## Deployment topology (delta from YU03)

Unchanged: order-matcher remains the BLP (StatefulSet, single-BLP or HA), risk gateway folded into
the same JVM. **New:** `account-service` and `reference-data` are no longer read-only, cold-path
outbound calls from order-matcher's perspective — each now runs a background outbox-publisher loop
and holds a durable JetStream stream open. Neither service's own deployment topology changes
(still a plain Deployment, not a StatefulSet) — the durability lives in JetStream (file-backed
streams) and each service's own MariaDB rows, not in local pod state.

## Readiness (extends YU03's readiness section)

- **Risk replica readiness** now requires **both** `ControlFeedSubscriber` instances (account,
  security) to report `ready` — i.e. both have installed a valid snapshot and caught up to their
  observed high watermark. YU03's single combined readiness flag becomes the AND of two per-source
  flags. Until both are ready, screening still returns `CONTROL_STATE_STALE` exactly as in YU03 —
  no new rejection reason, same fail-closed contract.
- A source's readiness can flip back to false **after** initial bootstrap (this is new in this
  state) if that source's live stream shows a gap, version regression, or epoch change — see below.

## Startup / degraded behavior matrix — new rows for this state

| Condition | Gateway (edge) | Notes |
|---|---|---|
| JetStream subscribe fails (broker down/unreachable) for a source | reject `CONTROL_STATE_STALE` → **503** for that source's readiness contribution; retry with backoff, same pattern as YU03's bootstrap retry | Independent per source — the other source can still become ready. |
| Snapshot fetch fails (`account-service`/`reference-data` down) | same as above | Unchanged failure mode from YU03's one-shot fetch, just now gates the new per-source subscribe-first step too. |
| Snapshot checksum/count/schema mismatch | reject `CONTROL_STATE_STALE`, log + count as a quarantine event, retry whole bootstrap for that source | New in this state (ADR-019 step 3; YU03 had no snapshot to verify against a stream). |
| Live delta gap (version jumps by more than 1) | that source's readiness → false, quarantine + full re-bootstrap of that source only (fresh ephemeral consumer, fresh snapshot) | FR-IMRG34. The other source and the BLP authority are unaffected — BLP still rejects unknown/disabled state independently (ADR-018), so a quarantined Gateway source fails safe, not open. |
| Live delta version regression (≤ last applied) | discarded as a duplicate (idempotent), not a quarantine — this is the expected at-least-once redelivery case, not a fault | Distinguishes ordinary duplicate redelivery from an actual gap; only a *forward* jump larger than 1 is a gap. |
| Source epoch changes mid-stream | quarantine + full re-bootstrap of that source (treated the same as a gap) | FR-IMRG34; expected only after a deliberate source-side resync, never in normal operation. |
| Pre-snapshot delta buffer overflows (`risk.bootstrap.buffer-capacity` exceeded) | quarantine + restart bootstrap for that source with a fresh subscribe | NFR-IMRG-OUTBOX-03; bounded-capacity discipline extended to this new buffer (FR-IMRG26). |
| Outbox publisher down/stalled (either service) | Gateway stays on its last-installed snapshot + already-applied deltas; no readiness flip on its own | A stalled publisher looks identical to "no changes happened" from the consumer's side — this is a source-side operational concern (`traderx_outbox_publish_lag_seconds`/`traderx_outbox_unpublished_rows`), not a Gateway fail-closed trigger, since staleness of the *replica* (not the source) is what FR-IMRG18 gates on. |

**No generic fail-open exists here either**: every new failure mode above resolves to "reject /
quarantine / retry," never to serving admission against an unverified or partially-applied source
state.

## Deferred (later commits, unchanged from YU03)

Multi-Gateway concurrency (FR-IMRG25); entitlement replica (auth roadmap item); Grafana
dashboard/alerts for the new feed-health metrics (see `tasks.md`).
