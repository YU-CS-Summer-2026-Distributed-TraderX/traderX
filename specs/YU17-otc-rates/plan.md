# Implementation Plan: YU17-otc-rates

## Goal

Absorb an instrument class that cannot match and cannot net, without giving up determinism and
without degrading the file every existing consumer already reads. A swap booking enters the same
replicated log as an order, is applied by the clustered service into a contract store that is
ordinary replicated state, and is published as a second artifact rendered from the same cut at the
same consensus sequence. The netted position extract keeps its schema, its columns and its meaning
for the four instrument classes netting is correct for.

## Workstreams

### 1. The booking command

`TYPE_SWAP_BOOK = 12` on the existing SBE template 1. `AeronReplicationCodec` copies `commandType`
through without interpreting it, so a new command type costs no schema change — the pattern YU13
established for atomic replace. The economics ride the record's existing slots, five of them in a
slot whose current meaning already fits and only the date pair packed. `POST /swaps` on the gateway
resolves the conventions name to its index, validates every term the record cannot represent, and
offers one command.

### 2. The contract store

A list in `MatchingEngineClusteredService`, beside the symbol table rather than inside
`MatchingEngine`: a swap has no book to rest in and no position to accumulate, so the engine is not
merely uninvolved, it has nothing to be involved in. Contract id is the booking's own consensus
sequence, which removes a generator, a snapshot header field and a restore invariant from the
design. `T_CONTRACT` joins the snapshot at format 5, with `MIN_READABLE_SNAPSHOT_FORMAT` held at 3.

### 3. The risk gate

The one place engine work is genuinely needed. `decideSwapBooking` keeps the ordered pipeline and
the stable precedence and changes what is measured: the notional is the notional. The four checks
it drops are dropped because they are wrong for a swap, not because they are inconvenient —
security state that does not exist, and the two limits that are projections of the very netting
grain this state exists to demonstrate the loss of.

### 4. Market conventions

A compile-time table addressed by index. The variable economics of a vanilla fixed-float IRS are a
small enum in practice, so putting them in a table keeps the per-trade payload inside the existing
record. Stored nowhere, derived everywhere — the same property `OccSymbol.multiplierFor` and the
derived bond grid have.

### 5. Two artifacts, one cut

The cut gains a `#contracts` section at cut schema 2. One artifact renders the positions and stops
at the marker; the other renders the contracts. Both come from the same bytes under the same stamp,
so they share a sequence and a `cutSha256` by construction rather than by the producer being
careful. The sink writes both write-once beside the single stored cut, and the announcement carries
both hashes.

### 6. Proofs

`scripts/proofs/yu17-swap-netting.sh` is written first and drives the design: it books the pair,
proves it was sequenced, and asserts the contracts artifact carries two contracts at a sequence
where the netted artifact carries none. `SwapBookingTest` makes the same claim without a cluster
and covers the paths the live proof cannot reach cheaply — snapshot round-trip, format-4 restore,
truncation, an unknown convention index, idempotent retry.

## Key decisions

- **Sequenced through consensus, though nothing matches** (ADR-062). Booking into the read model
  would put swaps outside the cut and retire the extract's strongest claim.
- **The rate is not a price** (ADR-063). The gate gets a swap path rather than a bypass, because a
  bypass would mean a 10mm notional consuming no credit at all.
- **Two artifacts, not one polymorphic file** (ADR-064). A single file would force every consumer
  to branch on instrument type before reading any column.
- **Terms, not values.** No NPV, no curve, no discounting. The consumer's engine is authoritative
  for what a contract is worth; a second number from here is a reconciliation break, not data.
- **No symbol-table entry per swap.** `MAX_SECURITIES` is 1024 and every swap trade is a new
  identity, so a swap book would exhaust the table in an afternoon — and a book with no resting
  orders is dead weight in every snapshot.

## Exit Criteria

- `bash pipeline/generate-state.sh YU17-otc-rates` exits 0 and the pack matches house style.
- A swap booked through the gateway is sequenced, and all three members agree on the contract store
  by rendered hash at one consensus sequence.
- A member rebuilt from an empty disk reproduces the contract store byte-identically; a format-4
  snapshot still restores.
- The extract emits both artifacts at one consensus sequence, each byte-reproducible from the
  stored cut alone, with the netted artifact unchanged for equities, Treasuries and options.
- The receiver-4.2% / payer-4.3% pair is a passing proof carrying its own negative controls.
- Every inherited proof still passes.
