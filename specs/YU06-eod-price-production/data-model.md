# Data Model: YU06 — EOD Price Production + Overnight Batch Chain

All new tables live in the **real runtime schema** — the k8s database-init ConfigMap
(`kubernetes-runtime/manifests/base/database-init-configmap.yaml`), not the dead
`database/initialSchema.sql` (see research.md gotcha). MariaDB, lowercase identifiers.

## `eod_price_session` (header — one row per produced version)

| Column | Type | Notes |
|---|---|---|
| `session_date` | DATE | The trading session being closed. |
| `version` | INT | 1-based; incremented per re-production/override for the same date. |
| `status` | VARCHAR(16) | `DRAFT` (produced, not yet gated) → `PUBLISHED` (gated, event emitted). Immutable once `PUBLISHED`. |
| `instrument_count` | INT | Instruments priced in this version. |
| `flagged_count` | INT | Unresolved `STALE`/`SPIKE`/`MISSING` in this version (must be 0 to publish). |
| `created_at` | DATETIME | Production wall-clock (audit only, not a consistency key). |
| `published_at` | DATETIME, nullable | Set when status → `PUBLISHED`. |

PK `(session_date, version)`. A published version's rows are never updated — a correction writes a
new `(session_date, version+1)`.

## `eod_price_snapshot` (the versioned closing prices — immutable)

| Column | Type | Notes |
|---|---|---|
| `session_date` | DATE | FK part → `eod_price_session`. |
| `version` | INT | FK part → `eod_price_session`. |
| `security` | VARCHAR(16) | Ticker. |
| `closing_price` | DECIMAL(18,6), nullable | Last trade price at close; `null` iff `quality = MISSING` and not overridden. |
| `quality` | VARCHAR(16) | `OK` / `STALE` / `SPIKE` / `MISSING` / `OVERRIDDEN`. |
| `source_tick_millis` | BIGINT, nullable | Event-time of the sample the price came from (staleness evidence). |
| `override_reason` | VARCHAR(255), nullable | Set only for `OVERRIDDEN`. |

PK `(session_date, version, security)`. This is the single source of truth every downstream job
reads — keyed by the exact `(session_date, version)` the gate event names.

**Quality classification** (computed at production time against `eod.quality.*` config):

| Quality | Meaning |
|---|---|
| `OK` | Fresh sample, move within bounds. |
| `STALE` | Newest sample older than `eod.quality.staleness-seconds` before session close. |
| `SPIKE` | `|close − priorPublishedClose| / priorPublishedClose > eod.quality.max-move-pct`. |
| `MISSING` | Instrument in the expected universe with no sample at all (`closing_price` null). |
| `OVERRIDDEN` | An operator supplied a corrected price for a previously flagged instrument (new version). |

The **expected universe** for `MISSING` detection is `eod.universe` (config; default = every ticker
`PriceHistoryStore` has observed this session). An instrument in the config list with no sample is
`MISSING`; the fail-safe blocks publication until it is overridden or removed.

## `eod_position_pnl` (consumer output — immutable, position-service)

Written by the position-service EOD consumer when it processes an `EOD_PRICES_READY` event.

| Column | Type | Notes |
|---|---|---|
| `session_date` | DATE | From the event. |
| `version` | INT | From the event — ties the mark to the exact price version used. |
| `account_id` | INT | Account marked. |
| `security` | VARCHAR(16) | Held security. |
| `quantity` | INT | Position quantity at mark time. |
| `closing_price` | DECIMAL(18,6) | The `(session_date, version, security)` snapshot price used. |
| `market_value` | DECIMAL(20,6) | `quantity × closing_price`. |
| `marked_at` | DATETIME | Consumer wall-clock (SLA evidence). |

PK `(session_date, version, account_id, security)`. Immutable/idempotent: reprocessing the same
event (durable redelivery) is a no-op via `INSERT ... ON DUPLICATE KEY UPDATE`-guarded upsert with
identical values, so at-least-once delivery is safe. An account with any halted (missing/flagged)
holding writes **no** rows for that account (fail-safe) and is counted in `eod_pnl_halted_total`.

## Events (NATS JetStream, durable — stream `TRADERX_EOD`, file storage)

### `eod.prices.ready` (`EOD_PRICES_READY`) — producer → consumers

```json
{ "sessionDate": "2026-07-08", "version": 3, "instrumentCount": 42,
  "publishedAtMillis": 1751990400000 }
```

Published by trade-processor **after** the snapshot rows for `(sessionDate, version)` are committed
and status is `PUBLISHED`. Durable so a consumer that boots after publish still receives it.

### `eod.pnl.done` — position-service → next stage (chain link)

```json
{ "sessionDate": "2026-07-08", "version": 3, "accountsMarked": 17,
  "accountsHalted": 1, "completedAtMillis": 1751990460000 }
```

Emitted when the consumer finishes a session. This is the next link the teammate's VaR batch or
YU05 NAV can subscribe to (not consumed within this state).

## Config (namespace `eod.*`)

| Key | Default | Where | Meaning |
|---|---|---|---|
| `eod.quality.staleness-seconds` | 300 | trade-processor | Sample-age threshold for `STALE`. |
| `eod.quality.max-move-pct` | 20 | trade-processor | Abs % move vs. prior close for `SPIKE`. |
| `eod.universe` | (empty = all seen) | trade-processor | Expected instrument list for `MISSING` detection. |
| `eod.session.auto-publish` | true | trade-processor | Auto-publish on close when zero flags. |
| `eod.stream` | `TRADERX_EOD` | both | JetStream stream name. |
| `eod.subject.prices-ready` | `eod.prices.ready` | both | Gate-event subject. |
| `eod.subject.pnl-done` | `eod.pnl.done` | position-service | Chain-link subject. |
| `eod.consumer.durable` | `eod-pnl` | position-service | Durable consumer name. |

## Reused, unchanged

- `PriceHistoryStore` (trade-processor, YU05) — per-ticker bounded time-ordered `(price,
  timestampMillis)` samples; the closing price is its newest sample per ticker. Read-only reuse.
- `JwtAuthenticator` / `AuthController` (trade-processor, YU05) — admin gate for all EOD endpoints.
- `JetStreamControlFeedPublisher` pattern (account-service, YU04) — the durable-publish blueprint.
- `Position` / `PositionRepository` (position-service) — the positions to mark.
