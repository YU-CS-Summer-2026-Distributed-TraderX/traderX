# Feature Specification: Durable Control Feeds for the Risk Gateway (state YU04)

**State id**: `YU04-durable-control-feeds`
**Parent state**: `YU03-in-memory-risk-gateway`
**Created**: 2026-07-06
**Status**: Spec pack in progress (see `generation/implementation-status.md` for what is done vs deferred)
**Input**: Adoption of ADR-019 (`specs/YU03-in-memory-risk-gateway/system/adr-019-watermarked-replica-bootstrap.md`),
which YU03 shipped as a fully-specified target design but explicitly deferred (see that ADR's
"Status in YU03 slice 1" section). This state closes that deferral.

This state replaces `ReplicaBootstrap`'s one-shot REST fetch (safe only because YU03 slice 1 has a
single co-located Gateway with no live external delta stream to race against) with the full
ADR-019 subscribe-buffer-snapshot-catchup protocol, backed by real durable outbox feeds on
`account-service` and `reference-data`. It is the first roadmap item to modify those two services'
persistence layers — everything through YU03 was order-matcher-only.

## Requirements

The `IMRG` requirement namespace is inherited verbatim (see `specs/YU03-in-memory-risk-gateway/spec.md`
and the original `in-memory-risk-gateway` branch spec). This state implements the subset YU03 left
deferred; per-requirement status recorded in `requirements/functional-delta.md`. Headline coverage:

- **Implemented by this state**: FR-IMRG04 (subscribe-buffer-snapshot bootstrap), FR-IMRG05
  (readiness at observed high watermark), FR-IMRG32/33 (durable, watermarked, versioned source
  feeds with retention/replay/gap-detection), FR-IMRG34 (quarantine of invalid/out-of-order
  control updates). FR-IMRG03 (versioned replica records) moves from "internally assigned only" to
  "carries a real per-source epoch/watermark."
- **Still out of scope** (unaffected by this state, tracked in YU03's backlog): entitlements
  (FR-IMRG02's entitlement replica, FR-IMRG30's full authn — blocked on the real-auth roadmap
  item), multi-Gateway concurrency (FR-IMRG25 — this state is a *prerequisite* for it, see
  "Out of scope" below), SBE contract, order expiry, perf-profile acceptance runs.

## What changes, at a glance

1. **`account-service`** (Java/Spring/MariaDB via JPA) gains a transactional outbox table for
   account control changes (enable/disable) and a background publisher that ships each change,
   in strict version order, to a durable JetStream stream. Its existing `GET /account/` endpoint
   gains a watermarked-snapshot response shape (schema version, source epoch, watermark, checksum)
   without breaking existing callers that only read the record array (see `contracts/contract-delta.md`).
2. **`reference-data`** (NestJS/Node) gains the equivalent outbox table + publisher for security
   control changes (enable/disable/halt), and the same watermarked-snapshot wrapper on `GET /stocks`.
3. **`order-matcher`'s `ReplicaBootstrap`** is rewritten from a single cold REST fetch into the
   ADR-019 5-step protocol: subscribe to each source's durable stream and buffer; fetch the
   watermarked snapshot; verify schema/count/checksum and atomically install; apply buffered
   deltas above the snapshot watermark, same epoch, in order; continue live consumption and mark
   ready only once caught up to the observed high watermark. A gap, version regression, or epoch
   change on either source invalidates readiness and forces a fresh bootstrap for that source only
   (FR-IMRG34).
4. **A new ADR** (`system/adr-021-transactional-outbox-jetstream-feeds.md`) records the outbox
   mechanism decision ADR-019 deliberately left open.
5. **A second, fully isolated Cloud Build trigger + Cloud Deploy pipeline** for YU04 (own staging
   namespace `traderx-yu04-staging`, own `account-service`/`reference-data`/`order-matcher` pods —
   the first staging environment that needs those two extra services, since this feature's entire
   point is the traffic between them and order-matcher).

## Design constraints carried over unchanged from ADR-019 / YU03

- **No change to the BLP decision path, journal wire format, or snapshot format.** `GatewayReplicaStore`
  is edge-only runtime state (rebuilt by `ReplicaBootstrap` every boot, never journaled or
  snapshotted — confirmed against `data-model.md`'s Tier-1/Tier-2 split), so adding per-source
  epoch/watermark tracking there is free of journal/snapshot compatibility concerns. The versioned
  control events that DO enter the BLP journal (`TYPE_{ACCOUNT,SECURITY}_CONTROL`, ids 7/8) keep
  their existing internally-assigned-at-apply-time version semantics (ADR-020) — this state does
  not thread the source's own version/epoch through the journal.
- **JetStream, not core NATS**, for both new source streams — ADR-019 already rejected "subscribe
  only, replay from origin" as a durable-feed substitute; JetStream gives the retention/replay this
  state's watermark-catchup step depends on.
- **Reservations still ride `RestingOrder` via `ReservationHolder`** — unrelated, unchanged.
- **Off production.** Deploys only to the new isolated `traderx-yu04-staging` namespace, same as
  YU03's staging pattern; never touches the production `traderx` namespace or the `traderx-yu03-staging`
  namespace either.

## Out of scope for this state

- **Multi-Gateway deployment** (FR-IMRG25, YU03's tasks.md T-24). This state builds the feed
  infrastructure a second Gateway instance would need to stay consistent with the first, but does
  not deploy a second Gateway or run the concurrency-overshoot test itself.
- **Entitlements** (blocked on the real-auth roadmap item; unrelated to durable feeds).
- Any change to `account-service`/`reference-data`'s *existing* non-control-plane read/write APIs
  (positions, trades, people) — only the account/security control fields that feed Gateway
  admission grow an outbox.
- Grafana dashboard/alerts for the new feed-health metrics (source lag, quarantine events) —
  tracked as a follow-up task (see `tasks.md`), same reasoning as YU03's deferred alert thresholds.
