# Tasks: YU04-durable-control-feeds

## Delivered

### Spec pack + plumbing

- [x] T-01 Spec pack (spec, requirements deltas, ADR-021, architecture, runtime-topology,
      data-model, contract-delta, plan, research, this file, generation docs) mirroring YU03's shape.
- [x] T-02 `pipeline/generate-state-YU04-durable-control-feeds.sh` +
      `render-state-YU04-durable-control-feeds.sh`, copying YU03's structure, parent pointed at
      `YU03-in-memory-risk-gateway`; state registered in `catalog/state-catalog.json`.
- [x] T-03 `bash pipeline/generate-state.sh YU04-durable-control-feeds` confirmed to exit 0
      end-to-end (scaffold, then repeatedly with real implementation code).
- [x] T-04 Propagation verified empirically (marker comment + regenerate + grep) for every
      new/edited runtime-overrides file. `order-matcher`, `account-service`, `reference-data`
      overrides all propagate via the standard `overlay_dir` mechanism. The MariaDB init SQL path
      does **not** — `postgres-database-replacement` is pruned from the generated tree for every
      k8s-era state (010 onward), confirmed empirically; new schema goes into
      `cluster-addons/yu04-staging/database.yaml` (T-53) instead. See `research.md`.

### `account-service` outbox

- [x] T-10 `account_control_outbox` + `account_source_epoch` tables (test-only `schema.sql`; the
      real deployed schema belongs in `cluster-addons/yu04-staging/database.yaml`, T-53).
- [x] T-11 `@Transactional` on `AccountService.upsertAccount`; outbox insert
      (`AccountControlOutboxRepository.recordChange`) in the same transaction as `AccountRepository.save`.
- [x] T-12 `AccountOutboxPublisher`: `@Scheduled` poller (250ms default) publishing unpublished rows
      in version order to `TRADERX_CONTROL_ACCOUNT` via a `ControlFeedPublisher` seam
      (`JetStreamControlFeedPublisher` connects lazily on first publish, so it never blocks
      app/test startup on broker availability), `Nats-Msg-Id="account:<version>"`.
- [x] T-13 `GET /account/control-snapshot` (schema version, source epoch, watermark, count, SHA-256
      checksum) — new additive endpoint; `GET /account/` unchanged.
- [x] T-14 Tests (7/7): outbox/business-row atomic commit and rollback (forced-failure proof),
      watermark/checksum correctness, poller order-preservation and no-skip-ahead-on-failure. Fixed
      a real pre-existing bug: `account-service`'s tests live at `src/main/test/java`, which Gradle
      does not pick up by default, so `./gradlew test` had been running zero tests (including the
      pre-existing smoke test); fixed with a `sourceSets` block (account-service only) and pointed
      the resurrected smoke test at H2 via `@TestPropertySource`.

### `reference-data` outbox

- [x] T-20 New MariaDB-backed `stocks` table (replaces the CSV-only cache) + `stocks_control_outbox`
      + `stocks_source_epoch` tables (`mysql2` pool reusing the existing DB env vars).
- [x] T-21 CSV-to-DB one-time idempotent seed in `StocksService.onModuleInit` (only when `stocks` is
      empty); each seed row also gets an outbox row in the same transaction, so the initial universe
      is itself replayable.
- [x] T-22 New `POST /stocks` write path (`stocks` + `stocks_control_outbox` in one transaction) —
      `reference-data`'s first write path ever.
- [x] T-23 `StocksOutboxPublisher` (`@nestjs/schedule` `@Interval`) publishing to
      `TRADERX_CONTROL_SECURITY` via a lazily-connecting JetStream publisher,
      `Nats-Msg-Id="security:<version>"`.
- [x] T-24 `GET /stocks/control-snapshot` (new, additive); `GET /stocks`/`GET /stocks/:ticker` stay
      response-shape-unchanged, now DB-backed.
- [x] T-25 Tests (8/8): checksum stability/sensitivity, transaction commit/rollback proof (fake
      connection recording begin/commit/rollback — no embeddable MariaDB-compatible test DB for
      Node, so this validates our orchestration; real-DB behavior is exercised live in staging),
      poller ordering and no-skip-ahead-on-failure. Test infrastructure was built from scratch
      (reference-data had none): jest config + `@nestjs/testing`; verified with `npm install`,
      `npx jest`, and a full strict-mode `npm run build`.

### `order-matcher`: `ReplicaBootstrap` rewrite

- [x] T-30 `ControlFeedSubscriber` + `ControlFeedBootstrapState`: a pure protocol state machine
      (`ControlFeedBootstrapState<T>`, no I/O, 15/15 unit tests) plus a thin real-I/O adapter
      (`ControlFeedSubscriber<T>` — ephemeral `DeliverPolicy.New` JetStream pull consumer + HTTP
      snapshot fetch). Covers ADR-019's full protocol: subscribe + buffer, snapshot verify
      (`ChecksumCodec` matching both source services' canonical serialization) + atomic install,
      buffered-delta replay above the watermark in order, live consumption, and
      gap/regression/epoch-mismatch quarantine + re-bootstrap.
- [x] T-31 `GatewayReplicaStore`: `AccountRecord`/`SecurityRecord` gain `sourceVersion`; new
      `applyAccount(int, boolean, long)` / `applySecurity(String, boolean, boolean, long)` overloads
      (existing 2/3-arg versions still used by `/risk/control/*`). `markReady()`/`markNotReady()` now
      called exclusively from `ReplicaBootstrap` (FR-IMRG05: both sources must be ready).
- [x] T-32 `ReplicaBootstrap` rewritten to own two `ControlFeedSubscriber`s (account, security)
      instead of two one-shot REST fetches; same PRIMARY-only/recovery-ready gating and
      forever-retry-with-backoff discipline as YU03; a quarantine on one source revokes overall
      Gateway readiness immediately while only that source re-bootstraps (FR-IMRG34 is per-source).
- [x] T-33 New metrics: `traderx_replica_source_watermark{source}`,
      `traderx_replica_quarantine_total{source,reason}`.
- [x] T-34 Tests: `ControlFeedBootstrapStateTest` (15) covers ADR-019's full validation list.
      Full order-matcher suite green (65; only the pre-existing ~1-in-3 72-byte allocation flake in
      `AllocationGateTest`); `RiskReplayDeterminismTest` and snapshot-v3 tests pass unchanged,
      confirming no journal/snapshot format impact. Fixed a real regression found in verification:
      moving `markReady()` from unconditional to bootstrap-gated broke `LmaxHotPathParityTest`
      (no live NATS in its Spring context); fixed by granting readiness immediately in explicit
      seeds-only mode (`risk.bootstrap.enabled=false`) and opting that test into it.

### Verification

- [x] T-40 Full suites green across all three services: order-matcher 65, account-service 7,
      reference-data 8 — 80 tests total, all passing except the one pre-existing unrelated flake.
- [x] T-41 `bash pipeline/generate-state.sh YU04-durable-control-feeds` end-to-end with real
      implementation code (always exit 0), `./gradlew test`/`npm test` on the generated trees.

## Still open

- [ ] Grafana dashboard + alerts for the new feed-health metrics
      (`traderx_replica_source_watermark`, `traderx_replica_quarantine_total`,
      `traderx_outbox_publish_lag_seconds`, `traderx_outbox_unpublished_rows`) — needs threshold
      decisions (source lag / quarantine rate that should page someone).
- [ ] Outbox row pruning job — safe once a row is at/below the current snapshot watermark
      (ADR-021); not required for correctness at this state's data volume, worth doing before any
      long-lived deployment.
- [ ] Isolated staging CI/CD (T-50–T-56): `cloudbuild-yu04-staging.yaml`,
      `clouddeploy-yu04-staging.yaml`, `skaffold-yu04-staging.yaml`, `cluster-addons/yu04-staging/`
      (namespace + database + nats-broker + order-matcher + new account-service/reference-data pods),
      a manual `gcloud builds submit` dry run, and a live end-to-end injection/quarantine check.
      Touches live Cloud Build/Deploy resources — **requires explicit user go-ahead before starting**,
      same discipline as every prior state.
