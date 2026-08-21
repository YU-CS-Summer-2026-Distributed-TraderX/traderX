# Feature Specification: OTC Interest-Rate Swaps

**Feature Branch**: `YU17-otc-rates`
**Created**: 2026-08-13
**Status**: In implementation
**Input**: OTC fixed-float interest-rate swaps and swaptions on the cluster tier, parented on `YU16-cdm-instruments`

## User Stories

- As a rates trader, I want a booked swap to be a contract with its own terms rather than a
  quantity in a position, because two swaps I have deliberately put on against each other are two
  obligations, and a system that reports them as one flat line is reporting something that is not
  true.
- As a risk-engine consumer, I want the swap terms — direction, notional, fixed rate, both dates,
  float index, frequency, day count, currency — as of the same consensus sequence as the netted
  positions, so I value one portfolio taken at one instant rather than two portfolios taken at two.
- As a risk-engine consumer, I want no valuation in that file, because the NPV is my engine's
  answer and a second, differently-derived number from the venue is a reconciliation break I would
  have to explain rather than a fact I could use.
- As the platform owner, I want swap bookings to go through the replicated log even though they
  never match, because the extract's own header claims every row is the state machine's state at a
  consensus sequence and not a read-model query, and booking swaps around the log would quietly
  make that sentence false.
- As the platform owner, I want the risk gate to know that a swap's notional is its notional,
  because `quantity × price × multiplier` values a 10mm swap at 420,000 — an understatement that
  passes every check and appears nowhere in any log.
- As an operator, I want a `YU16-cdm-instruments` epoch to roll forward onto this build without a
  PVC wipe, because a snapshot format bump that also demands a fresh epoch turns a routine roll
  into an outage.
- As a maintainer, I want the netted position extract untouched, because netting is correct for
  equities, ETFs, Treasuries and listed options, and giving it up to accommodate the one class it
  cannot serve would be a worse file for every consumer.
- As a rates trader, I want a swaption's exercise style on its row, because a European and a
  Bermudan on identical underlying terms are different instruments and I hold both.
- As a risk-engine consumer, I want swaptions in the same file as swaps, because they are the same
  desk's exposure and a row that shares every column but three should not cost me a second file and
  a join.

## Functional Requirements

### The booking command

- FR-OTC01: The gateway SHALL expose `POST /swaps` accepting `accountId`, `payReceive`,
  `notional`, `fixedRate`, `effectiveDate`, `maturityDate`, `conventions` and an optional
  `clientOrderId`; a missing required field SHALL return 400.
- FR-OTC02: A swap booking SHALL be sequenced through the consensus log as `TYPE_SWAP_BOOK` on the
  existing `InputEventMessage` (SBE template 1); no new template and no schema version change.
- FR-OTC03: `TYPE_SWAP_BOOK` SHALL carry its economics in the record's existing slots: `accountId`
  the booking account, `side` the fixed-leg direction, `qty` the notional in whole currency units,
  `limitPx` the fixed rate in 1e6 ticks, `priceTicks` the `clientOrderKey`, `securityId` a
  convention-table index, and `orderRef` the two epoch-day dates packed 16 bits each.
- FR-OTC04: The gateway SHALL reject before sequencing any term the record cannot represent: a
  notional outside `1..2147483647`, a date outside `1970-01-01..2149-06-06`, a maturity at or
  before the effective date, a zero fixed rate, an unrecognised `payReceive`, a non-ISO date, and
  an unknown conventions name. Each SHALL return 400 and SHALL NOT advance the consensus sequence.
- FR-OTC05: A booking carrying a `clientOrderId` SHALL be idempotent: a repeat of the same key
  SHALL answer with the original contract id and SHALL NOT create a second contract.
- FR-OTC06: The booking SHALL NOT reach the matching engine. It creates no order, rests in no
  book, crosses nothing, books no trade and creates no position.

### The contract store

- FR-OTC07: An accepted booking SHALL create a contract in replicated state holding
  `{contractId, accountId, payFixed, notional, fixedRateTicks, conventionIndex,
  effectiveEpochDay, maturityEpochDay, productType, expiryEpochDay, exerciseStyle}`. The first
  eight columns mean the same thing for both products; the last three are the option wrapper.
- FR-OTC08: `contractId` SHALL be the consensus sequence the booking landed at, rendered
  externally as `SW-<sequence>` for a swap and `SWPT-<sequence>` for a swaption; it is unique within
  the cluster epoch by construction and derivable from the log alone.
- FR-OTC09: The store SHALL hold at most `MAX_CONTRACTS` (4096) contracts and SHALL refuse a
  booking at capacity with `RiskReason.CAPACITY`, deterministically and identically on every
  member. Capacity SHALL be checked before the risk gate, so a refused booking consumes no credit.
- FR-OTC10: Contracts SHALL be held in booking order, which is ascending `contractId`, and SHALL
  be written to and restored from the snapshot in that same order.
- FR-OTC11: Nothing SHALL remove or modify a contract. This state models no resets, no coupon
  payments, no accrual, no amortisation and no termination.

### Market conventions

- FR-OTC12: Float index, payment frequency, day count and currency SHALL be resolved from a
  compile-time table addressed by the committed convention index, stored nowhere in replicated
  state and therefore identical on every member, on replay and on restore.
- FR-OTC13: A convention index SHALL keep its meaning permanently once journaled; the table is
  appended to, never reordered and never reused.
- FR-OTC14: A rendered contract naming a convention index this build does not know SHALL abort the
  render with a message naming the index, rather than resolve to any other convention.

### The risk gate

- FR-OTC15: A swap booking SHALL be admitted through `BlpRiskState.decideSwapBooking`, which
  checks — in this order — duplicate suppression, kill switch, account existence, account
  enablement, entitlement, a positive notional, the per-booking notional cap, and credit.
- FR-OTC16: The notional measured SHALL be the contract's notional in Px ticks. `quantity × price
  × multiplier` SHALL NOT be applied to a swap.
- FR-OTC17: The swap path SHALL NOT apply the security-enabled, security-restricted, price-present,
  price-freshness, position-limit or concentration-limit checks; a swap has no symbol-table entry,
  no last trade and no `(account, security)` quantity for those checks to read.
- FR-OTC18: An accepted booking's notional SHALL accrue immediately to the account's executed
  exposure; a swap is not a resting order and holds no releasable reservation.

### Snapshot and recovery

- FR-OTC19: `SNAPSHOT_FORMAT` SHALL be 6 and the contract store SHALL be persisted as
  `T_CONTRACT` records.
- FR-OTC20: `MIN_READABLE_SNAPSHOT_FORMAT` SHALL remain 3, so a format-3, format-4 or format-5
  snapshot restores on this build and an existing epoch rolls forward without a wipe.
- FR-OTC21: Snapshot restore SHALL fail closed on a contract id that is not a sequence at or below
  the restored applied sequence, and on contract records that are not in ascending id order.
- FR-OTC22: A member restored from a snapshot SHALL render a cut byte-identical to the member that
  never restarted, contracts included.

### The cut and the two artifacts

- FR-OTC23: The rendered cut SHALL be at schema 3 and SHALL carry a `#contracts` section after the
  position rows, introduced by a marker line and its own column header, with the contract count
  declared in the cut header as `contracts=`.
- FR-OTC24: The `#contracts` section SHALL be emitted even when the store is empty, so an absent
  section means an older producer and never an empty portfolio.
- FR-OTC25: The netted position extract SHALL remain at CSV schema 3 with every column unchanged,
  SHALL stop reading at the section marker, and SHALL never carry a swap row.
- FR-OTC26: A second artifact SHALL be rendered from the same cut under the same stamp, carrying
  one row per contract with `contractId, accountId, payReceive, notional, fixedRate, floatIndex,
  effectiveDate, maturityDate, paymentFrequency, dayCount, currency, counterpartyId, nettingSetId,
  productType, expiryDate, exerciseStyle`.
- FR-OTC27: The contracts artifact SHALL contain no valuation of any kind — no NPV, no mark, no
  discount factor, no curve, no par rate, no sensitivity — and SHALL say so in its preamble.
- FR-OTC28: Both artifacts SHALL be written write-once under the same session date, price version
  and consensus sequence, alongside the single stored cut both are rebuilt from.
- FR-OTC29: `risk.extract.ready` SHALL announce both artifacts, sharing one `consensusSequence`,
  one `sessionDate` and one `cutSha256`.
- FR-OTC30: `RiskExtractMain --rebuild <cut> <positions.csv> <contracts.csv>` SHALL reproduce both
  files byte-identically from the stored cut and immutable reference data alone.
- FR-OTC31: A cut declaring a contract count that disagrees with the rows it carries SHALL abort
  the render.

### Swaptions

- FR-OTC32: The gateway SHALL expose `POST /swaptions`, accepting the swap body plus `expiryDate`
  and `exerciseStyle`; every other field describes the UNDERLYING swap, so `fixedRate` is the strike
  and `payReceive` is the direction of the underlying's fixed leg.
- FR-OTC33: A swaption booking SHALL be sequenced as `TYPE_SWAPTION_BOOK` on the existing SBE
  template 1. The product SHALL be the command type, never the presence or value of a field.
- FR-OTC34: `TYPE_SWAPTION_BOOK` SHALL carry the option wrapper in `securityId`: convention index in
  bits 0-7, exercise style in bits 8-15, expiry epoch-day in bits 16-31. Every other slot SHALL keep
  the meaning it has for `TYPE_SWAP_BOOK`.
- FR-OTC35: Exercise style SHALL be resolved from a compile-time, append-only table
  (`EUROPEAN`, `BERMUDAN`, `AMERICAN`), and a style index this build does not know SHALL abort the
  render rather than resolve to another style.
- FR-OTC36: The gateway SHALL refuse before sequencing an unknown `exerciseStyle`, an `expiryDate`
  outside the representable range, and an `expiryDate` after the underlying's `effectiveDate`.
- FR-OTC37: A contract record SHALL carry `productType`, `expiryEpochDay` and `exerciseStyle`; a
  swap SHALL carry zeros in all three.
- FR-OTC38: `SNAPSHOT_FORMAT` SHALL be 6. Snapshot restore SHALL read a `T_CONTRACT` record at the
  width its format declares — eight columns for format 5, eleven for format 6 — because the record
  carries no length and reading a format-5 record at the new width would consume the next record's
  bytes.
- FR-OTC39: A format-5 contract SHALL restore as a SWAP with an empty option wrapper.
- FR-OTC40: The contracts artifact SHALL be schema 2, carrying `productType`, `expiryDate` and
  `exerciseStyle`; a SWAP row SHALL carry `SWAP` and leave the two option columns empty.
- FR-OTC41: A swaption's contract id SHALL be `SWPT-<consensusSequence>`.
- FR-OTC42: A swaption SHALL be admitted through the same risk path as a swap, measuring the
  UNDERLYING notional.
- FR-OTC43: No exercise SHALL be modelled. A swaption past its expiry date is listed exactly as
  booked.

## Non-Functional Requirements

- NFR-OTC01: The order hot path SHALL be unchanged. A swap booking is dispatched before the
  engine's apply and the engine's own code is untouched, so the allocation gates and Epsilon-GC
  proofs measure exactly what they measured on `YU16-cdm-instruments`.
- NFR-OTC02: Swap booking is a cold path and MAY allocate; it SHALL NOT share code with the order
  apply loop.
- NFR-OTC03: The rendered cut SHALL be a pure function of replicated state — no clock, no map
  iteration order, no locale-sensitive formatting, no floating point — so every member and every
  replay renders identical bytes.
- NFR-OTC04: A swap booking SHALL be observable per member as a movement of the applied sequence
  and, at extract time, as the `contracts=` count on the `RISK-EXTRACT-CUT` line.
- NFR-OTC05: Rolling this build onto an existing epoch SHALL NOT require a PVC wipe. Rolling BACK
  from it does, because a format-5 snapshot is not readable by an older build; the header message
  says so in the direction of the mismatch.
- NFR-OTC06: The state SHALL add no NATS subject, remove none and rename none.
- NFR-OTC07: A full contract store adds roughly 272KB to a snapshot, an order of magnitude inside
  the budget the idempotency table already sets, so snapshot duration — an apply-thread freeze —
  is not materially changed.

## Technical Debt Register

- TD-OTC01: `contractId` is unique within a cluster epoch, not across epochs. A wiped epoch
  restarts consensus sequences, so a re-run against the same session date meets the extract sink's
  write-once refusal rather than silently mixing two epochs' contracts. This is the same posture
  the epoch-unaware trade ids already have, and the refusal is loud.
- TD-OTC02: A swap booking is admitted against a single per-booking notional cap and the account's
  credit line. There is no tenor-weighted, DV01-weighted or currency-aware limit, because those
  are valuation-shaped and valuation is the consumer's half of the boundary (ADR-063).

## Success Criteria

- SC-OTC01: `bash pipeline/generate-state.sh YU17-otc-rates` exits 0.
- SC-OTC02: A swap booked through `POST /swaps` moves every member's applied sequence by exactly
  one and returns the contract id `SW-<that sequence>`.
- SC-OTC03: The receiver-4.2% / payer-4.3% pair on one account at one notional appears in the
  contracts artifact as TWO rows carrying both rates, at a sequence where the netted artifact
  carries no row for either.
- SC-OTC04: All three members log the identical `RISK-EXTRACT-CUT` sha256 and `contracts=` count
  for the extract sequence.
- SC-OTC05: A member deleted to an empty disk rejoins, replays and re-renders the identical cut.
- SC-OTC06: Both artifacts rebuild byte-identically from the stored cut alone.
- SC-OTC07: A format-4 snapshot restores on this build; a format-5 snapshot handed to an older
  build is refused with a message naming the direction of the mismatch.
- SC-OTC08: A booking on an unknown or disabled account returns 422 with the reason and creates no
  contract; a booking with an unrepresentable term returns 400 and does not advance the sequence.
- SC-OTC09: Every inherited proof passes unchanged.
- SC-OTC10: A European and a Bermudan payer swaption identical in every other term appear as two
  rows differing in exactly one column, in the same artifact as a plain swap.
- SC-OTC11: A contract booked on a format-5 build restores on a format-6 build with its terms
  intact and `productType` `SWAP`.
