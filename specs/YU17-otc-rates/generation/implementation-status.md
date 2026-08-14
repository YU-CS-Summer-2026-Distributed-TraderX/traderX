# Implementation Status: YU17-otc-rates

Status: implemented; verified by generation, the component suite and the live kind rig. One
acceptance item remains open — see "The inherited suite" below (`tasks.md` T-OTC20).

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

## Verified on the live kind rig

| Claim | Evidence |
|---|---|
| Rolls FORWARD onto an existing YU16 epoch with no PVC wipe | Snapshot barrier taken on all three members (snapshots 282/218/218 → 283/219/219), then the image set; applied sequence 23390 preserved across the roll, so the format-4 snapshot restored on the format-5 build |
| Every member on the target image and Ready before any traffic | Gated on the fact, not `rollout status`: all three pods reporting `traderx/cluster-node:yu17 true` |
| A swap is SEQUENCED | Two bookings moved the applied sequence by exactly 2 |
| Contract ids are the booking sequences | `SW-16745` / `SW-16746` at consensus 16745/16746 |
| All three members agree on the contract store | `contracts=2` and cut sha `18c2faf6ec4a…` identical on members 0, 1 and 2 at N=16747 |
| The headline: netting would have deleted this position | Both contracts in the artifact with both rates, at a sequence where the netted extract carried 14 position rows and **0** swap rows |
| The netted extract is unchanged | Still `schema=3`, every column as before |
| Both artifacts reproduce from the stored cut alone | `--rebuild seq-16747.cut` byte-compared equal for both files |
| Quiescence witness holds | Announced `quiesceWitnessSequence` = N+1 |
| A member destroyed to an empty disk rebuilds the store | Member 2's **PVC deleted** (not just the pod); it rebuilt from nothing and re-rendered `18c2faf6ec4a…` with 2 contracts |
| The risk gate is wired | A booking on an unseeded account returned 422 `UNKNOWN_ACCOUNT` and created no contract |
| The proof cannot pass against a stale build | Its preflight reads `SwapConventions.class` out of the running member and runs its own negative control against a class that cannot exist |

## The inherited suite

`scripts/yu15/run-proofs.sh` must be run with `YU15_CLUSTER_IMAGE=traderx/cluster-node:yu17`: the
runner pins the rig to its baseline image and wipes to a fresh epoch, so the default rolls the
members BACK to the YU16 build and the suite then says nothing about this state.

`yu17-swap-netting` passes inside the suite. The first full run also reported six failures, none of
which touches swaps — the gateway answered `ready=200` from inside the cluster throughout, no pod
restarted, and every failure traces to an HTTP call through a port-forward returning nothing, on a
box at load average 5-8 with the kind nodes at 60-92% CPU. Three separate reporting defects turn
that into a verdict about the system, including two proofs that print their own `[PASS]` and are
recorded FAIL; all three are written up in `issues/HANDOFF-issue-suite-verdicts-under-load.md`.

Two of those runs are themselves evidence FOR this state rather than against it:
`yu15-risk-extract` passed on the YU17 build (it is the proof most exposed to the cut's schema
change), and `yu16-book-grid`'s substantive assertion passed — a member rebuilt under a format-5
snapshot re-derived a byte-identical book geometry — before the script died elsewhere.

The affected proofs are re-run individually on a quiet box. Until they are green on this build,
"every inherited proof still passes" is not claimed.
