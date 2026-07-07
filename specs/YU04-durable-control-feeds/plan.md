# Implementation Plan: YU04 Durable Control Feeds

Parent: `YU03-in-memory-risk-gateway`. Approach: adopt ADR-019's already-fully-specified target
protocol, resolving the one open design question (the outbox mechanism, ADR-021) and implementing
across all three affected services in one state, per the user's explicit scope decision (both
account-service and reference-data outbox feeds land together, not split across two states —
`ReplicaBootstrap`'s subscribe-buffer-snapshot-catchup machinery is shared regardless of source
count, and a half-migrated bootstrap protocol with one durable source and one still-REST source is
not a design ADR-019 describes or this roadmap wants to build).

## Slice scope (this state)

1. **Spec pack**: this pack (spec, requirements deltas, ADR-021, architecture, runtime-topology,
   data-model, contract-delta, plan, research, tasks, generation docs) — mirrors YU03's shape.
2. **`account-service`**: `account_control_outbox` + `account_source_epoch` tables, `@Transactional`
   outbox insert on the existing `AccountRepository.save` write path, a `@Scheduled` outbox
   publisher, `GET /account/control-snapshot`.
3. **`reference-data`**: new minimal MariaDB-backed `stocks` persistence (replacing the CSV-only
   in-memory cache; CSV becomes a one-time idempotent seed), `stocks_control_outbox` +
   `stocks_source_epoch` tables, a new `POST /stocks` write path, an interval-driven outbox
   publisher, `GET /stocks/control-snapshot`.
4. **`order-matcher`**: new `ControlFeedSubscriber` (per source), `ReplicaBootstrap` rewritten to
   orchestrate two of them instead of two one-shot REST fetches, `GatewayReplicaStore` extended
   with `sourceVersion` + new apply overloads, new quarantine/watermark metrics.
5. **Generation pipeline hooks**: `pipeline/generate-state-YU04-durable-control-feeds.sh` +
   `render-state-YU04-durable-control-feeds.sh`, mirroring YU03's exactly, parent pointed at YU03.
6. **Isolated staging CI/CD**: `cloudbuild-yu04-staging.yaml`, `clouddeploy-yu04-staging.yaml`,
   `skaffold-yu04-staging.yaml`, `cluster-addons/yu04-staging/` (namespace `traderx-yu04-staging`,
   now including `account-service` + `reference-data` pods, not just order-matcher + database +
   nats-broker as YU03-staging needed) — built only after the core implementation passes tests, and
   only with the user's explicit go-ahead before touching any live Cloud Build/Deploy resource.

## Key decisions (see ADR-021 + spec.md "Design constraints carried over unchanged")

- Transactional outbox + polling publisher (not CDC/Debezium, not dual-write, not DB triggers) —
  ADR-021.
- The durable feed's field scope is existence/identity (account id/displayName, security
  ticker/companyName), not enable/disable/halt — that stays a Gateway/BLP-native concept via
  `/risk/control/*` (ADR-020), unchanged by this state.
- Ephemeral, `DeliverPolicy.New` JetStream pull consumers for bootstrap, one fresh consumer per
  bootstrap/re-bootstrap attempt — not a durable consumer with a persisted cursor (see `research.md`).
- Per-source (not shared) epoch/watermark/quarantine state — a gap on one source must not force
  re-bootstrapping the other.
- No change to the BLP decision path, journal wire format, or snapshot format — `GatewayReplicaStore`
  stays edge-only, never journaled/snapshotted.
- New DB schema (outbox tables, `stocks`) is **not** a generation-pipeline override at all — empirically
  confirmed (marker-comment test) that `postgres-database-replacement` is pruned from the generated
  tree for every k8s-era state, so it lands directly in the new `cluster-addons/yu04-staging/database.yaml`
  this state's own CI/CD pipeline creates (see `research.md`), same as how `yu03-staging`'s schema is
  a hand-maintained, non-generated ConfigMap today.
- Off production; isolated `traderx-yu04-staging` namespace only, same discipline as YU03.

## Sequencing within this state

1. Scaffold spec pack + generation hook/render scripts; confirm `bash pipeline/generate-state.sh
   YU04-durable-control-feeds` exits 0 before writing any real implementation code.
2. Verify propagation empirically (marker-comment + regenerate + grep) for every new/edited file
   before trusting its runtime-overrides location, per the repeated generation-pipeline gotcha.
3. Implement `account-service` outbox (smallest lift — existing write path, existing JDBC pattern).
4. Implement `reference-data` persistence + outbox (larger lift — new DB layer + new write path).
5. Rewrite `ReplicaBootstrap`/`ControlFeedSubscriber`/`GatewayReplicaStore` in order-matcher.
6. Tests: outbox atomicity + idempotent publish (both services), `ControlFeedSubscriber` protocol
   tests (snapshot+buffer+replay, gap/regression/epoch-mismatch/quarantine, buffer overflow),
   extend `RiskReplayDeterminismTest`-style coverage only if the BLP-facing surface changed (it
   should not have — confirm, don't assume).
7. Only after 3–6 pass: build the isolated CI/CD pipeline (step 6 in "Slice scope" above), with
   explicit user go-ahead before any live Cloud Build/Deploy change.

## Validation strategy

Unit + integration tests in-tree for all three services (outbox atomicity, publish idempotency,
bootstrap protocol correctness including fault injection per ADR-019's "Validation (when adopted)"
list) plus a live end-to-end check in the isolated `traderx-yu04-staging` namespace — inject an
account/security change through the real outbox path and confirm it reaches order-matcher's replica
without a restart, then confirm a deliberately corrupted/regressed delta triggers quarantine and
recovery, same verification discipline YU03 used for its staging deploy.
