# ADR-062: A swap booking is a sequenced consensus command that never reaches the engine

**Status**: Accepted
**State**: YU17-otc-rates
**Date**: 2026-08-13

## Context

Swaps trade OTC, bilaterally, by RFQ. There is no central limit order book that corresponds to
anything real, and a booking has no counterparty order to cross. The hosted `MatchingEngine` is a
crossing price-time-priority book over integer ticks: handed a swap booking it has no book to rest
it in, no price grid to rest it on, and no position grain that can hold it (see ADR-064).

That makes the shortcut obvious: book swaps straight into `trade-processor` and the database, since
nothing about them needs consensus to decide an outcome.

## Decision

Swap bookings are **sequenced through the consensus log** as `TYPE_SWAP_BOOK` (12) on the existing
`InputEventMessage` (SBE template 1), and **applied in `MatchingEngineClusteredService`, never
handed to `MatchingEngine`.**

The economics ride the record's existing slots. Five of the six values land in a slot whose current
meaning already fits — `accountId`, `side` (the fixed leg's direction), `qty` (the notional),
`limitPx` (the fixed rate, already a 1e6 fixed-point long), `priceTicks` (the `clientOrderKey`) —
and `securityId` carries an index into a compile-time convention table rather than a symbol id,
because a swap gets no symbol-table entry. Only the two dates are packed, into `orderRef`, which the
sequenced generator touches for `TYPE_ORDER_NEW` alone.

## Consequences

**Why sequencing, when nothing matches.** The EOD extract's own preamble states that every row is
*the replicated state machine's state at `consensusSequence` on the totally-ordered consensus log,
not a read-model query*. Booking swaps outside the log makes that sentence false for the file that
carries it, and what is given up is concrete: deterministic replay of a booking, byte-identical
rendering across all three members, the quiescence witness that proves nothing was sequenced during
the build, and reproducibility of the artifact from the stored cut alone.

**Why not the engine.** A swap creates no order, rests in no book, crosses nothing, books no trade
and creates no position. Dispatching before the engine's apply makes "a swap changes nothing for the
instruments that already work" a structural fact rather than a claim, and leaves the order hot path
— and therefore the allocation gates and the Epsilon-GC proofs — byte-for-byte what they were.

**Why no new SBE template.** `AeronReplicationCodec` copies `commandType` through without
interpreting it, which is what let YU13 add atomic replace at no schema cost. Template ids 1-8 are
allocated across the lineage — 8 is YU15's `RiskExtractMessage`, invisible from a YU13 worktree — so
claiming one from a branch that cannot see the whole lineage is a hazard worth not taking when the
existing record suffices.

**Why the idempotency key keeps its slot.** A swap booking is a bilateral confirmation. A retried
one that creates a second 10mm contract is exactly the failure `clientOrderKey` exists to prevent,
and the table's key is 64 bits because a collision answers a distinct request with another request's
outcome. Spending that slot on the dates and packing the key instead would have traded a real safety
property for a cosmetic one.

**The date range is a real limit.** Two 16-bit unsigned epoch days reach 2149-06-06. The gateway
refuses anything outside that range before sequencing, because `setSwapDates` masks: an unrefused
out-of-range date would wrap into a plausible one rather than fail.

**Conventions by index carries an obligation.** An index that has been journaled keeps its meaning
permanently — appending is safe, reordering silently rewrites the terms of contracts already booked.
A build meeting an index it does not know aborts the render naming the index, rather than resolving
to another convention: publishing a contract under the wrong day count is worse than publishing
nothing, and the remedy (roll forward) is the one the snapshot header already prescribes.

**Rolling it.** This is a change to the deterministic apply path, so it cannot be rolled gradually:
a mixed-version window diverges the members permanently, and the un-snapshotted log tail is itself
such a window. Take a snapshot barrier on all three members before rolling
(`.claude/skills/prove-cluster-engine-change`). The snapshot format moves 4 → 5 for the new record
type while `MIN_READABLE_SNAPSHOT_FORMAT` stays at 3, so the roll forward needs no epoch wipe.

## Alternatives considered

- **Book into the read model directly.** Simplest, and it retires the extract's strongest claim
  silently. Rejected.
- **A new SBE template for swaps.** Legitimate, and unnecessary: the existing record holds the
  payload once the conventions are recognised as an enum rather than per-trade economics. It would
  also mean claiming a template id from a branch that cannot see every sibling's allocations.
- **A symbol-table entry per swap.** `MAX_SECURITIES` is 1024 and ids are never evicted, so a day's
  trading exhausts the table — and every subsequent snapshot then carries books that hold no resting
  orders, because a swap never rests.
