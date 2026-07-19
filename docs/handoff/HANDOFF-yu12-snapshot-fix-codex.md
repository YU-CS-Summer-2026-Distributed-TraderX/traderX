# Handoff: YU12 snapshot barrier O(n) fix

Date: 2026-07-19

Branch: `YU12-snapshot-fix`

Worktree: `/Users/yaakov/dev/lmax/traderX-YU12-snapshot-fix`

Base: `70142ce` (`YU12 Phase 0: FIRST-APPLY SLO stamp`)

## Commits

- `ecabf7c` — `YU12: make snapshot order serialization O(n)`
- The handoff note is committed separately immediately after the implementation commit.

Never pushed. Fable can merge the branch or cherry-pick the implementation commit and this
handoff commit.

## Result

`MatchingEngineClusteredService.writeSnapshot` no longer performs a complete `allOrders` scan
for every terminal reference. It now:

1. Builds the terminal-ref membership set exactly as before.
2. Scans the dense order-ref index once in ascending ref order, writing non-terminal rows.
3. Resolves each terminal ref directly from the index and writes it in exact eviction-FIFO order.
4. Reuses one 15-long tuple buffer for all order rows.

The snapshot format, record payloads, and ordering contract are unchanged. Restore code is
untouched. The apply path is untouched.

## Callback measurement

Fixture: 100,000 retained orders, including 50,000 terminals, with a `SnapshotWriter` that
observes every emitted record.

| Measurement | Before | After |
|---|---:|---:|
| Identical no-warmup run (`warmupRuns=0`, `measuredRuns=1`) | 15,365.314 ms | 14.900 ms |
| Default gate (`warmupRuns=2`, `measuredRuns=5`) | Not repeated: one old callback already took 15.4 s | min 7.310 ms, median 9.815 ms, max 12.187 ms |
| Gate | Failed `<= 50 ms` | Passed `<= 50 ms` |

The identical single-run comparison is about **1,031x faster**. Both versions emitted 100,003
records and produced benchmark checksum `2662551566251133587`. The separate codec test compares
every changed order-record byte against an executable copy of the legacy ordering algorithm.

## Decisions

- I first tried the brief's suggested one-pass `Int2ObjectHashMap`. It removed O(T x N), but its
  five-run maximum was 76.234 ms (median 28.867 ms), so I did not keep it.
- The committed design exposes two cold snapshot helpers on `MatchingEngine`:
  `snapshotOrderIndexLength()` and `copySnapshotOrderTuple(...)`. This avoids the temporary map
  and per-order tuple arrays without exposing mutable pooled orders.
- `allOrderTuples()` now delegates tuple field copying to the same helper, keeping the tuple
  definition single-sourced.
- A package-private `initEngine(initialPoolSize, terminalRetain)` overload exists only to build
  the inflated test fixture. The production `initEngine()` retains the exact existing sizes.

## Verification

All verification ran against regenerated
`generated/code/target-generated/order-matcher`; the generated service, engine, and benchmark
files were byte-identical to their YU12 runtime-override sources.

- `bash pipeline/generate-state.sh YU12-aeron-cluster` — passed.
- `./gradlew test --no-daemon` — passed in 1m13s, including:
  - `SnapshotBarrierPerformanceTest` — 100k/50k callback gate.
  - `ClusterSnapshotCodecTest` — 9/9, including full order-record byte identity and terminal FIFO.
  - `AeronClusterSpikeTest.completeStateSurvivesSnapshotAndZeroTailRecoveryWithoutIdReuse`.
  - `ThreeMemberClusterTest.wipedMemberRejoinsAndLineageSurvivesTwoFailovers`.
  - `clusterAllocationGateTest` — exact-zero cluster apply allocation.
- `./gradlew noGcTest --no-daemon` — passed, including the dependent risk no-GC gate.

No live cluster access, image build, deployment, or push was performed.
