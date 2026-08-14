# Implementation Status: YU17-otc-rates

Status: implemented and verified — by generation, the component suite, the live kind rig, and the
inherited proof suite run against this build.

## What was built

| Area | Artifact |
|---|---|
| Spec pack | spec, plan, research, data model, contract delta, functional/non-functional deltas, subject map, topology, ADR-062 … ADR-065, generated architecture |
| Command | `TYPE_SWAP_BOOK` (12) and `TYPE_SWAPTION_BOOK` (13) on the inherited SBE template 1; the slot map, the packed date pair and the option-terms word on `InputEvent` |
| Conventions | `SwapConventions` — five market standards and three exercise styles, compiled in, addressed by index, append-only, each with a knowing refusal for an unknown index |
| Risk gate | `BlpRiskState.decideSwapBooking` — the notional measured directly, the four instrument-shaped checks dropped for cause, accrual on acceptance |
| Engine | contract store, `onSwapBook` (both products), `T_CONTRACT` (12) at 11 columns, `MAX_CONTRACTS` 4096, `SNAPSHOT_FORMAT` 6 with by-format restore width, `MIN_READABLE_SNAPSHOT_FORMAT` held at 3, `KIND_SWAP_BOOKED` (102). `MatchingEngine` is NOT overridden — an OTC contract never reaches it |
| Cut | schema 2 with a `#contracts` section, emitted even when empty, ascending ids asserted at render, `contracts=` on the `RISK-EXTRACT-CUT` log line |
| Extract | `SwapContractCsv` (terms only, no valuation), `RiskExtractCsv` stopping at the section marker with schema 3 and every column unchanged, both artifacts rendered/written/announced from one cut under one stamp, optional fourth `--rebuild` argument, GCS sink delivering both in one call |
| Ingress | `POST /swaps` and `POST /swaptions` behind one shared validator, with every unrepresentable term refused before sequencing |
| Proofs | `scripts/proofs/yu17-swap-netting.sh` and `scripts/proofs/yu17-swaption-terms.sh` (live headlines, each with negative controls); `SwapBookingTest` (the same claims without a cluster) |

## Verification

| Check | Result |
|---|---|
| `bash pipeline/generate-state.sh YU17-otc-rates` from clean | **EXIT=0** |
| YU17 overlay present in the generated tree | `TYPE_SWAP_BOOK` in `InputEvent`/`MatchingEngineClusteredService`; `SwapConventions` and `SwapContractCsv` present |
| Ancestor markers survive the overlay (shadowed-layer check) | YU16 `TREASURY_BOOK_TICK_PX`, YU15 `KIND_RISK_EXTRACT_MARKED`, YU14 `OccSymbol.multiplierFor`, YU13 `TYPE_ORDER_REPLACE` all still present |
| order-matcher suite | **371 / 0 failures / 4 skipped** (YU16 carried 344; +27) |
| `SwapBookingTest` executed, not merely compiled | `tests="23" failures="0" skipped="0"` in the JUnit XML |
| `RiskExtractTest` unchanged in count | **21 / 0** |
| Snapshot format compat | `ClusterSnapshotFormatCompatTest` 3/0; `aFormatFourSnapshotStillRestores`, `aFormatFiveSnapshotRestoresItsSwapsAsSwaps` and `aSnapshotFromThisBuildDeclaresFormatSix` all pass |
| Negative control, phase-1 headline | Splicing contract netting into `onSwapBook` makes `twoOffsettingSwapsSurviveAsTwoContracts` FAIL at the contract-count assertion; restoring makes it pass |
| Negative control, phase-2 headline | Publishing every swaption as EUROPEAN makes `aEuropeanAndABermudanOnIdenticalTermsAreTwoContracts` FAIL; restoring makes it pass |
| Negative control, the ASCII gate | Putting the em-dash back makes `bothArtifactsAreUsAsciiEncodable` FAIL — the test that would have caught the live bug |

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
| The proof cannot pass against a stale build | Its preflight reads `SwapConventions.class` out of the running member and runs its own negative control against a class that cannot exist. The phase-2 proof greps the class for `BERMUDAN`, so a PHASE-1 image is rejected too |

## Phase 2, verified on the live rig

| Claim | Evidence |
|---|---|
| A swaption is sequenced and gets its own id space | `SWPT-22938` and `SWPT-22939` at consensus 22938/22939, applied sequence +2 |
| The style is the whole instrument | A European and a Bermudan payer swaption identical in fourteen columns render as two rows differing in exactly `exerciseStyle` |
| One artifact, two products | `SW-22940,…,SWAP,,` beside `SWPT-22939,…,SWAPTION,2027-02-15,BERMUDAN` in the same file at schema 2 |
| Neither product becomes a position | Netted extract still `schema=3`, 6 position rows, **0** OTC rows |
| **A format-5 epoch rolls forward** | A swap booked on the PHASE-1 build, snapshot barrier taken so a format-5 `T_CONTRACT` existed on disk, then the members rolled to phase 2. `SW-18645,22214,RECEIVE_FIXED,7000000,0.039700,USD-SOFR,2026-09-01,2033-09-01,1Y,ACT/360,USD,…,SWAP,,` — terms intact, read at the old width |
| The boundary refuses an unknown style pre-consensus | `"Asian"` → 400, applied sequence unmoved at 22927 |
| All three members agree | `91e0a3f47351…` identical on members 0, 1 and 2 at N=22941 |
| Both artifacts still reproduce from the cut | `--rebuild seq-22941.cut` byte-compared equal |

### Two bugs the live run found that the unit tests could not

1. **An em-dash in the artifact preamble.** Artifacts are written with `US_ASCII`, so one non-ASCII
   character threw `UnmappableCharacterException` and aborted the whole EOD batch — after the cut
   had already been rendered and hashed. Every existing test renders to a `String` and never
   encodes it, so none of them could see it. `bothArtifactsAreUsAsciiEncodable` now covers all
   three artifacts and is verified to go red when the em-dash is put back.

2. **Reading the applied sequence from one member.** Both proofs sampled member 0, which races with
   catch-up: a member that has just restored reports the position it restored to while the others
   are past it (observed: member 0 at 22927 with `engineApplied -1` while 1 and 2 were at 22929), so
   a "+2" measured across that gap is a statement about replication lag. `quiesced_seq()` now waits
   for all three to agree first — the rule the cross-member digest already followed.

## The inherited suite

`scripts/yu15/run-proofs.sh` must be run with `YU15_CLUSTER_IMAGE=traderx/cluster-node:yu17`: the
runner pins the rig to its baseline image and wipes to a fresh epoch, so the default rolls the
members BACK to the YU16 build and the suite then says nothing about this state.

**Result on the phase-2 build: 24 passed, 0 skipped, 3 failed** (`yu17-swap-netting`,
`yu13-cancel-ingress`, `yu13-stp-and-replace`), and on the phase-1 build before it: 23 passed,
3 failed. Every failure was then run individually and resolved:

`yu17-swap-netting` failed on the phase-2 build because its own row check still asserted the 13
columns the artifact had before swaptions widened it to 16. The row was correct; the proof was
describing an older artifact. Fixed, and the check now asserts a SWAP row's option columns are
EMPTY rather than just counting — so widening the file again cannot quietly start filling them.
Re-run green at N=2433.

The other two are the same pre-existing blocker in both runs:

| Proof | Outcome |
|---|---|
| `yu13-otel-trace-join` | **PASSES** once `OTEL_SAMPLE_MASK` is the manifest's `0` rather than the `127` the rig had drifted to. The pods sample on the mask; the proof's own predicate reads the mask from ITS shell and defaults to 0, so a drifted rig makes it expect traces the engine never sampled |
| `yu13-cancel-ingress` | Fails at "roll the gateway to `traderx/cluster-node:yu15-cancel`". Not a YU17 regression: pre-YU16 gateway images serve their probes on 18110 only, and the manifest's startup probe points at 18111, so the kubelet crash-loops them. Diagnosed to the kubelet event and written up in `issues/HANDOFF-issue-historical-gateway-images-fail-the-probe-port.md` |
| `yu13-stp-and-replace` | Same cause — the runner's `[stp-prep]` repins the gateway to `traderx/cluster-node:yu15-pre` |

So every inherited proof that CAN run against the current manifest passes on this build, and the
two that cannot are blocked by a YU16-era probe-port change meeting historical images, not by
anything in this state.

`yu15-risk-extract` passing matters most of them: it is the proof most exposed to the cut's schema
change, and it is green. `yu16-book-grid` passing is the second: a member rebuilt under a format-5
snapshot re-derives a byte-identical book geometry.

### A caution about the first attempt

An earlier full run reported six failures. Those were caused by editing `run-proofs.sh` while bash
was executing it — bash reads a script incrementally by byte offset, so inserting bytes mid-file
shifts every later offset and the shell resumes mid-token (`line 472: unexpected EOF while looking
for matching '"'`). Every one of those six passes in the clean run. The corrupted run also died
inside the `stp-prep` block without running its restore, leaving the rig on
`traderx/cluster-node:yu15-pre` with the control feed off, observability at zero replicas and the
OTel sample mask drifted — all of which had to be undone by hand before the clean run.

Do not edit a script that is currently executing.
