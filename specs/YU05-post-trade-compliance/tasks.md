# Tasks: YU05 Post-Trade Compliance Bundle

## Settlement + reconciliation (ADR-022) — done

- [x] T-01 Fix `TradeOrder.fromEvent()` to use `OrderSnapshot.tradeIdFor(e.tradeSeq)` (order-matcher,
      legacy `/trades` NATS path — the live writer, `ProjectorHandler`, already did this correctly).
- [x] T-02 `TradeBlotter` + `TradeBlotterHandler`: new bounded, replay-rebuilt in-memory trade
      record on the output ring (order-matcher).
- [x] T-03 `ReconController`: `GET /recon/trades/blotter` (order-matcher), token + operator header
      auth, own config namespace.
- [x] T-04 `TradeService`: idempotent booking keyed on the now-deterministic id (trade-processor,
      legacy path — the live path was already idempotent via `INSERT IGNORE`).
- [x] T-05 `settlementdate` column added to the real runtime MariaDB schema (k8s init ConfigMap
      override, not the legacy `database/initialSchema.sql`); **fix applied where it actually
      matters, `ProjectorHandler.toTrade()`** (order-matcher, the live writer) — no longer sets
      `Settled` immediately, sets `Processing` + a real T+N settlement date; `SettlementService`
      T+N sweep + `POST /trades/{id}/settlement/force` (trade-processor).
- [x] T-06 `ReconciliationService`: scheduled sweep against the order-matcher blotter,
      MATCHED/MISSING_IN_PROJECTION/FIELD_MISMATCH classification, `GET /recon/status`
      (trade-processor).
- [x] T-07 Corrected two existing integration tests (`LmaxHotPathParityTest`) that asserted
      instant-`Settled` — updated to `Processing`, confirming the fix landed on the live path.
- [x] T-08 Tests: `TradeBlotterTest`, `TradeServiceIdempotencyTest`, `SettlementServiceTest`,
      `ReconciliationServiceTest`, `ProjectorHandlerTest` (new — tests the actual live writer).
- [x] T-09 Spec pack (spec, requirements, ADRs 022-025, architecture, runtime-topology,
      data-model, contract-delta, plan, research, this file); pipeline hooks; catalog +
      runtime-harness wiring.

## Full-history orphan detection (FR-PTC10) — done

- [x] T-10 `LmaxEngine.reindexFullHistory()`: on-demand shadow-engine full journal replay into an
      unbounded `TradeBlotter`, reusing `verifyJournalReplay()`'s construction pattern.
      `POST /recon/full-history/reindex` + `GET /recon/full-history/trades` (order-matcher).
- [x] T-11 `ReconciliationService.runOrphanSweep()`: triggers the reindex, diffs every local trade
      id against it, flags `ORPHAN_IN_PROJECTION`. `POST /recon/orphan-sweep` +
      `GET /recon/orphan-sweep/last` (trade-processor).

## Regulatory reporting (ADR-023, FR-PTC20-22) — done

- [x] T-20 `AuditRecord` + `AuditLogHandler`: captures every reportable output kind (accept/
      reject/partial-fill/fill/cancel/trade-booked) during a shadow replay, filtered by
      `OutputEvent.inputSeq` range. `LmaxEngine.generateRegulatoryReport(fromSeq, toSeq)` +
      `GET /regulatory/report` (order-matcher, own token namespace).
- [x] T-21 Tests: `AuditLogHandlerTest` (kind coverage, range filtering, unbounded-`toSeq`).

## TCA (ADR-024, FR-PTC30-32) — done, VWAP deferred

- [x] T-30 `PriceHistoryStore` + `PriceTickHandler`: bounded per-ticker price history fed by
      price-publisher's existing `pricing.*` NATS feed (trade-processor, no BLP involvement).
- [x] T-31 `TcaService`: arrival price + TWAP benchmark + signed slippage-bps.
      `GET /tca/report/{tradeId}` (trade-processor, own token namespace).
- [x] T-32 Tests: `PriceHistoryStoreTest` (TWAP math, bounded eviction), `TcaServiceTest` (slippage
      sign convention for buy/sell, null-benchmark honesty).
- [ ] T-33 VWAP — deferred, needs a real per-tick-volume data source (e.g. the professor's TAQ
      dataset); `PriceHistoryStore`'s contract doesn't need to change to add it later.

## Real auth/entitlements (ADR-025, FR-PTC40/41) — done; FR-PTC42 deferred

- [x] T-40 `JwtAuthenticator`/`JwtPrincipal`/`JwtTokenMinter` (order-matcher and trade-processor,
      each its own copy — no shared library between the modules): real HS256 signature
      verification via JDK `javax.crypto` + Jackson, no new dependency, no live OIDC provider.
- [x] T-41 Retrofitted every YU05 endpoint from token+operator headers to JWT: `ReconController`
      and `RegulatoryReportController` (order-matcher, `admin`-only), `SettlementController`,
      `TcaController`, and `ReconStatusController`'s orphan-sweep endpoints (trade-processor,
      account-entitlement or `admin`). `ReconciliationService` mints its own long-lived
      service-account JWT for its machine-to-machine calls into order-matcher.
- [x] T-41a `POST /auth/dev-token` (trade-processor): local dev/test token minting, gated by its
      own master secret.
- [x] T-41b Tests: `JwtAuthenticatorTest` (both modules) — valid round-trip, wrong-secret/tampered/
      expired/malformed rejection, `admin` override, entitlement checks.
- [ ] T-42 Feed the risk gateway's `principalKey` path (YU03) — deferred; needs wiring into order
      *submission* (`OrderMatcherService`/`GatewayReplicaStore`), a hot-path-adjacent surface this
      state deliberately never touched (FR-PTC07/NFR-PTC09).

## Observability (done)

- [x] T-50 Wired `traderx_recon_matched_total`, `traderx_recon_missing_in_projection_total`,
      `traderx_recon_field_mismatch_total`, `traderx_recon_cursor`, `traderx_recon_orphan_total`,
      `traderx_settlement_swept_total` into Micrometer (`ReconciliationService`/`SettlementService`,
      trade-processor, mirrors the existing `SystemController` `Gauge` pattern).
- [x] T-51 `traderx-post-trade-compliance.json` Grafana dashboard (recon cursor/classifications,
      settlement rate, TCA/regulatory-report request rate), added to the same aggregated
      ConfigMap every other dashboard in this lineage uses.

## Deferred — later commits (see plan.md)

- [ ] T-60 Full container smoke: order fill → blotter → recon sweep → settlement sweep, end to end
      against a real MariaDB in an isolated staging namespace (same discipline as YU03).
- [ ] T-61 Isolated staging Cloud Build trigger + Cloud Deploy pipeline for YU05 (same pattern as
      YU03/YU04) — **requires explicit user go-ahead before touching any live CI/CD resource.**
- [ ] T-62 VWAP (see T-33) and FR-PTC42 (`principalKey` wiring, see T-42) remain open follow-ons.
