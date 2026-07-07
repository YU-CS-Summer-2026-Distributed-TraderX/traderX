# ADR-021: Transactional Outbox Tables with Polling Publishers into Per-Source JetStream Streams

**Status:** Accepted for specification (implemented in YU04)
**Date:** 2026-07-06
**State:** `YU04-durable-control-feeds` (parent `YU03-in-memory-risk-gateway`)

## Context

ADR-019 requires every mandatory external replica source (`account-service`, `reference-data`) to
expose a durable, versioned, watermarked delta stream (FR-IMRG32/33), deliberately leaving *how*
those services produce that stream as an open question ("Outbox mechanism choice not yet decided"
in the YU03→YU04 handoff). Neither service publishes events today. `account-service` persists
accounts to MariaDB via plain **Spring JDBC** (`JdbcTemplate` + hand-written SQL, no JPA/Hibernate,
no ORM, no entity annotations — confirmed by reading `AccountRepository`/`AccountService`, correcting
an assumption in the original handoff that it used JPA). `reference-data` has **no database and no
write path at all today** — `StocksService` loads a CSV into memory once at boot and serves it
read-only; there is nothing to make durable yet (see `research.md`). Whatever mechanism is chosen
must not let the durable feed and the actual business record disagree — that would silently defeat
the whole point of ADR-019's watermark/gap machinery, and for `reference-data` specifically, must
give it a real place to persist and mutate its control-relevant data in the first place.

## Decision

**Scope note (important, corrects an assumption in the ADR-019/handoff text):** neither service
owns "enabled"/"halted" as business data today — those are Gateway/BLP-local control concepts,
administered exclusively through order-matcher's own `/risk/control/{account,security}` API and
sequenced into the BLP journal (ADR-020). `account-service`'s only admission-relevant fields are an
account's **existence and identity** (`id`, `displayName`); `reference-data`'s are a security's
**existence and identity** (`ticker`, `companyName`). This state does not move enable/halt
authority out of order-matcher — it makes the *existence* feed durable and versioned, which is
exactly what FR-IMRG32 asks for ("the fields used by admission") and is a faithful, non-invented
scope: `ReplicaBootstrap` already only reads `id`/`displayName` and `ticker`/`companyName` from
these two services today (see the current `ReplicaBootstrap.bootstrapOnce()` — it hardcodes
`enabled=true`/`halted=false` for every fetched record).

Each service adds a local **transactional outbox table** in the same database as its business
tables:

1. A control-relevant write (an account created/renamed; a security added/renamed to the tradable
   universe) inserts one row into `<domain>_control_outbox` in the **same local database
   transaction** as the entity insert/update. Atomicity comes from the local ACID transaction, not
   a distributed/two-phase commit — the outbox row and the business row can never diverge because
   either both commit or neither does.
   - `account-service` already has this write path (`AccountRepository.save`, called from
     `POST /account/` and `PUT /account/`) — it gains `@Transactional` plus the outbox insert.
   - `reference-data` has no write path today (CSV-loaded, read-only). It gains a minimal MariaDB-backed
     `stocks` table (reusing the same shared MariaDB instance/database `account-service` and the
     other services already use — no new stateful infra) and a new `POST /stocks` endpoint, so the
     durable feed has a genuine, testable live-delta path rather than a one-time static snapshot
     that never changes. The CSV becomes the one-time idempotent seed of that table on first boot
     (each seed row also gets an outbox row, so the initial universe is itself replayable).
2. The outbox row carries a strictly monotonic per-service version (DB auto-increment / sequence)
   and a source epoch (a small `source_epoch` table holding one row, bumped only on an
   unrecoverable resync — e.g. a restore from an external backup that breaks version continuity;
   normal operation never changes it).
3. A single-threaded background poller (Spring `@Scheduled` in `account-service`; a NestJS
   interval task in `reference-data`) reads unpublished outbox rows in version order and publishes
   each to that source's JetStream stream, with `Nats-Msg-Id: "<source>:<version>"` for
   publish-side idempotency, then marks the row published once JetStream acks.
4. Each service gains a **new, additive** snapshot endpoint (`GET /account/control-snapshot`,
   `GET /stocks/control-snapshot`) returning a watermarked-snapshot response: schema version,
   source epoch, watermark (= highest published outbox version at fetch time), record count, and a
   checksum over the canonical record set. The existing `GET /account/` / `GET /stocks` array
   endpoints are untouched — other consumers (UI, position-service, trade-processor) keep reading
   a plain array; only `ReplicaBootstrap` calls the new endpoint (see `contracts/contract-delta.md`).

## Alternatives Considered

- **CDC / log-tailing (e.g. Debezium on MariaDB's binlog):** rejected. Adds an operational
  component (Kafka Connect or equivalent) neither service needs for anything else, for a
  control-plane change volume (accounts/securities being on- or re-boarded) far below what CDC
  infra is built to justify.
- **Dual write** (write the DB row and publish to JetStream as two independent calls): rejected —
  this is exactly the unproven-delivery race ADR-019 exists to eliminate. A publish can succeed
  while the DB transaction later rolls back, or vice versa, with no way to reconcile which won.
- **DB-trigger-based outbox** (a trigger auto-inserts into the outbox on any row insert/update):
  rejected. Both services already do their own SQL by hand (`account-service`: `JdbcTemplate`;
  `reference-data`: a thin `mysql2` wrapper, see `research.md`) with no ORM/migration framework
  managing DDL — hand-written triggers would be one more piece of raw SQL to keep in sync across
  two codebases, with no compile-time check that the app-level write path and the trigger agree on
  which fields are control-relevant. An explicit `publishControlChange(...)` call at the same call
  site as the write is simpler to read, test, and code-review.
- **Synchronous publish-then-commit** (publish to JetStream first, commit DB only on ack):
  rejected — couples every account/security write to JetStream availability and reintroduces the
  synchronous-external-dependency-on-a-write-path pattern ADR-018/ADR-019 both exist to avoid,
  for no benefit over the async poller (control-plane writes are not latency-sensitive).

## Consequences

**Positive:** atomicity guaranteed by the local transaction, not network coordination; ordering
guaranteed by the outbox table's natural key order; replay/backfill possible straight from the
outbox table if JetStream retention is ever exceeded; no new infrastructure component in either
service's deployment; the pattern is identical in shape across both stacks even though the
concrete code differs (Java/Spring JDBC vs. NestJS/`mysql2`).

**Costs:** polling adds bounded latency before a change reaches the feed (default poll interval
250ms — acceptable; this is control-plane, not the order admission hot path, NFR-IMRG01 does not
apply here). Each service now owns a small piece of messaging code and a JetStream dependency
(NFR-IMRG12, justified in `requirements/nonfunctional-delta.md`). Outbox rows accumulate and need
periodic pruning — safe to prune any row at or below the current snapshot watermark, since a new
bootstrap never needs deltas older than the snapshot it installs.

## Validation

- Insert a control change, assert the outbox row and the business row commit/rollback together
  under a forced mid-transaction failure (both services).
- Kill the poller mid-publish, restart it, assert no duplicate delivery reaches a consumer (JetStream
  dedup window + consumer-side idempotent apply).
- Fetch a snapshot, assert watermark equals the highest currently-published outbox version and the
  checksum matches a fresh recomputation over the same record set.
- Prune outbox rows at/below a snapshot watermark, assert a subsequent bootstrap from that snapshot
  is unaffected (replay never needed those rows).
