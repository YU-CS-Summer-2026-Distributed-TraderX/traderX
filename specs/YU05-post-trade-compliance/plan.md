# Implementation Plan: YU05 Post-Trade Compliance Bundle

Parent: `YU03-in-memory-risk-gateway`. Approach: smallest meaningful vertical slice first — fix the
one wiring gap that blocks all four bundled sub-capabilities (unstable trade identity), then build
settlement + reconciliation on top of the now-stable id, then extend to reporting/TCA/auth in
later commits.

## Slice 1 (this commit) — delivered

1. **Deterministic trade identity** (`order-matcher`): `TradeOrder.fromEvent()` now calls
   `OrderSnapshot.tradeIdFor(e.tradeSeq)` instead of `orderIdFor(e.orderRef)` — the fix the
   existing helper's doc comment already specified but that was never wired up.
2. **Trade blotter** (`order-matcher`, `lmax/TradeBlotterHandler` + `lmax/TradeBlotter`): new
   output-ring handler capturing every `KIND_TRADE_BOOKED` event into a bounded, replay-safe
   in-memory store (id, accountId, securityId ticker, side, qty, price, execTimeMillis, tradeSeq).
   Unlike the NATS/DB bridge handlers it does **not** suppress on `readModel.isReplaying()` — the
   blotter must be rebuilt from replay, not just populated going forward.
3. **Recon read endpoint** (`order-matcher`, `controller/ReconController`): `GET
   /recon/trades/blotter?sinceSeq=` (paginated forward scan), authenticated the same way as
   `/risk/control/*` (token + operator header), its own config namespace (`recon.control.token`).
4. **Stable-id booking + idempotency** (`trade-processor`, `TradeService`): use the incoming
   `TradeOrder.id` verbatim as the MariaDB row id; short-circuit (no insert, no position mutation)
   if a trade with that id already exists — makes redelivery safe now that the id is deterministic
   rather than randomly minted per delivery attempt.
5. **Settlement state machine** (`trade-processor`, `SettlementService`): T+N (default 1 business
   day, `settlement.t-plus-days`) sweep advancing `Processing → Settled`; `settlementdate` column
   added to the real runtime MariaDB schema (the k8s init ConfigMap, not the legacy
   `database/initialSchema.sql` — see "Generation pipeline gotcha" in research.md); manual
   override endpoint `POST /trades/{id}/settlement/force` (same auth pattern as recon/risk).
6. **Reconciliation sweep** (`trade-processor`, `ReconciliationService`): scheduled forward walk
   over the order-matcher blotter, classifying each entry vs. the local MariaDB row; `GET
   /recon/status` summary + bounded Prometheus counters.
7. **Tests**: `TradeBlotterTest` (order-matcher: capture, bounded eviction, replay-safety),
   `TradeOrderIdTest`/existing hot-path parity coverage for the `tradeIdFor` wiring,
   `SettlementServiceTest` + `ReconciliationServiceTest` (trade-processor: idempotent booking,
   T+N transition, MATCHED/MISSING/MISMATCH classification).
8. **State packaging**: this spec pack, pipeline hooks, catalog registration, runtime harness
   wiring (mirrors YU03/YU04 exactly).

## Key decisions (see ADRs + spec.md)

- Fix trade identity first (ADR-022) rather than building settlement/recon on top of the existing
  unstable id — every later sub-capability needs the fix, so doing it first avoids rework.
- Trade blotter lives in order-matcher (the journal-adjacent process) and is populated via the
  output-ring handler chain during both live operation and recovery replay — no snapshot format
  change needed, unlike YU03's risk-state sections, because the blotter only needs to be rebuilt
  on restart, not restored instantly at snapshot-load time.
- Reconciliation and settlement are trade-processor-side (MariaDB-adjacent), never reach into the
  BLP/journal synchronously, and never mutate journal/BLP state — consistent with "MariaDB is a
  read-model projection, never authoritative" (FR-IMRG41, inherited invariant).
- Full-history orphan detection, regulatory reporting, TCA, and real auth are deferred — see
  "Sequencing after slice 1."

## Sequencing after slice 1

1. **Full-history reconciliation** — either extend the blotter to unbounded/spillover-to-disk
   retention, or replay the input journal directly (mirroring `JournalReader`) to detect
   `ORPHAN_IN_PROJECTION` with full confidence (`FR-PTC10`).
2. **Regulatory reporting** (`FR-PTC20`–22) — CAT/TRACE-style audit export sourced from the
   journal (not the DB projection), date-range windowed, reproducible byte-for-byte from replay.
3. **TCA** (`FR-PTC30`–32) — arrival/VWAP/TWAP benchmark computation over settled trades; pluggable
   historical-price source (synthetic price-publisher today, real TAQ data when the transfer
   lands, without changing the computation contract).
4. **Real auth + entitlements** (`FR-PTC40`–42) — OIDC principal resolution gating all of the
   above, and finally feeding the risk gateway's already-wired-but-unused `principalKey` path
   (closes YU03's FR-IMRG02/FR-IMRG30 deferrals).
5. **Observability** — Grafana dashboard for the settlement/recon metric set (mirrors YU03's
   `traderx-risk-gateway.json` pattern).

## Validation strategy

Unit + integration tests in-tree for the blotter (capture, bounded eviction, replay path),
settlement state transitions, and reconciliation classification logic. Full container smoke
(order fill → blotter → recon sweep → settlement sweep, end to end against a real MariaDB) is
deferred to the isolated-staging verification pass, same discipline as YU03.
