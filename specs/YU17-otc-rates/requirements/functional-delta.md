# Functional Delta: YU17-otc-rates (vs YU16-cdm-instruments)

Everything inherited from YU16 and its ancestry is carried forward unchanged unless listed here.

## Added

### Market data, the trading session, and replay

- A **session phase machine** in consensus — `CLOSED`, `PRE_OPEN`, `OPEN` — sequenced and snapshotted.
  The phase moves by a control command, so every member agrees on it. An order arriving in
  `PRE_OPEN` is accepted and held in a replicated queue rather than entering a book; opening
  releases the queue in sequence, and a halt back to `CLOSED` cancels it, so a client is never left
  holding an order the venue has quietly dropped.
- A **price band that follows the market**, anchored on the reference rather than on a book's first order.
  The reference is the feed price, else the last trade, else the first limit — so a stray order can
  no longer decide a security's tradeable range for a whole epoch. The band re-centres lazily, only
  when a limit it refuses is one the reference says is admissible. Orders stranded by a re-anchor
  are cancelled through the existing unsolicited-cancel path carrying reason `PRICE_COLLAR`, and
  `bandReanchors` and `bandStrandedCancels` count both halves for the operator.
- A **price-derived book grid**: an empty book takes its tick size from the reference price, by decade.
  A fraction-of-par instrument and a several-hundred-dollar one are each quoted at a usable
  granularity, instead of sharing one venue-wide tick. The tick is stored in `T_BOOK`, so a
  restoring member reads the book's geometry instead of re-deriving it.
- An **external reference replayed on a stateless clock**, resampled offline from a licensed historical tape.
  Replay position is derived as `(now - epochStart) x compression` and never stored, so a publisher
  restart resumes at the right point with no coordination and no persisted cursor. Each tick carries
  a `source` and an `asOf` beside the existing `simulated` flag, because a real price at a fabricated
  time is neither live nor invented and a boolean cannot say which it is.
- **Replayed prints entering as order flow**, sampled to a target rate and matched as ordinary orders.
  They are submitted through dedicated replay accounts, so the engine matches, fills and moves
  positions on activity that genuinely occurred. The side is not present in the source data and is
  inferred by the tick rule, which is labelled as inferred rather than presented as fact. Members
  gain operator-scoped counters — the replayed halves of the order-ref generator and the trade
  counter — so a global reading minus its external half is an operator-only reading that a
  continuous replayed feed cannot move.
- `GET /bbo` on each member: best bid, offer and mark derived from the book and served beside consensus.
  It is not sequenced, because every member can compute it from the log it has already applied. A
  side holding no resting orders is omitted from the response rather than zero-filled, so an empty
  side cannot be misread as a price of zero.

### OTC swaps

- `POST /swaps` on the cluster gateway: books a vanilla fixed-float OTC interest-rate swap.
  It takes `accountId`, `payReceive`, `notional`, `fixedRate`, `effectiveDate`, `maturityDate`,
  `conventions` and an optional `clientOrderId`. Returns `{"contractId":"SW-<N>","sequence":N,
  "booked":true}`, 422 with a `RiskReason` when the gate refuses, 400 when a term cannot be
  represented, 504 when no decision committed.
- `TYPE_SWAP_BOOK` (12) on the inherited `InputEventMessage` (SBE template 1): a sequenced consensus command.
  It carries the swap's economics in the record's existing slots, is applied in
  `MatchingEngineClusteredService`, and is never handed to `MatchingEngine`.
- A replicated OTC contract store: `{contractId, accountId, payFixed, notional, fixedRateTicks,
  conventionIndex, effectiveEpochDay, maturityEpochDay, productType, expiryEpochDay,
  exerciseStyle}` in booking order, capped at 4096 and
  refusing at capacity with `RiskReason.CAPACITY`. `contractId` is the booking's own consensus
  sequence.
- `SwapConventions`: a compile-time table of five market conventions (float index, payment
  frequency, day count, currency) addressed by index, append-only, stored nowhere in replicated
  state. An index this build does not know aborts the render rather than resolving to another.
- `BlpRiskState.decideSwapBooking`: the ordered admission pipeline with the swap's notional
  measured directly, and without the four checks that read state a swap does not have (ADR-063).
- `T_CONTRACT` (12) snapshot records, restoring in booking order and
  failing closed on an id beyond the restored applied sequence or out of ascending order.
- A `#contracts` section in the cut after the position rows, with the count declared in the cut
  header, emitted even when the store is empty.
- A second EOD artifact, `seq-<N>-contracts.csv`: one row per contract carrying
  direction, notional, fixed rate, both dates, float index, frequency, day count, currency,
  counterparty and netting set — and no valuation of any kind.
- `RiskExtractMain --rebuild <cut> <positions.csv> <contracts.csv>`: an optional fourth argument
  rebuilding the contracts artifact from the same stored cut.
- `RISK-EXTRACT-CUT` log lines carry `contracts=<C>`, so cross-member agreement on the contract
  store is readable from the pod logs alongside the position row count.
- `KIND_SWAP_BOOKED` (102) egress ack, correlated by the `clientOrderKey` it echoes.

### Swaptions (phase 2)

- `POST /swaptions`: the swap body plus `expiryDate` and `exerciseStyle`. Every other field
  describes the UNDERLYING swap, so `fixedRate` is the strike and `payReceive` is the direction of
  the underlying's fixed leg.
- `TYPE_SWAPTION_BOOK` (13): a distinct command type, so the product is the COMMAND and never the
  presence or value of a field. Every slot keeps its `TYPE_SWAP_BOOK` meaning; the option wrapper
  rides `securityId` as convention index / exercise style / expiry epoch-day.
- An exercise-style table beside the convention table in `SwapConventions` — `EUROPEAN`, `BERMUDAN`,
  `AMERICAN` — index-addressed, append-only, with the same knowing refusal for an unknown index.
- Three columns on the contract record: `productType`, `expiryEpochDay`, `exerciseStyle`, zero for
  a swap.
- Contract ids `SWPT-<consensusSequence>` for swaptions.

## Changed

- `risk.extract.ready` gains `contractsSchema`, `contractsUri`, `contracts` and `contractsSha256`,
  alongside the existing fields. `consensusSequence`, `sessionDate` and `cutSha256` are shared by
  both artifacts.
- `RiskExtractCut.render` takes the contract tuples and emits the second section; `SCHEMA` moves
  1 → 2.
- `RiskExtractCsv.render` stops at the first `#`-prefixed line after the position rows. The netted
  extract's own `SCHEMA` stays at 3 and no column changes.
- `RiskExtractGcsSink.put` delivers both fixtures in one call and returns both URIs; the file sink
  writes both beside the single stored cut, both write-once.
- `SNAPSHOT_FORMAT` 4 → 5 (the `T_CONTRACT` record) → 6 (its option-wrapper columns) → 7 (the
  `T_FX_RATE` record the credit gate values non-USD notionals with) → 8 (the book's derived tick in
  `T_BOOK`) → 9 (the replayed halves of the ref and trade counters in `T_HEADER`). `T_CONTRACT`
  restore reads a record at the width its FORMAT declares, because the record carries no length of
  its own.
- `MIN_READABLE_SNAPSHOT_FORMAT` 3 → 8 → 9, the first raises in this lineage. Formats 5, 6 and 7
  only added a record type and rolled forward untouched; 8 changed how a book's geometry is derived
  and 9 changed what the header must carry, and in both cases restoring an older snapshot would
  have produced a wrong answer silently rather than a legible refusal. Both are deterministic-core
  changes, so neither can be rolled gradually: a fresh epoch is mandatory.
- Cut schema 2 → 3 and contracts-artifact schema 1 → 2: the option columns append to both. The
  netted extract stays at CSV schema 3 with no column change.

## Retained unchanged

- Every order, cancel, replace, trade, price, control and extract-marker path, including the
  matching engine, the book, the position model and the netted extract's schema and columns.
- The symbol table and its 1024-entry capacity: a swap gets no entry.
- Every NATS subject, stream and payload shape; no subject is added, removed or renamed.
- The order hot path, and therefore the allocation gates and the Epsilon-GC proofs.

## Not modelled

- Contract lifecycle: no resets, coupon payments, accrual, amortisation, unwinds or terminations,
  and no swaption EXERCISE. A contract past its maturity or expiry date is listed exactly as booked.
  Exercise STYLE is modelled, because it is a term rather than an event.
- Valuation: no NPV, mark, discount factor, curve, par rate or sensitivity.
- Matching: there is no swap order book and no crossing path.
