# Runtime Topology: YU05

## Deployment topology

Unchanged from `YU03-in-memory-risk-gateway` (→ `YU02-lmax-kubernetes`): order-matcher remains the
BLP StatefulSet; trade-processor remains its existing Deployment. No new services/pods introduced
in slice 1 — the new capabilities are code additions to two existing components.

## New runtime behavior

- **order-matcher**: one new output-ring handler (`TradeBlotterHandler`) and one new controller
  (`ReconController`) exposing `/recon/trades/blotter`. No new ports, no new config beyond
  `recon.blotter.capacity`, `recon.blotter.page-size`, `recon.control.token`.
- **trade-processor**: one new scheduled job (`ReconciliationService`, polling order-matcher's
  recon endpoint over HTTP — a new outbound dependency from trade-processor to order-matcher that
  did not exist before) and one new scheduled job (`SettlementService`, no external calls, MariaDB
  only). New config: `settlement.t-plus-days`, `recon.poll.interval-ms`, `recon.control.token`
  (must match order-matcher's), `order-matcher.base-url` (new — trade-processor previously had no
  reason to call order-matcher directly).

## Startup / degraded behavior

| Condition | Effect |
|---|---|
| order-matcher unreachable from trade-processor (recon poll) | Reconciliation sweep skips this cycle, logs a warning, retries next interval; settlement sweep (MariaDB-only) is unaffected. |
| MariaDB unreachable | Settlement/recon sweeps fail their current cycle (existing Spring datasource retry/backoff applies); no impact on order-matcher, no impact on the BLP. |
| order-matcher restart (recovery replay) | Trade blotter is fully rebuilt from journal replay before the recon endpoint would be queried again (recovery completes before `ACCEPTING_TRAFFIC`, and the blotter handler runs synchronously with recovery replay — see research.md). |
| Duplicate `TradeOrder` delivery (NATS redelivery) | No-op — booking is idempotent on the deterministic id (FR-PTC08). |

## Deferred (later commits)

Full-history orphan reconciliation, regulatory reporting export, TCA computation, real
auth/entitlements (all still specified but not runtime-active in slice 1).
