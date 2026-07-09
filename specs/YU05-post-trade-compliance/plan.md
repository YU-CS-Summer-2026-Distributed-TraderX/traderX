# Implementation Plan: YU05-post-trade-compliance

## Goal

Build a back-office compliance layer strictly downstream of the BLP — settlement + reconciliation,
regulatory reporting, TCA, and real JWT auth/entitlements — all reading the journal's executed-fill
stream as their common source of truth, without ever sitting on the order-admission path or
mutating journal/BLP state. The foundation is a deterministic trade id linking every MariaDB trade
row back to the journal fill that produced it.

## Workstreams

1. Deterministic trade identity + settlement (order-matcher, trade-processor)
   - Thread `OrderSnapshot.tradeIdFor(tradeSeq)` through both the live projector path and the legacy
     NATS booking path; make booking idempotent on that id.
   - Add the `settlementdate` column to the real runtime MariaDB schema; `ProjectorHandler.toTrade()`
     books `Processing` with a real T+N date instead of instant `Settled`; a scheduled sweep advances
     due trades; `POST /trades/{id}/settlement/force` is the manual override.
2. Reconciliation (order-matcher blotter + trade-processor sweep)
   - A bounded, replay-safe `TradeBlotter` on the output ring; a scheduled forward sweep classifying
     each entry `MATCHED`/`MISSING_IN_PROJECTION`/`FIELD_MISMATCH`; `GET /recon/status`.
   - Full-history orphan detection: an on-demand shadow-engine full journal replay into an unbounded
     index, cross-checked against every local trade id (`ORPHAN_IN_PROJECTION`).
3. Regulatory reporting (order-matcher)
   - `AuditLogHandler` captures every reportable lifecycle kind during a shadow replay, windowed by
     input-sequence range; `GET /regulatory/report`, reproducible byte-for-byte from the journal.
4. TCA (trade-processor)
   - `PriceHistoryStore` fed by price-publisher's `pricing.*` feed; `TcaService` computes arrival
     price, TWAP benchmark, and signed slippage-bps; `GET /tca/report/{tradeId}`.
5. Real auth + entitlements (order-matcher, trade-processor)
   - `JwtAuthenticator`/`JwtPrincipal` (HS256, JDK crypto) gating every new endpoint — account-scoped
     entitlement checks or an `admin` claim for cross-account endpoints; `POST /auth/dev-token` for
     local dev/testing.
6. Observability + state registration
   - Micrometer recon/settlement counters and a `traderx-post-trade-compliance.json` Grafana
     dashboard; spec pack, generation hook + render scripts, catalog entry, runtime harness.

## Key decisions (see ADRs + spec.md)

- Fix trade identity first (ADR-022): every capability depends on a stable id linking a trade row to
  its journal fill, so wiring `tradeIdFor` before building on top avoids rework.
- The trade blotter lives in order-matcher (journal-adjacent) and is populated by an output-ring
  handler during both live operation and recovery replay — no snapshot-format change, because it
  only needs rebuilding on restart, not instant restore at snapshot load.
- Reconciliation, settlement, and TCA are trade-processor-side (MariaDB-adjacent); they never reach
  synchronously into the BLP/journal and never mutate it — consistent with "MariaDB is a read-model
  projection, never authoritative" (FR-IMRG41, inherited).
- Regulatory reporting and full-history reindex are read-only shadow-engine replays, never touching
  the live BLP/journal.
- Real auth is HS256 JWT (ADR-025), not full OIDC — there is no live IdP in this environment; each
  module carries its own authenticator copy rather than a shared library.

## Exit Criteria

- Spec and tasks are complete and reviewed.
- Generation hook produces expected artifacts and exits successfully.
- Unit suites pass for order-matcher and trade-processor across all five capabilities.
- Generated shared files retain every ancestor state's content alongside this state's additions.
- State can be published to `code/generated-state-YU05-post-trade-compliance`.

## Validation status

- All five capabilities implemented and unit-tested in-tree (blotter capture/eviction/replay,
  settlement transitions, reconciliation classification, full-history orphan sweep, audit-log kind
  coverage, TWAP/slippage math, JWT round-trip/rejection/entitlement).
- Two follow-ons remain open and are tracked in `tasks.md`: feeding the risk gateway's `principalKey`
  path from real entitlement resolution (FR-PTC42 — needs order-admission wiring this state
  deliberately never touched) and VWAP (FR-PTC32 — blocked on a real per-tick-volume source).
- Full container smoke and an isolated staging CI/CD pipeline are open and require explicit user
  go-ahead before touching any live Cloud Build/Deploy resource.
