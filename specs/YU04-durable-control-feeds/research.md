# Research: YU04 Durable Control Feeds

## Starting point: ADR-019 was already fully specified in YU03

ADR-019 (`specs/YU03-in-memory-risk-gateway/system/adr-019-watermarked-replica-bootstrap.md`) wrote
the target subscribe-buffer-snapshot-catchup protocol in full during the YU03 forward-port, then
deliberately deferred adopting it because the source-side durable feeds (FR-IMRG32/33) didn't exist
yet. This state's job is almost entirely "build the thing ADR-019 already describes," not redesign
it. The one genuinely open design question ADR-019 left behind — *how* account-service/reference-data
produce a durable versioned delta stream — is resolved in the new ADR-021 (transactional outbox +
polling publisher).

## Survey findings that corrected assumptions (verified against the actual code, not the handoff)

The handoff into this state assumed `account-service` uses JPA. It does not:

- **`account-service`** (`account-service/src/main/java/finos/traderx/accountservice/`) uses **plain
  Spring JDBC** — `AccountRepository` is a `JdbcTemplate` wrapper with hand-written SQL and a manual
  `RowMapper`; `Account` is a plain POJO with no `@Entity`/`@Version`/ORM annotations at all. This
  matters for the outbox design: there is no JPA entity-lifecycle hook (`@PrePersist` etc.) to lean
  on — the outbox insert has to be an explicit second `jdbcTemplate.update(...)` call at the same
  call site as the business write, wrapped in one `@Transactional` boundary.
- **`reference-data`** (`reference-data/src/stocks/`) has **no database and no write path at all**.
  `StocksService` loads a CSV into memory once at construction (`loadCsvData()`) and caches it in a
  `Promise<Stock[]>`; `StocksController` exposes only `GET /` and `GET /:ticker`. For FR-IMRG32 to
  mean anything here, `reference-data` needs a real place to persist tickers and a way for that set
  to change over time — see ADR-021's decision to give it a minimal MariaDB-backed `stocks` table
  and a new `POST /stocks` endpoint (the CSV becomes a one-time idempotent seed).
- **`order-matcher` already depends on `io.nats:jnats:2.20.5`** and already runs a JetStream stream
  (`TRADERX_BLP_REPLICATION`, subject `traderx.blp.replication.events`, memory-backed, 1-day max-age)
  for BLP replication (`NatsJournalReplicator`/`ReplicationFollower`, both under
  `specs/YU02-lmax-kubernetes/generation/runtime-overrides/order-matcher/.../lmax/`). This state's
  new `ControlFeedSubscriber` reuses the same client dependency and the same connection config
  pattern (`nats.address` property, `NATS_ADDRESS`/`NATS_BROKER_HOST` env vars) — no new order-matcher
  dependency, just new stream/consumer wiring alongside the existing replication one. The two
  streams are unrelated (different subjects, different purpose) and must not be confused.
- **The live MariaDB schema is NOT part of the generated-tree overlay chain at all for k8s-era
  states**, and this was confirmed empirically (marker-comment test, not assumed) exactly the way
  the YU03→YU04 handoff's gotcha warning insists on: the schema's original text lives at
  `specs/009b-lmax-sequencer-architecture/generation/runtime-overrides/postgres-database-replacement/mariadb-init/initialSchema.sql`,
  but `pipeline/install-generated-ci-assets.sh`'s per-state `state_allowed_roots` allow-list only
  includes `postgres-database-replacement` for states 005–009b — **not** 010-kubernetes-runtime or
  any descendant (011 through YU04). Generating YU04 leaves
  `generated/code/target-generated/postgres-database-replacement/mariadb-init/` **empty** (verified:
  a marker comment placed in a YU04-owned override of that same relative path never reached the
  generated tree). A first attempt at this state assumed a YU04-owned override of that path would
  work, mirroring the general "copy from the fully-generated tree, edit, regenerate" fix for the
  patch-chain/render-script gotchas — it does not apply here, because this path isn't merely
  *sourced* from an older mechanism, it is **pruned entirely** for every k8s-era state. Do not add a
  `generation/runtime-overrides/postgres-database-replacement/...` directory for YU04; it will be
  silently dropped.
  - The **actual live schema** for any k8s environment (production or a staging namespace) is a
    hand-written, verbatim SQL blob embedded directly in that environment's own
    `cluster-addons/<env>/database.yaml` `ConfigMap` (confirmed: `cluster-addons/yu03-staging/database.yaml`'s
    embedded SQL is a byte-for-byte copy of the 009b file's content, hand-copied once, not
    regenerated). So this state's new tables (`account_control_outbox`, `account_source_epoch`,
    `stocks`, `stocks_control_outbox`, `stocks_source_epoch`) only need to be added to the **new**
    `cluster-addons/yu04-staging/database.yaml` this state's own isolated CI/CD pipeline creates
    (`tasks.md` T-53) — start from `yu03-staging`'s embedded schema and append the new tables. No
    production manifest changes, since production never runs this state's code.

## Why the outbox captures existence, not enable/disable/halt (scope discipline)

FR-IMRG32 says durable feeds must cover "the fields used by admission." Reading what
`ReplicaBootstrap` actually does with fetched records today settles what those fields are:
`bootstrapOnce()` hardcodes `enabled=true` for every account and `enabled=true, halted=false` for
every security — enable/disable/halt state is **not** sourced from account-service/reference-data
at all, ever; it is a Gateway/BLP-native concept administered exclusively through order-matcher's
`/risk/control/{account,security}` API and journaled per ADR-020. So the fields genuinely "used by
admission" from these two external sources are existence + identity: account `id`/`displayName`,
security `ticker`/`companyName`. Making *that* durable and versioned is the actual scope of
FR-IMRG32/33 here — inventing enable/disable ownership on account-service/reference-data would
contradict ADR-020's already-made decision and duplicate control authority in two places.

## Why an ephemeral, `DeliverPolicy.New` JetStream consumer for bootstrap (not a durable one)

ADR-019's step 1 is "subscribe... and buffer" specifically to close the race between "read a
snapshot" and "start consuming" — anything published in that window must not be lost. It does not
require replaying the *entire* stream history at every bootstrap, because the snapshot (step 2)
already covers everything up to its own watermark `W`, and `W` is always ≥ anything published
before the subscribe call in step 1. A fresh ephemeral pull consumer with `DeliverPolicy.New`,
created immediately before the snapshot fetch, satisfies the race-closing requirement with no
unbounded replay cost, and sidesteps durable-consumer-position drift across restarts — every
bootstrap attempt (initial boot, or a re-bootstrap after quarantine) gets a clean, correct starting
point by construction rather than by reasoning about a persisted consumer cursor.

## Why per-source (not shared) epoch/version/quarantine state

`account-service` and `reference-data` are independent services with independent outboxes; a gap or
epoch change in one must not force re-bootstrapping the other (FR-IMRG34 is per-source in the
original spec: "on invalid/out-of-order control updates, consumers SHALL quarantine the update").
`ControlFeedSubscriber` is therefore instantiated once per source with its own epoch/watermark
tracking and its own quarantine/re-bootstrap loop, feeding `GatewayReplicaStore` through the same
`applyAccount`/`applySecurity` entry points YU03 already has (extended with a `sourceVersion`
parameter), so the BLP-journal-facing side of those calls (ADR-020) is untouched.

## Deferred/out of scope, carried from YU03

Entitlement feeding, multi-Gateway deployment, SBE contract, order expiry, perf-profile acceptance —
unaffected by this state, see `spec.md` "Out of scope."
