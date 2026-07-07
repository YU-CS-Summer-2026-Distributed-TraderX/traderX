# Feature Specification: Post-Trade Compliance Bundle (state YU05)

**State id**: `YU05-post-trade-compliance`
**Parent state**: `YU03-in-memory-risk-gateway`
**Created**: 2026-07-06
**Status**: Slice 1 (settlement + reconciliation) implemented (see
`generation/implementation-status.md` for what is deferred)
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
*optional* future input to TCA's benchmark computation (arrival price/VWAP/TWAP) — it does not
drive scope here and slice 1 does not depend on it being available.

## Requirements

New requirement namespace `PTC` (`FR-PTCxx`, `NFR-PTCxx`), grouped by sub-capability:

- **Settlement + reconciliation** (`FR-PTC01`–`FR-PTC10`): slice 1, implemented this commit.
- **Regulatory reporting** (`FR-PTC20`–`FR-PTC22`): specified, deferred.
- **TCA** (`FR-PTC30`–`FR-PTC32`): specified, deferred.
- **Real auth + entitlements** (`FR-PTC40`–`FR-PTC42`): specified, deferred; closes the
  `principalKey`/entitlement gap YU03 deliberately left open (FR-IMRG02 partial, FR-IMRG30
  partial).

Full per-requirement status: `requirements/functional-delta.md` / `requirements/nonfunctional-delta.md`.

## Slice 1 scope: settlement + reconciliation

This state's first commit closes a concrete, verified bug that blocks *all four* sub-capabilities
equally: **trade identity is not actually stable end-to-end today.**

- The BLP already assigns a deterministic, replay-safe global trade number (`tradeCounter`,
  persisted in snapshot v3) for every booked fill, and `OrderSnapshot.tradeIdFor(tradeSeq)`
  already exists as the intended stable id — its doc comment says it should be "shared by the
  projector (DB row id) and the NATS bridge (published id) so both agree exactly."
- **It isn't wired up.** `TradeOrder.fromEvent()` (the NATS payload sent to trade-processor) uses
  `OrderSnapshot.orderIdFor(e.orderRef)` — the *order's* id, not the trade's — and
  `TradeService.processTrade()` in trade-processor then discards even that, minting a fresh
  `UUID.randomUUID()` for the MariaDB `TRADES.ID` row.
- Net effect: there is no way today to look at a MariaDB trade row and know which journaled fill
  it came from. Reconciliation, settlement tracking, regulatory reporting, and TCA all need that
  linkage — so fixing it is the correct, minimal first slice, not a detour.

Slice 1 fixes the wiring, makes trade-processor idempotent on the now-stable id (safe against NATS
redelivery), adds a settlement state machine (`New → Processing → Settled`, T+N business days,
scheduled + manual override), and adds a forward-looking reconciliation comparator between
order-matcher's replay-safe in-memory trade blotter and the MariaDB projection.

## Slice-1 behavioral contract

- Every `KIND_TRADE_BOOKED` output event (limit-order fill or market-trade execution) carries the
  same deterministic id, `trd-09b-<tradeSeq>`, in both the NATS `TradeOrder` payload and the
  in-process trade blotter — no code path mints a random id for a trade anymore.
- `trade-processor` books each `TradeOrder` exactly once per id: a duplicate id (NATS redelivery
  after an ack timeout, or an operator replaying a subject) updates nothing and re-emits no
  position change — checked by primary-key existence before insert, inside the same transaction
  that would otherwise double-book.
- A booked trade starts `New`, transitions to `Processing` immediately (booking is synchronous
  with fill), and to `Settled` when its settlement date (`created + settlement.t-plus-days`,
  default 1 business day) has passed — advanced by a scheduled sweep, or forced early via an
  authenticated operator endpoint (`POST /trades/{id}/settlement/force`).
- order-matcher's trade blotter is rebuilt during snapshot+journal replay (the output-ring handler
  chain runs during recovery; this handler, unlike the NATS/DB bridges, does not suppress on
  replay) and stays live-updated afterward, bounded to the most recent
  `recon.blotter.capacity` (default 500,000) trades.
- A reconciliation sweep (trade-processor, scheduled) walks the blotter forward from its last
  cursor and classifies each entry against the MariaDB row of the same id: `MATCHED`,
  `MISSING_IN_PROJECTION` (blotter has it, DB doesn't), or `FIELD_MISMATCH` (account/security/
  side/quantity/price differ). Results are exposed via `GET /recon/status` and bounded-cardinality
  Prometheus counters — no per-trade labels.
- **Known, documented limitation of slice 1**: because the blotter is bounded and only reliably
  covers trades since the last capacity-bounded eviction or process start, slice 1 cannot detect
  `ORPHAN_IN_PROJECTION` (a DB row with no corresponding journal fill) with full historical
  confidence — that direction needs the blotter (or an equivalent journal replay) to cover the
  *entire* trade history, deferred to a later commit (`FR-PTC10`).

## Out of scope for slice 1 (specified, deferred to later commits of this state)

Regulatory reporting generation, TCA benchmark computation, real OIDC/entitlements, full-history
orphan detection. See `generation/implementation-status.md`.
