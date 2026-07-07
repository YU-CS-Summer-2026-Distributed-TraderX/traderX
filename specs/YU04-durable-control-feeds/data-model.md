# Data Model: YU04 Durable Control Feeds

Three places gain state: two new source-side outbox schemas (MariaDB) and new in-memory tracking on
the order-matcher edge (`GatewayReplicaStore`/`ControlFeedSubscriber`). No BLP/Tier-2 state, journal
record, or snapshot format changes — see `spec.md` "Design constraints carried over unchanged."

## `account-service` (MariaDB, Spring JDBC — no ORM)

New tables. **Not** part of the generated-tree overlay chain — confirmed empirically that
`postgres-database-replacement` is pruned for every k8s-era state (010 onward), so a
`generation/runtime-overrides/postgres-database-replacement/...` override is silently dropped (see
`research.md`). These tables instead go straight into the new
`cluster-addons/yu04-staging/database.yaml` this state's own isolated CI/CD pipeline creates
(`tasks.md` T-53), appended to the schema `yu03-staging`'s `database.yaml` already embeds:

| Table | Columns | Notes |
|---|---|---|
| `account_control_outbox` | `version BIGINT PRIMARY KEY AUTO_INCREMENT`, `account_id INTEGER NOT NULL`, `display_name VARCHAR(50)`, `published BOOLEAN NOT NULL DEFAULT FALSE`, `created_at DATETIME NOT NULL` | One row per account create/update, in the same transaction as the `accounts` write. `version` is the strictly monotonic per-source version FR-IMRG32/33 requires. |
| `account_source_epoch` | `epoch BIGINT NOT NULL` | Single row (seeded `epoch=1` on first boot). Bumped only for a deliberate unrecoverable resync — never by normal operation. |

`accounts` (existing table, unchanged) stays the sole business-record source of truth; the outbox
row is a side-effect of the same write, not a separate fact that can drift from it.

## `reference-data` (new: MariaDB via `mysql2`, no ORM — mirrors account-service's style)

`reference-data` has no database today (see `research.md`). New tables, in the same
`cluster-addons/yu04-staging/database.yaml` schema as above (shared MariaDB instance/database, new
tables only — no collision with existing table names):

| Table | Columns | Notes |
|---|---|---|
| `stocks` | `ticker VARCHAR(16) PRIMARY KEY`, `company_name VARCHAR(100) NOT NULL`, `version BIGINT NOT NULL` | Replaces the CSV as the live source of truth; CSV becomes a one-time idempotent seed (only runs if the table is empty at boot). |
| `stocks_control_outbox` | `version BIGINT PRIMARY KEY AUTO_INCREMENT`, `ticker VARCHAR(16) NOT NULL`, `company_name VARCHAR(100)`, `published BOOLEAN NOT NULL DEFAULT FALSE`, `created_at DATETIME NOT NULL` | One row per ticker create/rename, in the same transaction as the `stocks` write (including each seed row, so the initial universe is itself replayable). |
| `stocks_source_epoch` | `epoch BIGINT NOT NULL` | Same shape/semantics as `account_source_epoch`. |

## JetStream streams (new; independent of the existing `TRADERX_BLP_REPLICATION` stream)

| Stream | Subject | Storage | Published by | Consumed by |
|---|---|---|---|---|
| `TRADERX_CONTROL_ACCOUNT` | `traderx.control.account.deltas` | File (durable across broker restart — this is a control feed, not a hot-path replication stream, so file storage's extra latency is irrelevant and its durability is the point) | `account-service`'s outbox poller | order-matcher's `ControlFeedSubscriber` (account source) |
| `TRADERX_CONTROL_SECURITY` | `traderx.control.security.deltas` | File | `reference-data`'s outbox poller | order-matcher's `ControlFeedSubscriber` (security source) |

Each published message: `Nats-Msg-Id: "<source>:<version>"` (publish-side idempotency via
JetStream's duplicate window) and a JSON body `{version, epoch, accountId|ticker, displayName|companyName}`.

## order-matcher edge state (`GatewayReplicaStore`, extended — still edge-only, never journaled/snapshotted)

| Change | Detail |
|---|---|
| `AccountRecord`/`SecurityRecord` | gain a `sourceVersion` field: the account-service/reference-data outbox version that produced this record (distinct from the existing `version` field, which stays the internally-assigned journal-facing apply-order counter per ADR-020 — unchanged). |
| `applyAccount(int, boolean, long sourceVersion)` / `applySecurity(String, boolean, boolean, long sourceVersion)` | new overloads used by the durable-feed path; the existing 2/3-arg versions remain, used by the `/risk/control/*` admin API path (which has no source version). |

## New component: `ControlFeedSubscriber` (order-matcher, one instance per source)

Not journaled/snapshotted (rebuilt every boot, same as `GatewayReplicaStore`). Per-source state:

| Field | Purpose |
|---|---|
| `epoch` | Last-seen source epoch; a change mid-stream is a quarantine trigger. |
| `lastAppliedVersion` (a.k.a. watermark) | Highest source version successfully applied; the next live delta must be exactly `lastAppliedVersion + 1` or it's a gap. |
| pre-snapshot delta buffer | Bounded (`risk.bootstrap.buffer-capacity`); holds deltas received after subscribe but before the snapshot install completes; overflow quarantines (NFR-IMRG-OUTBOX-03). |
| `ready` (per source) | Both sources' readiness AND-ed together gates `GatewayReplicaStore.markReady()` (FR-IMRG05). |

## Snapshot wire shape (new, additive endpoints — `GET /account/control-snapshot`, `GET /stocks/control-snapshot`)

```json
{
  "schemaVersion": 1,
  "sourceEpoch": 1,
  "watermark": 42,
  "count": 500,
  "checksum": "sha256:...",
  "records": [ { "id": 22214, "displayName": "..." }, ... ]
}
```
(`ticker`/`companyName` in place of `id`/`displayName` for the security snapshot.) `checksum` is a
SHA-256 over the canonical (id/ticker-sorted) JSON encoding of `records`, recomputed by
`ReplicaBootstrap` and compared before atomic install (ADR-019 step 3).
