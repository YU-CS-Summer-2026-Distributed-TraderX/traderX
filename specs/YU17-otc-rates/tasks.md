# Tasks: YU17-otc-rates

- [x] T-OTC01: Write the headline proof first — `scripts/proofs/yu17-swap-netting.sh` — and let its
      external contract drive the design: the route, the response shape, the artifact filename, the
      announcement fields.
- [x] T-OTC02: Add `TYPE_SWAP_BOOK = 12` to `InputEvent` with its slot map and the packed date pair,
      copied forward from the operative YU03 layer.
- [x] T-OTC03: Add `SwapConventions` — the compile-time convention table, append-only by index,
      with a knowing refusal for an index this build does not have.
- [x] T-OTC04: Add `BlpRiskState.decideSwapBooking` on the YU14 operative layer: measure the
      notional, drop the four checks that read state a swap does not have, accrue on acceptance.
- [x] T-OTC05: Add the contract store, `T_CONTRACT`, `MAX_CONTRACTS` and `onSwapBook` to
      `MatchingEngineClusteredService`; dispatch before the engine so `MatchingEngine` is untouched.
- [x] T-OTC06: Move `SNAPSHOT_FORMAT` 4 → 5, hold `MIN_READABLE_SNAPSHOT_FORMAT` at 3, and write
      and restore contracts in booking order with fail-closed id checks.
- [x] T-OTC07: Add the cut's `#contracts` section at cut schema 2, emitted even when empty, with
      the count declared in the header and ascending ids asserted at render.
- [x] T-OTC08: Stop `RiskExtractCsv` at the section marker, leaving CSV schema 3 and every column
      unchanged.
- [x] T-OTC09: Add `SwapContractCsv` — the per-trade artifact, terms only, with a preamble that
      states the absence of valuation rather than leaving it to be inferred.
- [x] T-OTC10: Render, write and announce both artifacts from one cut under one stamp in
      `RiskExtractMain`; deliver both in one call on the GCS sink; add the optional fourth
      `--rebuild` argument.
- [x] T-OTC11: Add `POST /swaps` to the gateway, refusing every unrepresentable term before
      sequencing, and `KIND_SWAP_BOOKED` correlation on egress.
- [x] T-OTC12: Carry the two changed call sites forward into this layer —
      `RiskExtractTest` (cut render signature, and the position-section filter) and
      `RiskExtractGcsSinkLiveProofTest` (sink signature, plus an assertion that the second object
      lands). A test that cannot compile is not a test that is passing.
- [x] T-OTC13: Add `SwapBookingTest`: the netting headline, the netted extract's immunity, snapshot
      round-trip, format-4 restore, truncation, an unknown convention index, idempotent retry,
      key-less distinctness, the packed date range, and the notional-not-quantity×rate assertion.
- [x] T-OTC14: Verify the new tests actually executed (`tests="16"`, `skipped="0"` in the JUnit XML)
      and that the headline goes RED when contract netting is spliced into the apply path.
- [x] T-OTC15: Register the state in all five pipeline places — the generate/render pair, the
      catalog, the runtime-harness installer (both cases), the CI-assets allow-list, and the
      start/stop/status/test wrapper scripts.
- [x] T-OTC16: Author the spec pack: README, spec, plan, research, data-model, quickstart, the two
      requirement deltas, the contract delta, the architecture model (generated to
      `architecture.md`), runtime topology, messaging subject map, and ADR-062/063/064.

## Still open

- [ ] T-OTC17: Run `bash pipeline/generate-state.sh YU17-otc-rates` from clean and confirm the exit
      code is 0 — not merely that the `[summary]` block printed, since a later install stage can
      fail after it.
- [ ] T-OTC18: Build the cluster image from the composed tree, roll the kind rig forward onto the
      existing epoch behind a snapshot barrier, and confirm every member reports the target image
      and Ready before any traffic.
- [ ] T-OTC19: Run `scripts/proofs/yu17-swap-netting.sh` against the rolled rig, including its
      negative controls, and record the sequence and hashes in
      `generation/implementation-status.md`.
- [ ] T-OTC20: Run the full inherited suite (`scripts/yu15/run-proofs.sh`) and confirm every YU16
      proof still passes.
- [ ] T-OTC21: Prove the rebuild-from-empty-disk arm live: delete a member's PVC, let it rebuild
      from snapshot plus log, and confirm it re-renders the identical cut with the contracts intact.
