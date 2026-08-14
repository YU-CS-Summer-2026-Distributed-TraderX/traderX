# Non-Functional Delta: YU17 over YU16-cdm-instruments

Every inherited non-functional requirement is retained.

## Determinism and the core

- NFR-OTC01: The order hot path SHALL be unchanged. `TYPE_SWAP_BOOK` is dispatched before the
  engine's apply and `MatchingEngine` is byte-identical to the YU16 layer, so the allocation gates
  and the Epsilon-GC proofs measure exactly what they measured there.
- NFR-OTC02: Swap booking is a cold path — a handful of bookings a day — and MAY allocate. It SHALL
  NOT share code with the order apply loop.
- NFR-OTC03: The rendered cut SHALL be a pure function of replicated state: no clock, no map
  iteration order, no locale-sensitive formatting, no floating point. Contract rows are in booking
  order, which is ascending `contractId`, asserted at render rather than assumed.
- NFR-OTC10: Convention resolution SHALL be a pure function of a committed index against a
  compile-time table, so it is identical on every member, on replay and on restore, and costs no
  snapshot field.

## Rollability

- NFR-OTC05: Rolling this build onto an existing `YU16-cdm-instruments` epoch SHALL NOT require a
  PVC wipe: `MIN_READABLE_SNAPSHOT_FORMAT` stays at 3, so the format-4 snapshot on disk restores
  here untouched. Rolling BACK does require an epoch change, because a format-5 snapshot is
  unreadable by the older build, which refuses it at the header naming the direction.
- NFR-OTC11: Because the apply path changed, the roll SHALL follow the deterministic-core
  discipline: a snapshot barrier on all three members first, then the roll, with no traffic
  exercising the changed path in flight. A mixed-version window diverges the members permanently,
  and the un-snapshotted log tail is itself such a window.

## Capacity and cost

- NFR-OTC07: A full contract store (4096 contracts × 68 bytes) adds roughly 272KB to a snapshot —
  an order of magnitude inside the budget the 256Ki idempotency table already sets — so snapshot
  duration, which is an apply-thread freeze, is not materially changed.
- NFR-OTC12: The contract store SHALL be bounded. Nothing removes a contract in this state, so an
  unbounded store would be an unbounded snapshot; capacity is refused deterministically rather than
  grown.
- NFR-OTC13: A swap SHALL consume no symbol-table capacity. The 1024-entry table is sized for a
  universe of instruments that pre-exist their trades.

## Observability

- NFR-OTC04: A swap booking SHALL be observable per member as a movement of the applied sequence,
  and at extract time as `contracts=<C>` on the `RISK-EXTRACT-CUT` line, so cross-member agreement
  on the contract store needs nothing but the pod logs.
- NFR-OTC14: A refusal SHALL be distinguishable by cause at the boundary: 400 means the term could
  not be represented and nothing was sequenced; 422 means the booking was sequenced and the risk
  gate decided against it; 504 means no decision committed and the outcome is ambiguous, not a
  rejection.

## Contracts and compatibility

- NFR-OTC06: The state SHALL add no NATS subject, remove none and rename none.
- NFR-OTC08: The netted position extract SHALL keep CSV schema 3 with every column's name, position
  and meaning unchanged, so an existing consumer reads it without a code change.
- NFR-OTC09: Both artifacts SHALL be byte-reproducible from the stored cut and immutable reference
  data alone, forever — no clock, no database read beyond the immutable published price snapshot,
  no cluster required.
