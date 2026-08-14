# Implementation Status: YU17-otc-rates

Status: implemented; verified by generation and the component suite. The live-rig arms are listed
under "Not yet verified live" and are open (`tasks.md` T-OTC17…T-OTC21).

## What was built

| Area | Artifact |
|---|---|
| Spec pack | spec, plan, research, data model, contract delta, functional/non-functional deltas, subject map, topology, ADR-062 … ADR-064, generated architecture |
| Command | `TYPE_SWAP_BOOK` (12) on the inherited SBE template 1; the slot map and the packed date pair on `InputEvent` |
| Conventions | `SwapConventions` — five market standards, compiled in, addressed by index, append-only, with a knowing refusal for an unknown index |
| Risk gate | `BlpRiskState.decideSwapBooking` — the notional measured directly, the four instrument-shaped checks dropped for cause, accrual on acceptance |
| Engine | contract store, `onSwapBook`, `T_CONTRACT` (12), `MAX_CONTRACTS` 4096, `SNAPSHOT_FORMAT` 5, `MIN_READABLE_SNAPSHOT_FORMAT` held at 3, `KIND_SWAP_BOOKED` (102). `MatchingEngine` is NOT overridden — a swap never reaches it |
| Cut | schema 2 with a `#contracts` section, emitted even when empty, ascending ids asserted at render, `contracts=` on the `RISK-EXTRACT-CUT` log line |
| Extract | `SwapContractCsv` (terms only, no valuation), `RiskExtractCsv` stopping at the section marker with schema 3 and every column unchanged, both artifacts rendered/written/announced from one cut under one stamp, optional fourth `--rebuild` argument, GCS sink delivering both in one call |
| Ingress | `POST /swaps` with every unrepresentable term refused before sequencing |
| Proofs | `scripts/proofs/yu17-swap-netting.sh` (live headline, with negative controls); `SwapBookingTest` (the same claim without a cluster) |

## Verification

| Check | Result |
|---|---|
| `bash pipeline/generate-state.sh YU17-otc-rates` from clean | **EXIT=0** |
| YU17 overlay present in the generated tree | `TYPE_SWAP_BOOK` in `InputEvent`/`MatchingEngineClusteredService`; `SwapConventions` and `SwapContractCsv` present |
| Ancestor markers survive the overlay (shadowed-layer check) | YU16 `TREASURY_BOOK_TICK_PX`, YU15 `KIND_RISK_EXTRACT_MARKED`, YU14 `OccSymbol.multiplierFor`, YU13 `TYPE_ORDER_REPLACE` all still present |
| order-matcher suite | **364 / 0 failures / 4 skipped** (YU16 carried 344; +20) |
| `SwapBookingTest` executed, not merely compiled | `tests="16" failures="0" skipped="0"` in the JUnit XML |
| `RiskExtractTest` unchanged in count | **21 / 0** |
| Snapshot format compat | `ClusterSnapshotFormatCompatTest` 3/0; `SwapBookingTest.aFormatFourSnapshotStillRestores` and `aSnapshotFromThisBuildDeclaresFormatFive` both pass |
| Negative control on the headline | Splicing contract netting into `onSwapBook` makes `twoOffsettingSwapsSurviveAsTwoContracts` FAIL at the contract-count assertion; restoring makes it pass. The assertion has been observed failing |

`RiskExtractGcsSinkLiveProofTest` reports 1 test, 1 skipped: it is gated on
`RISK_EXTRACT_GCS_HMAC_KEY_ID` and does not run without live GCS credentials. That is a declared
opt-out, not coverage — the second object's delivery is asserted there and is unverified until the
test runs with credentials.

`ThreeMemberClusterTest.wipedMemberRejoinsAndLineageSurvivesTwoFailovers` failed once, on the first
full-suite run, with `condition not met within 120s` waiting on egress — a timeout, not an
assertion. It passed alone in 26s and passed in a subsequent full `--rerun-tasks` run of all 364
tests. The in-process three-member cluster is CPU-hungry and the first run shared the machine; the
failure is recorded here rather than omitted, since a timeout under load and a real regression look
identical from the exit code alone.

## Not yet verified live

The following are claims this state makes that only the rig can settle, and none of them has been
run yet:

- The kind rig rolled forward onto the existing YU16 epoch behind a snapshot barrier, with every
  member on the target image and Ready before any traffic.
- `scripts/proofs/yu17-swap-netting.sh` end to end, including its negative controls.
- The full inherited suite (`scripts/yu15/run-proofs.sh`) still green.
- A member destroyed to an empty disk rebuilding and re-rendering the identical cut with the
  contracts intact.

The proof's own preflight refuses to run against an image that predates this state — it reads
`SwapConventions` out of the running member's classes — because a proof asserting new behaviour
cannot otherwise tell you it ran against a stale build.
