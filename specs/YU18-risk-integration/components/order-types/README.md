# Order types

Status: implemented locally as one delivery; I1–I3 corrected, SC-OT35 latency acceptance open.
READY_FOR_REVIEW, 2026-09-24. Not merged, not pushed, not deployed.
- State: YU18-risk-integration.
- Owner: Claude order-types lane, worktree `traderX-order-types`, branch `claude/order-types`,
  base d6ca3330.

[Spec](spec.md) · [Plan](plan.md) · [Tasks](tasks.md). Backlog: RI-01. Shared quickstart,
contracts and generation stay two levels up.

The scope is MARKET, LIMIT, STOP, STOP_LIMIT, ICEBERG, PEGGED (against the local book) and
TRAILING_STOP, with DAY/GTC/IOC/FOK, mapped over REST, FIX and the console. OPEN-D5 is approved:
a stop reserves at its stop price and is re-decided at trigger. The review's r3 requirements
are folded into the spec (consensus limits in the snapshot, a reachable exhaustion test, bounded
derived prices, and FOK mismatch as a fatal invariant breach). So are the implementation
review's corrections (board 20260924T010239Z): a unique business-day ack kind (I1), refusal of an
already-through trailing replace (I2), exact typed parsing at every boundary (I3), and the legacy
latency fast paths (I4). They are specified as FR-OT03/04/29/37 and SC-OT49–51.

## Review corrections (I1–I4)

Evidence: `coordination/eod-integration/review-evidence/ri01-corrections-20260924/`. It holds
raw latency lines, source identities, commands, the mutation results, the reviewer's probes
re-run, and the JUnit XML.

| Finding | Fix | Test (fails with the fix undone) |
| --- | --- | --- |
| I1 ack kind collision | `KIND_BUSINESS_DAY` = 105. The reset keeps 104, and every kind is unique | `i1_everyEgressKindIsUnique`, `i1_gatewayRoutesDayResetAndPhaseAcksToTheirOwnWaiters` |
| I2 crossed trailing replace | Refused STOP_ALREADY_TRIGGERED, equality included, before the reservation moves | `i2_trailingReplaceAlreadyThroughIsRefusedAndPreservesTheOrder` |
| I3 silent coercion | Presence, JSON type and precision are checked before conversion, on REST, FIX and the console | `i3_restMapperIsExact…`, `i3_restRoutesRefuseWithoutSubmitting` (route level, with a positive control), `i3_fixMapperIsExact…`, `sc04_f1…` (fractional MaxFloor over a real session), console spec |
| I4 legacy latency | Typed output copy and typed reset skipped for legacy orders; typed output fields moved to a per-slot side object | `i4_fastPathsNeverLeakATypedShape`; latency below |

Undoing each fix alone fails exactly its own tests; `mutations.txt` has the list. The reviewer's
`EngineProbe` now shows the replace refused (reason 24) with the order unchanged. Its
`BoundaryProbe`, adapted only to the new `parseTyped(body, side)` signature, shows all three
inputs refused and the reset ack reaching `lastResetAck`.

## Verification

Run on 2026-09-23/24, on a local macOS arm64 workstation. All results are from source tests.

| What | Command | Result |
| --- | --- | --- |
| Freshly generated root | `TRADERX_GENERATED_ROOT=<empty dir> bash pipeline/generate-state-YU18-risk-integration.sh` | exit 0, 131 s, at c41e508b |
| order-matcher, full suite (fresh root, c41e508b) | `./gradlew test` | 559 tests, 6 skipped (pre-existing), 0 failures; the 5 isolated allocation gates and both no-GC gates pass |
| of which: engine | `OrderTypesEngineTest` | 35 cases, SC-OT06–47, SC-OT50, the I4 fast paths |
| of which: service | `OrderTypesServiceTest` | 9 cases: replay/restore identity, format 11, consensus limits, RISK-suspended restore |
| of which: boundary | `OrderTypesBoundaryTest` | 11 cases: REST, FIX, F1 end to end, SC-OT49, SC-OT51 |
| Typed allocation gate (SC-OT34) | `./gradlew orderTypesAllocationGateTest` | 0 bytes in steady state |
| Epsilon no-GC gates (fresh root) | `./gradlew noGcTest` | base and risk pass |
| trade-processor (fresh root at 0ede1370; the corrections do not touch it) | `./gradlew test integrationTest` | 98 unit tests and 7 integration tests pass, including the 5 `SchemaMatchesShippedDdlIT` arms (upgrade and missing-ordertype arms included) |
| Console | `npx ng test --watch=false --browsers=ChromeHeadless` | 96/96, 6 of them order types (`order-types.spec.ts`); `ng build` OK (the existing bundle-budget warning) |
| Migration | the YU18 900-migrations block applied twice to a YU17 schema on mariadb:11.4 | idempotent; a typed PENDING_TRIGGER row is accepted; an unknown status is refused by `orderbook_status` |

Mutation checks:
- In the engine, with trigger latching removed and FOK feasibility forced, 22 of 32 engine tests
  fail.
- In the service, with the business date not restored and a shared release sequence, 2 of 8
  service tests fail.

The tests catch the defects they are meant to catch.

### SC-OT35 legacy latency

`MatchLatencyBenchmarkTest` was run on three source trees, alternating, on an otherwise idle
machine:
- the pre-change sources (0ae2c53a);
- the first delivery (0ede1370);
- the corrected sources (c41e508b).

The benchmark file was identical in all three. It was patched in scratch only, so that it also
prints the HdrHistogram mean: the Apple-silicon timer ticks at about 42 ns, so a percentile can
only move in whole ticks, while a mean over 250,000 samples resolves below one. All values are
in ns.

| Op | mean, before (6 runs) | mean, first delivery (3) | mean, corrected (6) | p50 corrected vs before | p99 corrected vs before |
| --- | --- | --- | --- | --- | --- |
| resting insert | 378.6 (374–384) | 393.4 | 386.4 (381–397) | 500 in all 12 | 750–792 vs 750–833 |
| limit cross | 310.9 (305–317) | 332.1 | 321.4 (315–338) | 292 in 5 of 6 vs 6 of 6 | 500–542 vs 500–750 |
| market order | 369.0 (361–378) | 390.5 | 378.4 (369–393) | 375 in 5 of 6 vs 2 of 6 | 584–667 vs 625–875 |

The first delivery's consistent one-tick shift is gone on the cross and insert paths. The
limit-cross p50 is 292 in five of six corrected runs (333 in the sixth); the corrected p99
values are inside the baseline range.

A mean residual of about 8–10 ns (2–3%) remains. It is inside the baseline's own run-to-run
range on this machine: in a separate campaign the baseline itself read 305–332 mean, with a 333
p50 in one run. The market-order p50 sits on the 334/375 tick boundary in both builds, and lands
on 375 more often after the change.

Tested and rejected: `RestingOrder` object size. Adding the same fields, unused, to the
baseline changed nothing (campaign F).

The settle call, typed dispatch and store checks are candidate contributors to the residual;
the recorded measurements do not isolate their costs. Overlapping run ranges do not establish
equivalence. SC-OT35 remains open for further measured optimization or explicit user acceptance.
The coordinator has not waived NFR-OT06.

### Limitations
- There is no live, cluster or failover proof, and no GKE run. These need separate authorization
  (NFR-OT04: this is a core, wire and snapshot-format change, so it is never rolled gradually).
- `ThreeMemberClusterTest` once timed out in a full run under load. It passed alone (28 s) and in
  the fresh-root run.
- On FIX, peg offsets are accepted only in whole ticks (PegOffsetType 2) or as zero. Trailing
  offsets are accepted as a price (0) or in whole bps (1). Anything else is refused with a named
  Text. This is specified in FR-OT04's unit table and pinned by `sc48_fixRefuses…` and
  `i3_fixMapper…`.
- Untyped FIX and REST keep today's conversions byte for byte (NFR-OT05). That includes the
  legacy `(int)` truncation of a fractional FIX OrderQty on an untyped order. Exactness applies
  to typed orders only.
- `ng build` shows the existing bundle-budget warning.
- The state generator runs `npm install` as part of its standard pipeline; the lockfile was
  unchanged.
- `pipeline/render-state-YU18-risk-integration.sh` now overlays every YU18 module directory.
  That is a state-level change and is flagged for the coordinator.

### Codex precision follow-up (2026-09-24)

The coordinator took over the released lane at f6914d52. Typed REST decoding now preserves
original decimal precision before validation; untyped decoding retains the legacy mapper.
The raw-HTTP regression failed before the fix and passed afterward: 33 invalid cases on each
of /orders and /replace, no queued submission, plus a positive control. Eight added cases cover
fractions that binary floating point erased. FIX original-text parsing and exact valid numbers
are checked too. The YU18 renderer and 56 focused tests with five allocation gates pass.
Evidence: coordination/eod-integration/review-evidence/ri01-codex-precision-20260924/ in the
shared parent workspace. SC-OT35 remains open; this is local generated-code verification.

## Codex latency investigation (2026-09-24)

SC-OT35/NFR-OT06 remains **open**. Dedicated owner: Codex latency lane,
`traderX-order-types-latency`, branch `codex/order-types-latency`, engine base/result
`88f3a27feed91bc9c4fcdd25e97efcada83f9083`. No production engine optimization was retained.
The operative owner remains this state's `generation/runtime-overrides/order-matcher`;
no ancestor, integration branch or other worktree was edited.

Independent full-source generation of baseline `0ae2c53a` and current `88f3a27f` gave exact
matcher override parity (19/19 and 29/29 files). The inherited workload and assertions were
unchanged; identical scratch instrumentation added only the histogram mean. Each campaign
alternated pair order; all observations were retained. The table uses means across 12 pairs
and pointwise 95% paired Student-t intervals, in ns per engine call, current minus baseline.

| Local runtime | Operation | Baseline | Current | Delta [95% interval] |
|---|---|---:|---:|---|
| Microsoft JDK 21.0.12 | resting-insert | 369.22 | 357.74 | -11.48 [-15.64, -7.33] |
| Microsoft JDK 21.0.12 | limit-cross | 310.63 | 304.34 | -6.29 [-11.12, -1.47] |
| Microsoft JDK 21.0.12 | market-order | 361.76 | 354.82 | -6.94 [-11.54, -2.33] |
| OpenJDK 25.0.1 | resting-insert | 368.64 | 370.67 | +2.03 [-4.43, +8.50] |
| OpenJDK 25.0.1 | limit-cross | 307.93 | 314.15 | +6.22 [-1.01, +13.45] |
| OpenJDK 25.0.1 | market-order | 366.57 | 369.63 | +3.05 [-6.45, +12.56] |

The historical positive residual was not reproduced on the pinned JDK21 profile: all three
mean intervals were negative. JDK25 point estimates were positive, but all three intervals
crossed zero. That is **inconclusive**, not equivalence, and does not prove which JVM ran the
historical campaign. Dockerfiles target Java 21, but these host runs are not a Temurin/Linux
container or live-cluster measurement. Timer quantization and uncontrolled OS/GUI scheduling
remain limits. Full per-run percentiles and tails are retained in the evidence.

Two bounded scratch experiments, eight guarded alternating pairs each:

- Removing only the empty cascade call gave insert/cross/market mean deltas
  +2.49/-0.42/-3.26 ns, with intervals crossing zero in every case. It does not isolate the
  historical residual's cause. This diagnostic is invalid for typed flows and cannot ship.
- Extracting the cold cascade body behind the unchanged guard was **slower** by
  +7.97/+7.64/+6.87 ns (intervals [5.90,10.05]/[4.96,10.32]/[3.61,10.12]). Rejected.
  A source-level split is not automatically a JVM optimization; no CPU/JIT mechanism is claimed.

The local Docker cluster was stopped with direct user authorization. Its four containers
unexpectedly restarted during the first split campaign; that campaign and a partial JDK25
campaign are retained as contaminated evidence and carry no verdict. The earlier cascade
campaign was inconclusive and preceded the recorded restart. Repeats checked all four
containers exited before every fork. Restart policies for those four were changed from
`on-failure` to `no`, and they remain stopped with data retained. No cloud resources changed.

No last-trade writes, order-type behavior, reservation semantics, trigger ordering, tests,
assertions or allocation thresholds changed. This investigation supplies a measured tradeoff,
not a performance waiver. Next: coordinator review and explicit acceptance decision, or a
separately scoped deployment-profile measurement with an agreed acceptance bound. Do not call
SC-OT35 passed from these results or from overlapping percentile ranges.

Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri01-latency-20260924T015157Z` (`review.md`, `results/measurements.md`, protocols, scripts,
source/class hashes, generation logs, raw JSON/XML, diagnostic patches and validation logs).

### Validation of retained engine

On generated current `88f3a27f`, Microsoft JDK21.0.12: 139 selected test cases,
138 passed, 0 failed/errors, 1 existing assumption skip (`sessionStateIsAbsentFromTheSnapshotToday`,
historical negative control superseded by phase/queue round-trip coverage). Includes all 56
focused engine/service/boundary cases plus legacy baselines and snapshot/replay/book tests.
All five isolated allocation tasks passed with their existing C2-only synchronous-compilation
flags (`-Xbatch -XX:-TieredCompilation -XX:CompileThreshold=10000`); both Epsilon no-GC tasks
passed. These are execution-profile-specific allocation results, not a claim about every JVM/GC.
No assertion, warmup, event count or threshold was changed. Full service/console/migration suites
were not rerun for this documentation-only delivery.

The component validator and all four repository quality gates passed. Generated matcher owner
parity is exact; no engine/runtime override source differs from the assigned base. No live-cluster,
failover, cloud, push or propagation proof is claimed. Evidence: `results/verification/` and
`results/quality.json`.

## Combined integration validation — 2026-09-24

Codex integration/CI lane combined `3244f7eb` and reviewed `53bc3bec` in
`traderX-integration-ci` / `codex/integration-ci`. The merge had no textual conflicts.
The YU18 renderer retains all existing exporter overrides and overlays the reviewed matcher,
trade read model and DDL last; RI-03 EOD and Risk UI files are unchanged from the integration base.
No ancestor layers or engine implementation were edited.

Fresh full YU18 generation passed; all 133 YU18 runtime files matched their generated counterparts.
Hosted matcher evidence contains 563 cases: 557 passed, six existing disabled/benchmark cases,
zero failures/errors, including all 56 focused typed correctness cases and five allocation gates.
Both Epsilon gates passed with their existing Epsilon/-Xbatch/C2 settings. This is an
execution-profile-specific allocation result, not latency acceptance.
Six service suites passed 221 tests. Seven real MariaDB/NATS trade-processor integration cases
passed, including shipped DDL. Source and generated EOD each passed 152 tests plus CLI smoke.
Console: 97 headless Chrome and 13 Node reader tests; production build passed with the existing
500 kB warning (638.77 kB output). Required frontmatter, root, readiness, coverage and component gates passed.

The fresh combined exporter reproduced all three frozen example cases and six mock custody runs.
The unchanged RI-03 accepted image `88ad80a6…` then processed the fresh bill via real HTTP:
verified intake, one attempt across consumer restart, independent Decimal bill comparison within
USD 1e-8 and all four refusal cases passed. `usableForRisk` and `portfolioRiskAvailable` stay false.
An initial proof invocation with automatic Docker port allocation failed the proof's explicit-port
precondition before submission; the explicit-port rerun passed, with both logs retained.

Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/integration-ci-20260924/`.
SC-OT35/NFR-OT06 remains user-deferred/unverified until GKE credits, not waived or passed.
Four upstream API contract failures remain external blockers. No new latency campaign, live cluster,
financial risk qualification, cloud, push, publish or deployment. Final coordinator incorporation remains pending.

## Live cluster exercise (RI-07, 2026-09-24)

This was the first live proof on a real 3-member kind cluster: a fresh render of `66baef75`,
image `traderx/cluster-node:ri07-66baef75`, driven through the console server's `/order-matcher`
proxy. Evidence is in `coordination/eod-integration/review-evidence/ri07-demo-acceptance-20260924/`.

- **F1, blocking.** On the unmodified gateway every body with `orderType` returns HTTP 504
  `no committed ack` in about 5–9 ms and is never sequenced; untyped orders return 200 and
  invalid typed bodies return 422. Cause: `ClusterGatewayMain.orderBuffer` is sized
  `INPUT_BYTES` (64), but the typed encode writes `ORDER_INSTRUCTION_BYTES` (96). A one-line fix
  was proposed to the coordinator and not applied in this layer. No existing test submits a typed
  order through the gateway's pipelined submit into a cluster.
- **With a scratch-patched gateway only** (members unpatched), `scripts/ri07/order-types-live.py`
  matched 30/30 cases at the effect end, twice. The cases cover the seven types, the eligible
  TIFs, boundary and engine refusals, triggers, iceberg, peg reprice and suspension, cancel,
  replace and DAY expiry. This is local-live evidence for the patched gateway, not for `66baef75`
  as it stands.
- **F3.** A trailing watermark ratchet emits no order update, so the read model's `stopprice`
  (FR-OT33 "current level") stays at the last emitted level until the order's next event. It was
  observed at 101.5 while the engine's level was 102, which the order confirmed when it triggered
  at 102. The console label now says so; the egress question goes to the coordinator.
- The console ticket's per-type TIF and field matrix matches FR-OT06; this was checked in the
  browser against the live rig.

Update, 2026-09-24 18:45Z: F1 is fixed in integration `9931b3e5` (Codex RI-06 lane,
coordinator-integrated). The exercise was rerun on `e96bf28a`, which is that fix plus RI-07 with
no local patch: 30/30 ordinary cases, twice, through the console path, and a typed LIMIT from a
user accepted through the console. F3 still reproduces, so the exercise reports INCOMPLETE
(exit 3). This is local-live evidence, not GKE, failover or latency evidence.

## F3 fixed: trailing-stop ratchet publication (2026-09-24)

Branch `claude/f3-trailing-stop`, based on integration `72870ca9`; not yet integrated. When a
print moves a pending trailing stop's level, `MatchingEngine.updateWatermark` now emits an
unsolicited order update (FLAG_RESTING_UPDATE, as peg reprices do) carrying the new level, and
stamps the updated time from the sequenced event time. A watermark move that leaves the rounded
level unchanged emits nothing. FR-OT19 now specifies this. The read-model code is unchanged; it
already mapped stopPrice from every update and orders them by (consensusSequence, outputOrdinal).

Validation:
- Source: TrailingStopRatchetEgressTest (5) and TrailingStopRatchetReplayTest (2) cover buy/sell,
  amount/bps, no-emission cases, full-log replay, and snapshot plus tail with identical egress
  bytes and digest. They fail 6 of 7 without the fix and pass 7 of 7 with it. Full matcher suite:
  589 tests, 0 failures (6 existing skips), with all 13 tasks executed, including the five
  allocation gates and both Epsilon no-GC gates.
- Real DB: TrailingStopProjectionIT on MariaDB with the RI-06 migration, 2/2 (two ratchets in one
  command, a redelivered ratchet cannot rewind, legacy arrival order). Trade-processor unit
  tests: 98/98.
- Local-live: on a disposable 3-member kind rig, the acceptance exercise matched 31/31 with
  VERDICT PASS (exit 0), twice. A live buy trail published 102, 99, 97.5 and then 96; the
  already-open console blotter updated to "stop level (current) 96" without a reload.

Deployment note: this changes the output stream (and output ordinals) of commands that ratchet.
Roll it only with a fresh epoch, never into a mixed-version cluster. No latency, GKE or failover
claim.
