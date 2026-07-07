# Tasks: YU04 Durable Control Feeds

## Spec pack + plumbing

- [x] T-01 Spec pack (spec, requirements deltas, ADR-021, architecture, runtime-topology,
      data-model, contract-delta, plan, research, this file, generation docs) mirroring YU03's shape.
- [ ] T-02 `pipeline/generate-state-YU04-durable-control-feeds.sh` + `render-state-YU04-durable-control-feeds.sh`,
      copying YU03's exact structure, parent pointed at `YU03-in-memory-risk-gateway`.
- [ ] T-03 Confirm `bash pipeline/generate-state.sh YU04-durable-control-feeds` exits 0 (scaffold
      only, before any real implementation code).
- [x] T-04 Verify propagation empirically for every new/edited runtime-overrides file (marker
      comment + regenerate + grep) before trusting a location, per the repeated generation-pipeline
      gotcha. Result: `order-matcher`, `account-service`, `reference-data` runtime-overrides all
      propagate normally via the standard `overlay_dir` mechanism (confirmed with marker comments).
      The MariaDB init SQL path does **not** — `postgres-database-replacement` is pruned from the
      generated tree for every k8s-era state (010 onward); confirmed empirically, not assumed. New
      schema goes straight into `cluster-addons/yu04-staging/database.yaml` instead (T-53), not a
      `generation/runtime-overrides/postgres-database-replacement/...` path. See `research.md`.

## `account-service` outbox — done

- [x] T-10 `account_control_outbox` + `account_source_epoch` tables. Schema for the real deployed
      DB still belongs in `cluster-addons/yu04-staging/database.yaml` (T-53); for now, added a
      `schema.sql` under `account-service`'s own `runtime-overrides` (Spring Boot only auto-runs it
      against a detected *embedded* datasource, i.e. H2 in tests — it has zero effect against the
      real MariaDB datasource, so it's the correct, non-conflicting place for test-only schema).
- [x] T-11 `@Transactional` on `AccountService.upsertAccount`; outbox insert
      (`AccountControlOutboxRepository.recordChange`) added alongside the existing
      `AccountRepository.save` call, same transaction.
- [x] T-12 `AccountOutboxPublisher`: `@Scheduled` poller (250ms default), publishes unpublished
      rows in version order to `TRADERX_CONTROL_ACCOUNT` (`io.nats:jnats:2.20.5`, matching
      order-matcher's existing dependency version) via a `ControlFeedPublisher` seam
      (`JetStreamControlFeedPublisher` is the real, lazily-connecting implementation — connects on
      first publish, not at construction, so it never blocks app/test startup on broker
      availability), `Nats-Msg-Id="account:<version>"`.
- [x] T-13 `GET /account/control-snapshot` (schema version, source epoch, watermark, count,
      SHA-256 checksum) — new additive endpoint; `GET /account/` unchanged.
- [x] T-14 Tests, all passing (`AccountOutboxWiringTest`, `AccountOutboxAtomicityTest`,
      `AccountOutboxPublisherTest`): outbox-row-and-business-row atomic commit/rollback (forced
      failure via `@MockitoBean` proves rollback of both), watermark/checksum correctness, poller
      order-preservation and no-skip-ahead-on-failure.
  - **Found and fixed a real, pre-existing bug while wiring this up**: `account-service`'s test
    sources live at `src/main/test/java` (not Gradle's default `src/test/java`), so `./gradlew
    test` silently reported `NO-SOURCE` and **zero tests ever ran**, including the pre-existing
    `AccountServiceApplicationTests` smoke test — confirmed empirically before assuming it was a
    working baseline. Fixed via a `sourceSets` block in `build.gradle` (scoped to account-service
    only; the same pattern also affects `trade-service`/`trade-processor`/`position-service`, out
    of scope for this state). Also had to point the resurrected smoke test at
    `test-application.properties` (H2) via `@TestPropertySource` — unannotated, it would otherwise
    try the real MariaDB datasource and fail in any environment without one reachable.
  - Full suite green: `AccountServiceApplicationTests` (1), `AccountOutboxAtomicityTest` (1),
    `AccountOutboxPublisherTest` (2), `AccountOutboxWiringTest` (3) — 7/7 passing, 0 skipped.

## `reference-data` outbox — done

- [x] T-20 New MariaDB-backed `stocks` table (replaces CSV-only cache) + `stocks_control_outbox` +
      `stocks_source_epoch` tables. Real deployed schema still belongs in
      `cluster-addons/yu04-staging/database.yaml` (T-53); connection config
      (`DatabaseModule`/`mysql2` pool) reuses the same `DATABASE_PG_HOST`/`DATABASE_NAME`/etc. env
      vars account-service already connects with.
- [x] T-21 CSV-to-DB one-time idempotent seed in `StocksService.onModuleInit` (only when `stocks`
      is empty at boot, reusing the existing `loadCsvData()` unchanged); each seed row also gets an
      outbox row in the same transaction, so the initial universe is itself replayable.
- [x] T-22 New `POST /stocks` endpoint (`StocksService.create`, insert `stocks` +
      `stocks_control_outbox` in one transaction) — `reference-data`'s first write path ever;
      previously fully read-only (static CSV cache).
- [x] T-23 `StocksOutboxPublisher` (NestJS `@Interval` task via `@nestjs/schedule`), publishes to
      `TRADERX_CONTROL_SECURITY` (`nats` npm JetStream client, lazily-connecting
      `JetStreamControlFeedPublisher` — same rationale as account-service's Java side: never
      blocks startup/tests on broker availability), `Nats-Msg-Id="security:<version>"`.
- [x] T-24 `GET /stocks/control-snapshot` (new, additive); `GET /stocks`/`GET /stocks/:ticker` stay
      response-shape-unchanged, now DB-backed instead of CSV-cached-promise.
- [x] T-25 Tests, all passing (`control-snapshot.spec.ts`, `stocks.service.spec.ts`,
      `stocks-outbox-publisher.service.spec.ts`): checksum stability/sensitivity, transaction
      commit/rollback proof (fake connection recording `beginTransaction`/`commit`/`rollback`
      calls — this project has no embeddable MariaDB-compatible test DB for Node the way H2 serves
      Java, so this tests OUR transaction orchestration code, not MariaDB's own ACID guarantee;
      real-DB behavior is exercised live in staging, T-56), poller ordering and
      no-skip-ahead-on-failure (mirrors `AccountOutboxPublisherTest`'s fake-publisher approach).
  - **Reference-data had zero test infrastructure before this state** (no jest, no
    `@nestjs/testing`, no test script) — added from scratch (`package.json` `jest` config,
    `jest`/`ts-jest`/`@types/jest`/`@nestjs/testing` devDependencies).
  - Verified for real: `npm install` + `npx jest` (8/8 passing) + full `npm run build` (`nest
    build`, strict-mode TypeScript) on the generated tree, not just read/reasoned about.

## `order-matcher`: `ReplicaBootstrap` rewrite — done

- [x] T-30 `ControlFeedSubscriber` + `ControlFeedBootstrapState`: split into a pure protocol state
      machine (`ControlFeedBootstrapState<T>` — no I/O, fully unit-tested, 15/15 passing) and a
      thin real-I/O adapter (`ControlFeedSubscriber<T>` — ephemeral `DeliverPolicy.New` JetStream
      pull consumer + HTTP snapshot fetch, verified live in staging per the same split used for
      `JetStreamControlFeedPublisher` on the source side). Covers ADR-019's full protocol: subscribe
      + buffer, snapshot verify (checksum + count, `ChecksumCodec` matching the exact canonical
      serialization both source services use) + atomic install, buffered-delta replay above the
      watermark in order, live consumption, gap/regression(=duplicate, not a fault)/epoch-mismatch
      quarantine + re-bootstrap.
- [x] T-31 `GatewayReplicaStore`: `AccountRecord`/`SecurityRecord` gain `sourceVersion` (4th field,
      all constructor call sites updated); new `applyAccount(int, boolean, long)` /
      `applySecurity(String, boolean, boolean, long)` overloads (existing 2/3-arg versions
      unchanged, still used by `/risk/control/*`). `markReady()`/`markNotReady()` now called
      exclusively from `ReplicaBootstrap` (FR-IMRG05: both sources must be ready) — removed the
      unconditional `markReady()` YU03 called at ring-start in `LmaxEngine`.
- [x] T-32 `ReplicaBootstrap` rewritten to own two `ControlFeedSubscriber`s (account, security)
      instead of two one-shot REST fetches; same PRIMARY-only/recovery-ready gating and
      forever-retry-with-backoff discipline as YU03; a quarantine on one source revokes overall
      Gateway readiness immediately (synchronous callback, not polling-delayed) while only that
      source re-bootstraps (FR-IMRG34 is per-source).
- [x] T-33 New metrics: `traderx_replica_source_watermark{source}`, `traderx_replica_quarantine_total{source,reason}`.
- [x] T-34 Tests: `ControlFeedBootstrapStateTest` (15 tests) covers ADR-019's full "Validation (when
      adopted)" list — updates immediately before/during/after snapshot creation (buffered-during-window
      replay), duplicate (idempotent, not a fault) / reorder (replayed in version order regardless of
      arrival order) / gap / epoch-change fixtures, buffer overflow, checksum/count mismatch,
      readiness false until bootstrap completes and true only then, re-bootstrap-after-quarantine.
      Confirmed (not assumed): full order-matcher suite green (65 tests, only the pre-existing
      documented ~1-in-3 72-byte allocation flake in `AllocationGateTest` — reproduced at that same
      rate across 3 reruns, unrelated to this state) — `RiskReplayDeterminismTest` and snapshot-v3
      tests pass unchanged, confirming no journal/snapshot format impact.
  - **Found and fixed a real regression during verification, not just written and assumed correct**:
    `LmaxHotPathParityTest` (8 test methods, inherited unchanged from YU02, submits real orders via
    `OrderMatcherService` through the full Gateway screening path) started failing with
    `RiskRejectedException` once `markReady()` moved from unconditional to bootstrap-gated — this
    test's Spring context has no live NATS/account-service/reference-data, so bootstrap never
    completes and readiness never arrives. Root cause: this test exercises BLP/matching-hot-path
    parity, not the bootstrap protocol, and was previously passing only because YU03's readiness was
    seed-based and unconditional. Fixed properly (not by reverting the design): `ReplicaBootstrap`
    now grants readiness immediately when `risk.bootstrap.enabled=false` (seeds-only mode is, by
    definition, a complete admission image with no feed to wait on), and added
    `"risk.bootstrap.enabled=false"` to this test's properties — an explicit, honest opt-in to
    seeds-only mode rather than a hidden default. All 8 failures resolved; full suite reran clean.

## Verification

- [x] T-40 Full order-matcher/account-service/reference-data test suites green (order-matcher 65,
      account-service 7, reference-data 8 — 80 tests total across the three services, all passing
      except the one pre-existing unrelated flake noted above).
- [x] T-41 `bash pipeline/generate-state.sh YU04-durable-control-feeds` end-to-end with real
      implementation code (run repeatedly through this state's development, always exit 0),
      `./gradlew test`/`npm test` on the generated order-matcher/account-service/reference-data trees.

## Isolated staging CI/CD (only after T-01–T-41 pass; explicit user go-ahead required before touching
## any live Cloud Build trigger or Cloud Deploy pipeline/target)

- [ ] T-50 `cloudbuild-yu04-staging.yaml` (generate-state.sh YU04 + build all three service images).
- [ ] T-51 `clouddeploy-yu04-staging.yaml` (`order-matcher-yu04-staging-pipeline`, target `yu04-staging`,
      `requireApproval: true`, same service account as YU03-staging).
- [ ] T-52 `skaffold-yu04-staging.yaml` referencing `cluster-addons/yu04-staging/*.yaml`.
- [ ] T-53 `cluster-addons/yu04-staging/`: `namespace.yaml`, `database.yaml` (hand-carrying the
      updated schema — see `research.md`), `nats-broker.yaml`, `order-matcher.yaml`,
      `account-service.yaml` (new), `reference-data.yaml` (new), `kustomization.yaml`.
- [ ] T-54 `scripts/provision-yu04-staging-secret.sh` (mirrors YU03's, if YU04 needs its own admin
      token — confirm whether this state's staging environment needs a distinct token or can reuse
      YU03-staging's pattern with a new namespace-scoped secret).
- [ ] T-55 Manual `gcloud builds submit --config=cloudbuild-yu04-staging.yaml` dry run before wiring
      the GitHub trigger, same validation discipline YU03 used.
- [ ] T-56 Live end-to-end in `traderx-yu04-staging`: inject a real account/security change through
      the outbox path, confirm order-matcher's replica picks it up without a restart; then
      deliberately corrupt/regress a delta and confirm quarantine + recovery.

## Deferred — not this state's scope (unchanged from YU03's backlog)

- [ ] Entitlement replica (auth roadmap item).
- [ ] Multi-Gateway deployment + concurrency-overshoot test (FR-IMRG25) — this state is a
      prerequisite (durable feeds a second Gateway would need), not the deployment itself.
- [ ] Grafana dashboard/alerts for the new feed-health metrics (`traderx_replica_source_watermark`,
      `traderx_replica_quarantine_total`, `traderx_outbox_publish_lag_seconds`,
      `traderx_outbox_unpublished_rows`) — needs threshold decisions, same reasoning as YU03's
      deferred alert thresholds (T-22b there).
- [ ] Outbox row pruning job (safe once a row is at/below the current snapshot watermark, per
      ADR-021) — not required for correctness at this state's expected data volume, worth doing
      before a long-lived production deployment.
