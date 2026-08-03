# Implementation Plan: YU04-durable-control-feeds

## Goal

Replace YU03's one-shot REST replica bootstrap with real durable control feeds: give
`account-service` and `reference-data` a transactional outbox that publishes versioned control
deltas to per-source JetStream streams, and rewrite `order-matcher`'s `ReplicaBootstrap` to run
ADR-019's subscribe-buffer-snapshot-catchup protocol per source — without touching the two-tier
Gateway+BLP admission pipeline, the journal/replication wire format, or the snapshot format.

## Workstreams

1. `account-service` outbox
   - `account_control_outbox` + `account_source_epoch` tables; a `@Transactional` outbox insert on
     the existing account write path; an `AccountOutboxPublisher` (`@Scheduled` poller) shipping
     rows in version order to `TRADERX_CONTROL_ACCOUNT`; a new additive `GET /account/control-snapshot`.
2. `reference-data` persistence + outbox
   - New MariaDB-backed `stocks` persistence (CSV becomes a one-time idempotent seed);
     `stocks_control_outbox` + `stocks_source_epoch` tables; a new `POST /stocks` write path; a
     `StocksOutboxPublisher` (`@Interval` task) shipping rows to `TRADERX_CONTROL_SECURITY`; a new
     additive `GET /stocks/control-snapshot`.
3. `order-matcher` bootstrap rewrite
   - `ControlFeedSubscriber` (one per source) split into a pure protocol state machine
     (`ControlFeedBootstrapState`) and a thin JetStream + HTTP-snapshot I/O adapter; `ReplicaBootstrap`
     rewritten to orchestrate two subscribers; `GatewayReplicaStore` records gain `sourceVersion`;
     per-source watermark/quarantine metrics.
4. State registration
   - Spec pack (including ADR-021 for the outbox mechanism), generation hook + render scripts,
     catalog entry, runtime harness registration.
5. Validation
   - Unit tests for outbox atomicity and idempotent publish (both services), the full bootstrap
     protocol including fault injection (order-matcher), and a regression pass confirming no
     journal/snapshot-format impact.
6. Isolated staging CI/CD
   - A second, fully isolated Cloud Build trigger + Cloud Deploy pipeline in its own
     `traderx-yu04-staging` namespace (the first staging environment that also runs `account-service`
     and `reference-data` pods), plus a live end-to-end injection/quarantine check.

## Key decisions (see ADR-021 + spec.md)

- Transactional outbox + polling publisher (not CDC/Debezium, not dual-write, not DB triggers) —
  ADR-021, so the feed is written in the same transaction as the business record and can never
  diverge from it.
- Two independent JetStream streams (not one shared), so an epoch bump or resync on one source never
  forces re-bootstrapping the other; separate services have separate deploy lifecycles and failure
  domains.
- Ephemeral, `DeliverPolicy.New` JetStream pull consumers for bootstrap — one fresh consumer per
  bootstrap attempt, not a durable consumer with a persisted cursor.
- Per-source epoch/watermark/quarantine state; a gap on one source revokes overall readiness while
  only that source re-bootstraps (FR-IMRG34).
- No change to the BLP decision path, journal wire format, or snapshot format — `GatewayReplicaStore`
  stays edge-only, rebuilt on every boot, never journaled or snapshotted.
- New DB schema (outbox tables, `stocks`) lands in the state's own `cluster-addons/yu04-staging/`
  database manifest, not a generation-pipeline override — `postgres-database-replacement` is pruned
  from the generated tree for every k8s-era state (confirmed empirically; see `research.md`).
- Off production: isolated `traderx-yu04-staging` namespace only, same discipline as YU03.

## Exit Criteria

- Spec and tasks are complete and reviewed.
- Generation hook produces expected artifacts and exits successfully.
- Unit suites pass across all three services (account-service, reference-data, order-matcher),
  including the full ADR-019 bootstrap validation list.
- `RiskReplayDeterminismTest` and the snapshot-v3 tests pass unchanged.
- State can be published to `code/generated-state-YU04-durable-control-feeds`.

## Validation status

- Core implementation complete and tested across all three services: 80 tests total
  (order-matcher 65, account-service 7, reference-data 8), all passing except one pre-existing,
  unrelated, documented allocation flake in `AllocationGateTest`.
- The isolated `traderx-yu04-staging` Cloud Build trigger + Cloud Deploy pipeline (Workstream 6) is
  the one remaining piece; it touches live Cloud Build/Deploy resources and requires the user's
  explicit go-ahead before it is built (see `tasks.md`, "Still open", and
  `generation/implementation-status.md`).
