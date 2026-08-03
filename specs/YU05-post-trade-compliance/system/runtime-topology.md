# Runtime Topology: YU05

## Deployment topology

Unchanged from `YU04-durable-control-feeds` (→ `YU03` → `YU02-lmax-kubernetes`): order-matcher
remains the BLP StatefulSet; trade-processor remains its existing Deployment. No new services or
pods — every capability in this state is code added to two existing components.

## New runtime behavior

- **order-matcher**:
  - one new output-ring handler (`TradeBlotterHandler`) building the bounded, replay-safe blotter;
  - `ReconController` (`GET /recon/trades/blotter`, `POST /recon/full-history/reindex`,
    `GET /recon/full-history/trades`) — the full-history endpoints run an on-demand shadow-engine
    full journal replay into an unbounded index;
  - `RegulatoryReportController` (`GET /regulatory/report?fromSeq=&toSeq=`) — a shadow-engine replay
    capturing every reportable lifecycle event in the range;
  - its own `JwtAuthenticator` gating all of the above (`admin` claim required — these are
    cross-account surfaces);
  - config: `recon.blotter.capacity`, `recon.blotter.page-size`, `auth.jwt.secret`.
- **trade-processor**:
  - `SettlementService` (T+N sweep + `POST /trades/{id}/settlement/force`, MariaDB only);
  - `ReconciliationService` (scheduled forward sweep polling order-matcher's blotter over HTTP, plus
    `POST /recon/orphan-sweep` / `GET /recon/orphan-sweep/last`) — mints its own service-account JWT
    for its machine-to-machine calls into order-matcher;
  - `TcaService` + `PriceHistoryStore` (`GET /tca/report/{tradeId}`), the store fed by
    price-publisher's existing `pricing.*` feed;
  - `AuthController` (`POST /auth/dev-token`) minting JWTs for local dev/testing, gated by
    `auth.dev-token.master-secret`; its own `JwtAuthenticator` gating settlement-force/TCA
    (account-entitlement) and orphan-sweep (`admin`);
  - config: `settlement.t-plus-days`, `recon.poll.interval-ms`, `order-matcher.base-url`,
    `auth.jwt.secret` (must match order-matcher's), `auth.dev-token.master-secret`.

## Startup / degraded behavior

| Condition | Effect |
|---|---|
| order-matcher unreachable from trade-processor (recon poll) | Reconciliation sweep skips this cycle, logs a warning, retries next interval; settlement sweep (MariaDB-only) is unaffected. |
| MariaDB unreachable | Settlement/recon sweeps fail their current cycle (existing Spring datasource retry/backoff applies); no impact on order-matcher, no impact on the BLP. |
| order-matcher restart (recovery replay) | Trade blotter is fully rebuilt from journal replay before the recon endpoint would be queried again (recovery completes before `ACCEPTING_TRAFFIC`, and the blotter handler runs synchronously with recovery replay — see research.md). |
| Duplicate `TradeOrder` delivery (NATS redelivery) | No-op — booking is idempotent on the deterministic id (FR-PTC08). |
| Full-history reindex / regulatory report requested | Runs a read-only shadow-engine replay; the live BLP, journal, and matching pipeline are untouched. A concurrent request re-runs its own shadow replay — never mutates shared live state. |
| Missing or invalid JWT on any YU05 endpoint | Rejected (401/403) before any work is done; account-scoped tokens are rejected from cross-account endpoints. |
