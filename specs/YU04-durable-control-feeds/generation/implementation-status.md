# Implementation Status: YU04-durable-control-feeds

**Status:** Core implementation complete across all three services and tested: `account-service`
(7/7), `reference-data` (8/8), `order-matcher` (65/65, one pre-existing unrelated flake). Only the
isolated staging CI/CD pipeline remains (requires explicit go-ahead).
**Parent:** `YU03-in-memory-risk-gateway`
**Branch:** `YU04-durable-control-feeds` (own worktree `traderX-YU04-durable-control-feeds`).

## Done

- Spec pack: `spec.md`, `requirements/functional-delta.md`, `requirements/nonfunctional-delta.md`,
  `contracts/contract-delta.md`, `system/architecture.md`, `system/runtime-topology.md`,
  `system/adr-021-transactional-outbox-jetstream-feeds.md`, `data-model.md`, `plan.md`,
  `research.md`, `tasks.md`, this file, `README.md` — mirrors YU03's spec-pack shape.
- Codebase survey (recorded in `research.md`) corrected two assumptions from the original
  YU03→YU04 handoff: `account-service` uses plain Spring JDBC, not JPA; `reference-data` has no
  database or write path at all today (CSV-loaded, read-only) and needs new minimal persistence to
  make FR-IMRG32/33 meaningful for it.
- Outbox mechanism decided and recorded (ADR-021): transactional outbox table + polling publisher,
  in each service's own database/transaction, publishing to a per-source JetStream stream.
- Generation/render hook pair written (`pipeline/generate-state-YU04-durable-control-feeds.sh`,
  `pipeline/render-state-YU04-durable-control-feeds.sh`), state registered in
  `catalog/state-catalog.json`, runtime-harness scripts added (`scripts/{start,stop,status}-state-YU04-durable-control-feeds-generated.sh`,
  `scripts/test-state-YU04-durable-control-feeds.sh`), and `bash pipeline/generate-state.sh
  YU04-durable-control-feeds` confirmed to exit 0 end-to-end.
- Generation propagation verified empirically (marker-comment test, not assumed) for all three
  affected services: `order-matcher`, `account-service`, and `reference-data` runtime-overrides all
  propagate normally. The MariaDB init SQL path (`postgres-database-replacement/mariadb-init/...`)
  does **not** — it's pruned from the generated tree for every k8s-era state (010 onward), a new
  finding beyond the two gotcha variants the YU03→YU04 handoff already knew about. New DB schema
  for this state goes directly into the new `cluster-addons/yu04-staging/database.yaml` (T-53), not
  a generation-pipeline override. See `research.md` for the full trace.

- `account-service` outbox implementation (`tasks.md` T-10 through T-14), all done and tested:
  `account_control_outbox`/`account_source_epoch` tables (test-only `schema.sql`; real deployed
  schema still belongs in `cluster-addons/yu04-staging/database.yaml`, T-53), `@Transactional`
  outbox insert alongside the existing `accounts` write, `AccountOutboxPublisher` (scheduled
  poller → `TRADERX_CONTROL_ACCOUNT` JetStream stream via a lazily-connecting
  `JetStreamControlFeedPublisher`), new additive `GET /account/control-snapshot` endpoint. Full
  test suite green (7/7): outbox atomicity (forced-failure rollback proof), watermark/checksum
  correctness, poller ordering and no-skip-ahead-on-failure.
  - **Found and fixed a real pre-existing bug along the way**: `account-service`'s tests live at
    `src/main/test/java`, which Gradle's Java plugin does not pick up by default — `./gradlew test`
    silently ran **zero tests** (confirmed empirically, not assumed), including the pre-existing
    smoke test. Fixed with a `sourceSets` block in `build.gradle` (account-service only); also had
    to point the resurrected smoke test at the H2 `test-application.properties` via
    `@TestPropertySource`, since unannotated it would otherwise try the real MariaDB datasource.
- `reference-data` outbox implementation (`tasks.md` T-20 through T-25), all done and tested: new
  MariaDB-backed `stocks` persistence (`mysql2`, thin raw-SQL style matching account-service's,
  not a heavy ORM) replacing the CSV-only in-memory cache — CSV is now a one-time idempotent seed —
  `stocks_control_outbox`/`stocks_source_epoch` tables, new `POST /stocks` write path (this
  service's first ever), `StocksOutboxPublisher` (`@nestjs/schedule` interval task →
  `TRADERX_CONTROL_SECURITY` JetStream stream via a lazily-connecting publisher), new additive
  `GET /stocks/control-snapshot` endpoint. Test suite built from scratch (reference-data had zero
  test infra before this state — no jest, no `@nestjs/testing`) and green (8/8): checksum
  stability, transaction commit/rollback proof via a fake connection (no embeddable
  MariaDB-compatible test DB exists for Node the way H2 serves Java, so this proves OUR
  orchestration code calls `beginTransaction`/`commit`/`rollback` correctly, not MariaDB's own ACID
  guarantee — real-DB behavior is exercised live in staging, T-56), poller ordering and
  no-skip-ahead-on-failure. Verified for real: `npm install` + `npx jest` + full `npm run build`
  (strict-mode TypeScript) on the generated tree.
- `order-matcher`'s `ReplicaBootstrap` rewrite (`tasks.md` T-30 through T-34), all done and tested:
  split into a pure, fully-unit-tested protocol state machine (`ControlFeedBootstrapState<T>`, 15
  tests covering ADR-019's complete validation list) and a thin real-I/O adapter
  (`ControlFeedSubscriber<T>` — ephemeral JetStream pull consumer + HTTP snapshot fetch, same
  thin-adapter split as the source-side publishers). `GatewayReplicaStore` gained per-record
  `sourceVersion`; `ReplicaBootstrap` now orchestrates two independent subscribers (account,
  security), revoking Gateway readiness immediately on either source's quarantine while
  re-bootstrapping only the affected one (FR-IMRG05/FR-IMRG34). New metrics:
  `traderx_replica_source_watermark{source}`, `traderx_replica_quarantine_total{source,reason}`.
  - **Found and fixed a real regression during verification** (not just written and assumed
    correct): moving `markReady()` from unconditional (YU03) to bootstrap-gated broke
    `LmaxHotPathParityTest` (8 methods, inherited from YU02) — a full-Spring-context test with no
    live NATS/account-service/reference-data, so bootstrap could never complete. Root cause: that
    test exercises BLP/matching parity, not the bootstrap protocol, and only passed before because
    readiness was unconditional. Fixed by granting readiness immediately in explicit
    seeds-only mode (`risk.bootstrap.enabled=false`) and opting that test into it — an honest,
    visible choice rather than reverting the design or leaving a hidden default. Full order-matcher
    suite reran clean afterward (65/65, only the documented pre-existing ~1-in-3 allocation flake,
    reproduced at that same rate across 3 reruns to confirm it wasn't a new regression).

Total across all three services: **80 tests, all passing** (order-matcher 65, account-service 7,
reference-data 8) except one pre-existing, unrelated, documented flake.

## Not yet done

- The isolated `traderx-yu04-staging` Cloud Build trigger + Cloud Deploy pipeline (`tasks.md` T-50
  through T-56) — requires the user's explicit go-ahead before touching any live Cloud Build/Deploy
  resource, per this project's established discipline (see YU03's staging pipeline for precedent).

## Next step

`tasks.md` T-50: with the user's explicit go-ahead, build the isolated YU04 staging CI/CD pipeline
(`cloudbuild-yu04-staging.yaml`, `clouddeploy-yu04-staging.yaml`, `skaffold-yu04-staging.yaml`,
`cluster-addons/yu04-staging/` — including new `account-service`/`reference-data` pods this state's
staging environment needs that YU03's didn't), then a live end-to-end verification (T-56).
