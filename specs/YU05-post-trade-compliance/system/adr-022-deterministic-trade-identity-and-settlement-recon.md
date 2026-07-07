# ADR-022: Deterministic Trade Identity as the Foundation for Settlement + Reconciliation

**Status:** Accepted for specification (implemented in YU05 slice 1)
**Date:** 2026-07-06
**State:** `YU05-post-trade-compliance` (parent `YU03-in-memory-risk-gateway`)

## Context

Real settlement tracking and reconciliation both require a stable way to say "this MariaDB trade
row is (or is not) the same event as this journaled fill." Investigating the existing codebase
before designing either capability found that this linkage does not exist today:

- The BLP already assigns a deterministic per-fill trade number (`tradeCounter`, snapshot-persisted,
  pure function of replay order), and a helper (`OrderSnapshot.tradeIdFor`) already derives a
  stable string id from it, with a doc comment stating it should be shared end-to-end.
- The NATS payload sent to trade-processor (`TradeOrder.fromEvent`) instead used the *order's* id.
- trade-processor then discarded even that and minted a fresh `UUID.randomUUID()` per delivery.

The result: MariaDB trade rows carry ids with no derivable relationship to the journal, so no
reconciliation or settlement-lifecycle tracking keyed on trade identity was possible, and every
NATS redelivery would silently double-book a trade and double-move a position (no idempotency key
existed on this path at all).

## Decision

Wire up the trade id that already existed but was unused, end-to-end:

1. `TradeOrder.fromEvent()` sets `id = OrderSnapshot.tradeIdFor(e.tradeSeq)`.
2. `TradeService.processTrade()` uses that id verbatim as `TRADES.ID` and checks for its existence
   before inserting/moving a position — making booking idempotent for free, as a side effect of
   the id becoming deterministic rather than random.
3. Build the trade blotter (order-matcher, in-memory, replay-rebuilt) and reconciliation sweep
   (trade-processor, scheduled) on top of this now-stable id, plus a settlement state machine
   using the same id as its tracking key.

## Alternatives Considered

- **Keep minting a random UUID in trade-processor, add a separate correlation table mapping
  UUID ↔ tradeSeq:** rejected — adds a whole new table and a join on every recon/settlement query
  to solve a problem the existing (unused) `tradeIdFor` helper already solves for free.
- **Derive reconciliation identity from (accountId, security, quantity, price, timestamp) tuple
  matching instead of an explicit id:** rejected — ambiguous under two identical-looking fills to
  the same account/security in the same millisecond (a real, if rare, scenario), and much harder to
  make idempotent against redelivery.
- **Leave trade-processor's booking non-idempotent and rely on NATS delivery guarantees to never
  redeliver:** rejected — the existing `NatsJSONSubscriber` base class provides no such guarantee,
  and idempotent booking is nearly free once the id is deterministic anyway.

## Consequences

Positive: settlement and reconciliation both have a real, stable key to work with; NATS redelivery
becomes safe (a correctness bug fixed as a side effect, not the primary goal); regulatory
reporting and TCA (later commits of this state) inherit the same stable id for free.

Costs: `TradeOrder.id`'s semantics changed (order id → trade id) — no identified consumer depended
on the old value's format, but any future consumer must not assume the `ord-013-` prefix.

## Status in YU05 slice 1

- **Implemented.** `TradeBlotterHandler` captures every `KIND_TRADE_BOOKED` event (including during
  recovery replay, since the output-ring handler chain runs during recovery — see research.md) into
  a bounded, in-memory store keyed by the same deterministic id. `SettlementService` and
  `ReconciliationService` (trade-processor) both key off it.
- **Full-history orphan detection deferred** (FR-PTC10) — the blotter is bounded, so proving a
  MariaDB row has no corresponding fill *anywhere* in a long-lived journal needs either unbounded
  blotter retention or a direct journal-replay comparator, neither built in slice 1.

## Validation

- Idempotent booking: a duplicate `TradeOrder` delivery must not create a second row or move the
  position twice — covered by `TradeServiceIdempotencyTest`.
- Blotter replay-safety: trades booked before a restart must appear in the blotter again after
  recovery, with the same id/fields — covered by `TradeBlotterTest`.
- Recon classification correctness (MATCHED/MISSING_IN_PROJECTION/FIELD_MISMATCH) against seeded
  blotter/DB fixtures — covered by `ReconciliationServiceTest`.
