# Feature Specification: Post-Trade Compliance Bundle (state YU05)

**State id**: `YU05-post-trade-compliance`
**Parent state**: `YU04-durable-control-feeds` (reparented 2026-07-08; was originally a sibling of
YU04 off `YU03-in-memory-risk-gateway` directly — see "Reparenting" note below)
**Created**: 2026-07-06
**Status**: All five sub-capabilities implemented — settlement/reconciliation (incl. full-history
orphan detection), regulatory reporting, TCA, and real JWT-based auth/entitlements gating every
new endpoint (see `generation/implementation-status.md` for verification evidence). Feeding the
risk gateway's `principalKey` path (FR-PTC42) remains deferred — it needs order-admission wiring
outside this state's scope.
**Input**: New roadmap work — combines four items from the production-realism roadmap
(`HANDOFF-production-realism.md`) into a single spec-kit state at the user's explicit request, to
avoid one-state-per-item sprawl: post-trade settlement + reconciliation, regulatory reporting,
TCA, and real auth/entitlements.

## Why these four are bundled together

Settlement/reconciliation, regulatory reporting, and TCA are not four unrelated features — they
are three different **views over the same underlying record**: an executed fill (symbol, price,
qty, side, account, timestamp, deterministic trade id) sourced from the order-matcher journal.
Real auth/entitlements is the access-control layer that makes exposing that record as real
reports/APIs safe rather than wide open — it is the connective tissue, not a fourth unrelated
bolt-on.

Two items considered for this bundle were deliberately **excluded**: market surveillance (needs
L2 order-book data that doesn't exist anywhere in this system yet — order-matcher only emits
last-trade prints) and market data dissemination (the L2-publishing prerequisite for surveillance,
which nothing in this bundle needs). Both are deferred to a future `YU06` once there is an actual
consumer for book depth.

The professor's ~3TB historical NYSE TAQ dataset (trades + NBBO quotes, not L2) is a natural,
*optional* future input to TCA's benchmark computation — it does not drive scope here.

## Reparenting (2026-07-08)

This state originally branched directly off `YU03-in-memory-risk-gateway`, as a sibling of
`YU04-durable-control-feeds` (both parented on YU03). Per explicit request, the lineage was
changed to a straight chain — `YU03 -> YU04 -> YU05` — so YU05 now depends on YU04 rather than
bypassing it. Mechanically: `git rebase --onto YU04-durable-control-feeds <old-fork-point>
YU05-post-trade-compliance`, plus a manual 3-way content merge (base = the pre-YU04 file, "ours" =
YU05's existing override, "other" = YU04's override, via `git merge-file`) for the three files both
states independently override at the same path — `LmaxEngine.java`, order-matcher's
`application.properties`, and `LmaxHotPathParityTest.java` — since each state stores a full copy
of its overrides rather than a diff, and a plain history rebase alone would have let YU05's
pre-YU04 copy of those files silently clobber YU04's control-feed additions during generation.
One genuine (not just additive) conflict surfaced: YU04 renamed the REST-based
`risk.bootstrap.accounts-url`/`securities-url` properties to a JetStream-based property set
(`risk.bootstrap.account-stream`, `-snapshot-url`, etc., consumed by `ReplicaBootstrap.java`) —
resolved by keeping YU04's new property names (confirmed via the actual Java `@Value` bindings)
and dropping the now-dead old ones, with YU05's own properties appended after unchanged.

## Requirements

New requirement namespace `PTC` (`FR-PTCxx`, `NFR-PTCxx`), grouped by sub-capability:

- **Settlement + reconciliation, incl. full-history orphan detection** (`FR-PTC01`–`FR-PTC10`): implemented.
- **Regulatory reporting** (`FR-PTC20`–`FR-PTC22`): implemented.
- **TCA** (`FR-PTC30`–`FR-PTC32`): implemented (VWAP deferred — see below).
- **Real auth + entitlements** (`FR-PTC40`–`FR-PTC42`): FR-PTC40/41 implemented (real JWT, not
  full OIDC — no live IdP in this environment); FR-PTC42 (feeding YU03's `principalKey` path,
  FR-IMRG02/FR-IMRG30) deferred — needs order-admission wiring outside this state's scope.

Full per-requirement status: `requirements/functional-delta.md` / `requirements/nonfunctional-delta.md`.

## Foundational fix: deterministic trade identity (ADR-022)

Everything else in this state depends on a stable id linking a MariaDB trade row back to the
journal fill that produced it. `OrderSnapshot.tradeIdFor(tradeSeq)` (deterministic, snapshot-
persisted, replay-safe) already existed for this — but was unwired in `TradeOrder.fromEvent`
(the *optional*, disabled-by-default legacy `/trades` NATS path) and ignored entirely by
trade-processor's `TradeService`, which minted `UUID.randomUUID()` instead. Both are now fixed.

**Correction made during implementation, before finalizing this doc**: initial investigation
assumed this was *the* live bug. It wasn't — `output.legacy-trades.enabled` defaults to `false`,
so trade-processor's NATS-driven booking path never actually runs against the deployed
configuration. **The real, live writer of MariaDB `TRADES` is order-matcher's own
`ProjectorHandler`** (direct `INSERT IGNORE INTO trades` off the output ring), which was already
using `tradeIdFor` correctly and was already idempotent via `INSERT IGNORE`. The real live-system
gap was that `ProjectorHandler.toTrade()` set `TradeState.Settled` immediately, with no settlement
lifecycle at all — confirmed by two existing integration tests that asserted exactly that. That is
the fix that actually matters in production; see `research.md` for the full account. The
trade-processor-side fixes remain correct, tested improvements to the legacy path, kept in place
rather than reverted.

## What's implemented

- **Settlement**: a booked trade starts `Processing` with `settlementDate = created +
  settlement.t-plus-days` (default T+1, business days); a scheduled sweep advances due trades to
  `Settled`; `POST /trades/{id}/settlement/force` is the manual override.
- **Reconciliation**: order-matcher's replay-safe `TradeBlotter` (rebuilt from journal replay,
  bounded to `recon.blotter.capacity`) feeds a scheduled trade-processor sweep classifying each
  entry `MATCHED` / `MISSING_IN_PROJECTION` / `FIELD_MISMATCH` against the local MariaDB row.
- **Full-history orphan detection (FR-PTC10)**: `POST /recon/full-history/reindex` triggers an
  on-demand, admin-gated full journal replay (reusing the existing shadow-engine pattern from
  `verifyJournalReplay`) into an unbounded index; `POST /recon/orphan-sweep` (trade-processor)
  cross-checks every local trade id against it, flagging `ORPHAN_IN_PROJECTION` rows with full
  confidence — not just the bounded forward window the live sweep covers.
- **Regulatory reporting (ADR-023)**: `GET /regulatory/report?fromSeq=&toSeq=` replays the journal
  (same shadow-engine skeleton) and returns every order/trade lifecycle event (accept, reject,
  partial-fill, fill, cancel, trade-booked) in that input-sequence range, reproducible byte-for-
  byte — sourced from the journal, never from the MariaDB projection.
- **TCA (ADR-024)**: `GET /tca/report/{tradeId}` (trade-processor) computes arrival price, TWAP
  benchmark, and signed slippage-in-bps for a trade, fed by a `PriceHistoryStore` that subscribes
  to price-publisher's existing `pricing.*` NATS feed — no new data source, no BLP hot-path
  involvement. VWAP is deferred (FR-PTC32): the synthetic feed carries no per-tick volume to
  weight by; the real TAQ dataset would supply it without changing the computation's contract.
- **Real auth/entitlements (ADR-025)**: every endpoint above requires a real HS256-verified JWT
  (`JwtAuthenticator`, JDK crypto only, no live OIDC provider in this environment) instead of the
  shared-token stopgap the first draft of this state used. Account-scoped endpoints (settlement
  force, TCA) check the caller's entitlement against the trade's own account; cross-account
  endpoints (blotter, full-history, orphan-sweep, regulatory report) require an `admin` claim.
  `POST /auth/dev-token` (trade-processor) mints tokens for local dev/testing.
- **Observability**: `traderx_recon_matched_total`/`missing_in_projection_total`/
  `field_mismatch_total`/`cursor`/`orphan_total`/`traderx_settlement_swept_total` are real
  Micrometer gauges (trade-processor `/actuator/prometheus`); `traderx-post-trade-compliance.json`
  Grafana dashboard visualizes them plus TCA/regulatory-report request rates.

## Out of scope (specified, deferred)

Feeding the risk gateway's `principalKey` path (FR-PTC42) — needs wiring into order *submission*
(`OrderMatcherService`/`GatewayReplicaStore`), a hot-path-adjacent surface this state deliberately
never touched. VWAP (FR-PTC32). See `generation/implementation-status.md`.
