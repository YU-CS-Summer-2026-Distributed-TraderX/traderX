# Functional Delta: YU17-otc-rates (vs YU16-cdm-instruments)

Everything inherited from YU16 and its ancestry is carried forward unchanged unless listed here.

## Added

- `POST /swaps` on the cluster gateway: books a vanilla fixed-float OTC interest-rate swap from
  `accountId`, `payReceive`, `notional`, `fixedRate`, `effectiveDate`, `maturityDate`,
  `conventions` and an optional `clientOrderId`. Returns `{"contractId":"SW-<N>","sequence":N,
  "booked":true}`, 422 with a `RiskReason` when the gate refuses, 400 when a term cannot be
  represented, 504 when no decision committed.
- `TYPE_SWAP_BOOK` (12) on the inherited `InputEventMessage` (SBE template 1): a sequenced
  consensus command carrying the swap's economics in the record's existing slots, applied in
  `MatchingEngineClusteredService` and never handed to `MatchingEngine`.
- A replicated OTC contract store: `{contractId, accountId, payFixed, notional, fixedRateTicks,
  conventionIndex, effectiveEpochDay, maturityEpochDay}` in booking order, capped at 4096 and
  refusing at capacity with `RiskReason.CAPACITY`. `contractId` is the booking's own consensus
  sequence.
- `SwapConventions`: a compile-time table of five market conventions (float index, payment
  frequency, day count, currency) addressed by index, append-only, stored nowhere in replicated
  state. An index this build does not know aborts the render rather than resolving to another.
- `BlpRiskState.decideSwapBooking`: the ordered admission pipeline with the swap's notional
  measured directly, and without the four checks that read state a swap does not have (ADR-063).
- `T_CONTRACT` (12) snapshot records at `SNAPSHOT_FORMAT` 5, restoring in booking order and
  failing closed on an id beyond the restored applied sequence or out of ascending order.
- Cut schema 2: a `#contracts` section after the position rows, with the count declared in the cut
  header, emitted even when the store is empty.
- A second EOD artifact, `seq-<N>-contracts.csv` at schema 1: one row per contract carrying
  direction, notional, fixed rate, both dates, float index, frequency, day count, currency,
  counterparty and netting set — and no valuation of any kind.
- `RiskExtractMain --rebuild <cut> <positions.csv> <contracts.csv>`: an optional fourth argument
  rebuilding the contracts artifact from the same stored cut.
- `RISK-EXTRACT-CUT` log lines carry `contracts=<C>`, so cross-member agreement on the contract
  store is readable from the pod logs alongside the position row count.
- `KIND_SWAP_BOOKED` (102) egress ack, correlated by the `clientOrderKey` it echoes.

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
- `SNAPSHOT_FORMAT` 4 → 5. `MIN_READABLE_SNAPSHOT_FORMAT` is unchanged at 3.

## Retained unchanged

- Every order, cancel, replace, trade, price, control and extract-marker path, including the
  matching engine, the book, the position model and the netted extract's schema and columns.
- The symbol table and its 1024-entry capacity: a swap gets no entry.
- Every NATS subject, stream and payload shape; no subject is added, removed or renamed.
- The order hot path, and therefore the allocation gates and the Epsilon-GC proofs.

## Not modelled

- Contract lifecycle: no resets, coupon payments, accrual, amortisation, unwinds or terminations.
  A contract past its maturity date is listed exactly as booked.
- Valuation: no NPV, mark, discount factor, curve, par rate or sensitivity.
- Matching: there is no swap order book and no crossing path.
