# Contract Delta: YU04 over YU03-in-memory-risk-gateway

All of YU03's admission-facing contracts (`clientOrderId`, `RiskRejectionBody`, synchronous market
trades, `/risk/control/*`, the metric set) are retained unchanged — this state does not touch the
order/trade/position command path at all, only how the Gateway replica's account/security universe
gets populated. New/changed contracts are additive; nothing existing is removed or narrowed.

## 1. `account-service`: new endpoint, existing endpoint untouched

- `GET /account/` (existing) — **unchanged**, still returns a plain `Account[]` array. Any existing
  consumer (UI, other services) is unaffected.
- `GET /account/control-snapshot` (**new**) — watermarked snapshot for `ReplicaBootstrap`: see
  `data-model.md` for the wire shape.
- `POST /account/` / `PUT /account/` (existing) — unchanged request/response shape; now also writes
  one `account_control_outbox` row in the same transaction (invisible to the caller).

## 2. `reference-data`: new write path + new endpoint (was previously read-only)

- `GET /stocks` (existing) — **unchanged response shape** (`Stock[]`), now backed by the new
  `stocks` MariaDB table instead of the in-memory CSV cache (transparent to callers).
- `GET /stocks/:ticker` (existing) — unchanged.
- `GET /stocks/control-snapshot` (**new**) — watermarked snapshot for `ReplicaBootstrap`.
- `POST /stocks` (**new** — `reference-data` had no write endpoint before this state) — body
  `{ticker, companyName}`; inserts into `stocks` + `stocks_control_outbox` in one transaction.
  Justification for adding a write path to a previously read-only service: see ADR-021 — without
  it, `reference-data`'s "durable versioned delta feed" would never emit a single delta after the
  initial CSV seed, which does not exercise (or satisfy the spirit of) FR-IMRG32/33.

## 3. `order-matcher`: `ReplicaBootstrap` behavior change (no REST/NATS contract visible to clients)

Internal only — no change to any order-matcher REST/NATS contract a client observes. `ReplicaBootstrap`
now:
- Subscribes to `TRADERX_CONTROL_ACCOUNT` / `TRADERX_CONTROL_SECURITY` JetStream streams instead of
  making a single cold `GET /account/` / `GET /stocks` call.
- Calls the new `.../control-snapshot` endpoints instead of the plain array endpoints.
- Retries per-source (not globally) on gap/regression/epoch-mismatch (FR-IMRG34), instead of
  retrying the whole one-shot fetch with a flat backoff.

## 4. New Prometheus metrics (bounded cardinality, `source`/`reason` labels only)

Order-matcher: `traderx_replica_source_watermark{source}`, `traderx_replica_quarantine_total{source,reason}`.
`account-service` / `reference-data`: `traderx_outbox_publish_lag_seconds{source}`,
`traderx_outbox_unpublished_rows{source}` (new metrics endpoints on both services — neither exposes
Prometheus metrics today; each gains a minimal `/metrics` endpoint scoped to just these two series,
not a general observability rework of either service).

## Not changed

Order/trade/position payload shapes and subjects, matching policy, output-ring topology, BLP
decision pipeline, journal/snapshot format, `/risk/control/*` request/response shapes, UI journeys,
idempotency/reservation mechanics. `account-service`/`reference-data`'s non-control-plane APIs
(positions, trades, people, health) are untouched.
