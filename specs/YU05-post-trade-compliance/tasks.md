# Tasks: YU05 Post-Trade Compliance Bundle

## Slice 1 (settlement + reconciliation) — done

- [x] T-01 Fix `TradeOrder.fromEvent()` to use `OrderSnapshot.tradeIdFor(e.tradeSeq)` instead of
      `orderIdFor(e.orderRef)` (order-matcher).
- [x] T-02 `TradeBlotter` + `TradeBlotterHandler`: new bounded, replay-rebuilt in-memory trade
      record on the output ring (order-matcher).
- [x] T-03 `ReconController`: `GET /recon/trades/blotter` (order-matcher), token + operator header
      auth, own config namespace.
- [x] T-04 `TradeService`: idempotent booking keyed on the now-deterministic id (trade-processor).
- [x] T-05 `settlementdate` column added to the real runtime MariaDB schema (k8s init ConfigMap
      override, not the legacy `database/initialSchema.sql`) + `SettlementService` T+N sweep +
      `POST /trades/{id}/settlement/force` (trade-processor).
- [x] T-06 `ReconciliationService`: scheduled sweep against the order-matcher blotter,
      MATCHED/MISSING_IN_PROJECTION/FIELD_MISMATCH classification, `GET /recon/status`
      (trade-processor).
- [x] T-07 Metrics: `traderx_recon_*`, `traderx_settlement_swept_total`, `traderx_trade_blotter_*`.
- [x] T-08 Tests: `TradeBlotterTest`, `TradeServiceIdempotencyTest`, `SettlementServiceTest`,
      `ReconciliationServiceTest`.
- [x] T-09 Spec pack (spec, requirements, ADRs 022-025, architecture, runtime-topology,
      data-model, contract-delta, plan, research, this file); pipeline hooks; catalog +
      runtime-harness wiring.

## Deferred — later commits (see plan.md)

- [ ] T-10 Full-history `ORPHAN_IN_PROJECTION` detection (unbounded/spillover blotter retention or
      a direct journal-replay comparator) — FR-PTC10.
- [ ] T-20 Regulatory reporting: journal-sourced CAT/TRACE-style audit export, date-range windowed,
      reproducible byte-for-byte — ADR-023, FR-PTC20/21/22.
- [ ] T-30 TCA: execution-quality computation (arrival/VWAP/TWAP, slippage bps) behind a pluggable
      benchmark-source interface — ADR-024, FR-PTC30/31/32.
- [ ] T-40 Real auth/entitlements (OIDC): principal resolution + entitlement gating for every
      settlement/recon/reporting/TCA API, feeding YU03's unused `principalKey` path — ADR-025,
      FR-PTC40/41/42.
- [ ] T-41 Grafana dashboard for the settlement/recon metric set (mirrors YU03's
      `traderx-risk-gateway.json`).
- [ ] T-42 Full container smoke: order fill → blotter → recon sweep → settlement sweep, end to end
      against a real MariaDB in an isolated staging namespace (same discipline as YU03).
- [ ] T-43 Isolated staging Cloud Build trigger + Cloud Deploy pipeline for YU05 (same pattern as
      YU03/YU04) — **requires explicit user go-ahead before touching any live CI/CD resource.**
