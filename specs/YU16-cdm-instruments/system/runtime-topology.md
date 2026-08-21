# Runtime Topology: YU16-cdm-instruments

The inherited tier is unchanged: the same services, ports, cluster members and observability as
YU15. This state widens the instrument universe those services carry — CDM-shaped reference
data, Treasury pricing, bond-aware post-trade and display — and adds one route to
reference-data. No component is added or removed; no deterministic-core process changes.

## Entrypoints

| Entrypoint | Process | Purpose |
|---|---|---|
| `GET /instruments`, `GET /instruments/{instrumentKey}` | reference-data (:18085) | CDM instrument records |
| `GET /instruments/control-snapshot` | reference-data (:18085) | general-name control snapshot (same store/watermark as `/stocks/control-snapshot`) |
| `GET /stocks*` | reference-data (:18085) | retained inherited routes — the YU04 feed's bootstrap |
| `POST /orders` (REST), FIX `D` | cluster gateway | unchanged; gateway-side `UST-` face validation added |
| `pricing.<instrumentKey>` | price-publisher (:18100) | unchanged subjects; Treasury payloads extended |

## Components

| Component | Role | State |
|---|---|---|
| reference-data | instruments module + retained stocks module over one store and one outbox watermark | SQL + outbox (inherited) |
| price-publisher | equity/option quoting inherited; Treasury walk with per-batch shared roll | in-memory quote state |
| order-matcher cluster | unchanged deterministic core; registers the ten new keys through the inherited control feed | replicated (unchanged format 4) |
| cluster gateway | unchanged routing; `UST-` face validation before submission | stateless |
| trade-processor | booking gains Treasury average-cost and `Rejected` fail-closed landing | SQL |
| position-service | rejection-aware trade reads | SQL |
| risk-extract | join gains instrument static; renders schema-2 CSV | object store (write-once, inherited) |
| web-front-end | asset-class filter, Treasury labels/valuation | stateless |

## Networking

| Path | Transport | Notes |
|---|---|---|
| trade-processor → reference-data | HTTP `GET /instruments/{key}` | booking-time Treasury metadata; 2 s / 5 s timeouts, fail closed |
| trade-service → reference-data | HTTP `GET /instruments/{key}` | order validation resolves the CDM record |
| order-matcher → reference-data | HTTP `GET /instruments/control-snapshot` | bootstrap default at this layer; env override unchanged |
| everything else | inherited | no new paths, subjects or ports |

## Startup / Health Order

1. database → reference-data (instruments seed load asserts CDM conditions; a violation fails
   startup loudly)
2. NATS → price-publisher (Treasury seeds from snapshot; no external calls)
3. cluster members → gateway (unchanged)
4. post-trade services in any order — booking-time metadata is resolved per trade, fail closed

Ordering beyond the database is not load-bearing: every consumer of the new data either retries
(feed subscribers) or fails closed per request (booking metadata).

## Degraded Behavior

| Condition | Behavior |
|---|---|
| reference-data down at booking time | A `UST-` booking persists a `Rejected` trade ("Treasury reference metadata is unavailable") and publishes it; no position updates. Equity/ETF bookings do no lookup and are unaffected. |
| reference-data down at bootstrap | The replica bootstrap retries against `/instruments/control-snapshot` exactly as it did against `/stocks/control-snapshot` — same code, repointed URL. |
| A Treasury matures mid-session | Its quotes are suppressed and new orders are rejected at the validation boundary; resting state is untouched (earliest seed maturity is 2028-06-30). |
| Unknown `UST-` key requested | Price API returns 404 with no fallback quote; unknown equities keep the inherited lazy fallback. |
| Seed FIGI missing | The row loads with `BBGTICKER` only and a warning — startup never fails for an unresolvable identifier. |
| CDM condition violated in seed | Startup fails loudly (throw) — a malformed universe never serves. |
| Both control-snapshot routes drift | They cannot drift independently — one handler, one store, one watermark; the contract test asserts equality. |
