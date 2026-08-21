# ADR-065: A swaption is a contract record, and its exercise style is a term

**Status**: Accepted
**State**: YU17-otc-rates (phase 2)
**Date**: 2026-08-14

## Context

A swaption is an option on a swap: the holder has the right, at expiry, to enter a swap on
pre-agreed terms. Its economics are the underlying swap's — notional, fixed rate, direction, both
dates, conventions — plus three things the swap does not have: an expiry, an exercise style, and
the fact that it is an option at all.

Two questions had to be answered before writing any of it.

**Is exercise modelled?** No. D6 says this state carries no contract lifecycle, and exercising is
the swaption's lifecycle event. Nothing here exercises anything, nothing expires, and a swaption
past its expiry date is listed exactly as booked.

**Is exercise STYLE modelled?** Yes, and the distinction is the whole ADR. A style is not an event;
it is a term of the contract, agreed at booking and unchanged for its life. A European swaption can
be exercised on one date and a Bermudan on a schedule of them, so the Bermudan is worth materially
more. Two swaptions identical in every other respect are different instruments if their styles
differ — and a consumer valuing them from a file that omitted the style would price one of them
wrongly, with nothing anywhere indicating a problem. That is precisely the failure mode this whole
state exists to prevent, so the style is published and the exercise is not.

## Decision

**A swaption is a contract record like any other**, in the same store, the same cut section and the
same artifact as a swap.

- `TYPE_SWAPTION_BOOK` (13) — a separate command type, not a flag on `TYPE_SWAP_BOOK`.
- Every slot the swap command uses keeps its exact meaning, because the underlying IS a swap:
  `fixedRate` is the strike, `side` is the direction of the underlying's fixed leg (`Pay` = a payer
  swaption), `qty` is the underlying notional.
- The option wrapper rides `securityId` as one word: convention index in the low byte, exercise
  style in the next, expiry epoch-day in the high half.
- The contract record grows from 8 to 11 columns: `productType`, `expiryEpochDay`, `exerciseStyle`.
  A swap carries zeros there.
- `SNAPSHOT_FORMAT` 5 → 6. `MIN_READABLE_SNAPSHOT_FORMAT` stays 3.
- The contracts artifact goes to schema 2, gaining `productType`, `expiryDate` and `exerciseStyle`.
  A swap row carries `SWAP` and leaves the two option columns empty.
- Ids are `SWPT-<consensusSequence>` against the swap's `SW-<consensusSequence>`.

## Consequences

**The product is the command, never a field's value.** Deriving "this is a swaption" from a non-zero
expiry would make epoch day 0 — 1970-01-01 — a load-bearing sentinel, and a booking that failed to
set the field would silently become a swap. A distinct command type costs one constant and removes
the question.

**One artifact, and that is the MIRROR of ADR-064 rather than an exception to it.** ADR-064 split
swaps out of the netted position extract because a swap row shares only `accountId` with a position
row — the empty-columns convention could not stretch that far. A swaption row shares almost EVERY
column with a swap row, because those columns describe the underlying, and differs in three. That is
exactly the case the convention exists for, and it is the same judgement applied to a different
shape, not a reversal of it.

**Reading a format-5 snapshot needs the format, because a record cannot describe itself.**
`onSnapshotRecord` receives a buffer and an offset and no length, so nothing in a `T_CONTRACT`
record says how wide it is. Reading a format-5 record at the new width would take the following
record's bytes as an expiry and an exercise style — a silently wrong contract rather than a failure.
The reader therefore remembers the format from the header (which is guaranteed to arrive first) and
reads eight columns or eleven accordingly.

This was verified live rather than argued: a swap was booked on the phase-1 build, a snapshot
barrier taken so a format-5 `T_CONTRACT` existed on disk, and the members rolled forward. The
contract came back with its terms intact and `productType` `SWAP`.

**The risk gate is unchanged.** A swaption is admitted through the same `decideSwapBooking`, which
measures the underlying notional. That is conservative — the premium is far smaller than the
notional, and the notional is the exposure if it is exercised — and it keeps one account exposure
number meaningful across every product. Modelling the premium would require valuing the option,
which is the consumer's half of the boundary (D5).

**`expiryDate` must not be after the underlying's `effectiveDate`.** An option that expires after
the swap it is an option on has nothing to be exercised into. Refused at the boundary rather than
published as a term nobody could act on.

**An unknown exercise style aborts the render**, exactly as an unknown convention index does: a
Bermudan published as European is a different instrument, and refusing to publish beats publishing
something false. The remedy is the same — roll forward.

## Alternatives considered

- **A third artifact for swaptions.** Rejected: it would split rows that share almost every column,
  and force a consumer wanting all OTC exposure to read and join two files.
- **A `product` field on `POST /swaps`.** Rejected for the same reason as the command-type decision:
  a client posting a swap body should never receive an option by accident of which fields it
  happened to include.
- **Modelling exercise.** Out of scope by D6, and it would be the first thing in this state to make
  a contract's terms change after booking.
