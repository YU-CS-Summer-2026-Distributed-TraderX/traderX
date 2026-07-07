# Functional Delta: YU04-durable-control-feeds over YU03-in-memory-risk-gateway

Requirement ids inherited from the original `in-memory-risk-gateway` spec (readable via
`git show in-memory-risk-gateway:specs/in-memory-risk-gateway/spec.md`). Status is for this state,
relative to YU03 slice 1's status (see `specs/YU03-in-memory-risk-gateway/requirements/functional-delta.md`).

| Req | YU03 status | YU04 status | Notes |
|---|---|---|---|
| FR-IMRG03 versioned replica records | Partial | **Done** | `AccountRecord`/`SecurityRecord` gain a real per-source `sourceVersion`; per-source `sourceEpoch` tracked in the new `ControlFeedSubscriber` (one instance per source). |
| FR-IMRG04 subscribe-buffer-snapshot bootstrap | Deferred | **Done** | `ReplicaBootstrap` rewritten to the ADR-019 5-step protocol per source: subscribe (ephemeral JetStream pull consumer, `DeliverPolicy.New`) and buffer, fetch watermarked snapshot, verify + atomically install, apply buffered deltas above the watermark in order, discard duplicates at/below it. |
| FR-IMRG05 readiness at high watermark | Partial | **Done** | Gateway readiness (`GatewayReplicaStore.ready()`) requires BOTH sources to have installed a valid snapshot and caught up to their observed high watermark at subscribe time; a partial/one-source-only bootstrap does not satisfy it. |
| FR-IMRG18 fail closed | Partial | **Done** | Feed loss (subscribe failure, snapshot fetch failure, checksum mismatch) now explicitly re-triggers the fail-closed path via `markNotReady()`, in addition to the pre-bootstrap and stale-price cases YU03 already covered. |
| FR-IMRG32 durable source feeds | Deferred | **Done** | `account-service` and `reference-data` each expose a watermarked snapshot endpoint (extends `GET /account/` / `GET /stocks`) and a durable JetStream stream of versioned deltas (`TRADERX_CONTROL_ACCOUNT`, `TRADERX_CONTROL_SECURITY`), fed by a transactional outbox (ADR-021). |
| FR-IMRG33 feed retention/replay/gap detection | Deferred | **Done** | JetStream file-backed streams provide retention/replay; per-source monotonic version + epoch let `ReplicaBootstrap` detect gaps/regressions/epoch changes without a separate sequencing service. |
| FR-IMRG34 quarantine invalid control updates | Deferred | **Done** | A gap, version regression, or epoch mismatch on either source's live stream stops applying that source's deltas, invalidates Gateway readiness, logs/counts the quarantine event (`traderx_replica_quarantine_total{source,reason}`), and restarts that source's bootstrap from step 1. The BLP is unaffected (Tier-2 authority already rejects unknown/disabled state — ADR-018). |
| FR-IMRG35 startup/degraded matrix | Partial | **Partial → extended** | `system/runtime-topology.md` gains the durable-feed rows (subscribe failure, snapshot checksum mismatch, quarantine/rebootstrap) to the existing matrix; still no generic fail-open path. |

## Unaffected by this state (unchanged from YU03, not re-litigated here)

FR-IMRG01/02/06–17/19–24/26/27/30/31/40–45 — this state only changes how the Gateway replica's
account/security state gets populated and kept current; it does not touch screening logic, the BLP
decision pipeline, journal/snapshot format, the control-plane admin API's request/response shapes,
idempotency, or reservation mechanics. FR-IMRG25 (multi-Gateway) remains deferred — see `spec.md`
"Out of scope."
