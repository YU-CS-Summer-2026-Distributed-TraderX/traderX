# YU11 transport evidence — 2026-07-17

This file preserves every decision-relevant transport, allocation, recovery, and failover
measurement recovered from the codeX YU11 session. It deliberately includes failed diagnostics,
superseded runs, and external comparator data. A number appearing here is not necessarily a ship
result; provenance and confidence are stated for each group.

## Bottom line

> **Superseded 2026-07-17 (post-engagement):** the Aeron-vs-NATS transport throughput A/B now
> exists — see [Post-engagement Aeron Phase-0 results](#post-engagement-aeron-phase-0-results--2026-07-17)
> at the end of this file. Percentile latency, durable-ACK cost, and booked-order E2E remain
> unmeasured. The paragraph below is preserved as the accurate state at engagement end.

There is **no measured YU11 Aeron throughput or percentile-latency result**. The planned
`run-yu11-aeron-transport.sh` harness was documented but never implemented or run. Runtime work
did not clear the replay/rejoin and failover correctness gates, so no honest Aeron events/s,
booked-orders/s, p50, p95, or p99 comparison exists.

The only throughput comparators available are the earlier File-backed NATS Phase-0 transport
measurements and a separately supplied GKE booked-order baseline. YU11 itself produced exact-zero
allocation evidence and runtime timing evidence, but not a transport-rate result.

## Environment and identities

### Local Phase-0 NATS comparator

- Worktree: `/Users/yaakov/dev/lmax/traderX-blp-ha-demo`
- Branch/baseline: `YU02-lmax-kubernetes-blp-ha` at
  `643aa9ea9e60a370fc69ffe5d12ce04599f5db10`
- Host: Apple arm64, macOS 15.7.7 (24G720), Docker Desktop 29.6.1, OpenJDK 25.0.1
- Runtime: isolated local NATS container, not Compose or kind
- NATS: 2.10.29 (`nats:2.10-alpine`,
  digest `sha256:b83efabe3e7def1e0a4a31ec6e078999bb17c80363f881df35edc70fcb6bb927`)
- JetStream: File storage at `/data/jetstream`, one replica, 24-hour max age, 120-second sync
  interval
- Record/rings: 64-byte input record, 65,536 input ring, 65,536-event warm-up
- Replication: 256 publish/in-flight cap; 1,024-record journal force batch
- Sampling: three runs per mode, 30-second producer window; HA elapsed includes complete primary
  and follower drain
- Confidence: **high for this exact local harness; low for extrapolation to booked orders or GKE**

### YU11 local runtime

- Worktree/branch: `/Users/yaakov/dev/lmax/traderX-YU11-aeron-replication`,
  `YU11-aeron-replication`
- Cluster: dedicated multi-node kind cluster `kind-traderx-yu11-aeron`, one control plane plus two
  workers; the shared `kind-traderx-state-014` cluster was not modified
- Runtime: two order-matcher pods, one Archiving Media Driver sidecar per pod, MariaDB, NATS, and
  the inherited state/control services needed for readiness
- Matcher image used for the final MDC runtime:
  `traderx/order-matcher:yu11-aeron-replication`,
  image ID `sha256:79878fd9ada0de04f3aea8b946695686f3db97cbf31a7c06bfc6f33dc5f91a3b`
- Reused sidecar image:
  `traderx/aeron-replication-sidecar:yu11-aeron-replication`,
  local image ID `sha256:ba183e9d8a98419e44daaf921061ce7545bf8a7e3af8fc80f27b99df7c83a359`
- Sidecar runtime tag:
  `traderx-yu11-registry:5000/traderx/aeron-replication-sidecar:kind-20260717024618`,
  pulled digest `sha256:285e6fc757361f75d49e93e0767bfab9d61b3089bd5b31854ce4ea673e6f2595`
- Confidence: **high for observed logs and exact image identity; constrained-host timings are not
  production estimates**

## File-backed NATS transport comparator

These numbers predate the YU11 implementation but are the governing transport baseline inherited
by YU11. They exercise the real NATS replicator, real follower injector, File-backed JetStream,
and both journals. They do not include HTTP, risk, matching, output projection, or database work.

### Before the NATS allocation fix — on-ring ACK

| Mode | Run | Events | Elapsed seconds | Events/s | Stream messages |
|---|---:|---:|---:|---:|---:|
| Single journaled control | 1 | 143,304,704 | 30.017 | 4,774,045 | N/A |
| Single journaled control | 2 | 114,053,376 | 30.022 | 3,799,050 | N/A |
| Single journaled control | 3 | 122,447,360 | 30.013 | 4,079,826 | N/A |
| HA File/on-ring | 1 | 328,192 | 31.217 | 10,513 | 393,728 |
| HA File/on-ring | 2 | 393,728 | 34.865 | 11,293 | 459,264 |
| HA File/on-ring | 3 | 329,216 | 34.348 | 9,585 | 394,752 |
| **Mean** | | | | **4,217,640 control / 10,464 HA** | |

### After the NATS allocation fix — on-ring ACK

| Mode | Run | Events | Elapsed seconds | Events/s | Stream messages |
|---|---:|---:|---:|---:|---:|
| Single journaled control | 1 | 167,501,568 | 30.020 | 5,579,690 | N/A |
| Single journaled control | 2 | 117,698,048 | 30.012 | 3,921,650 | N/A |
| Single journaled control | 3 | 114,007,296 | 30.013 | 3,798,539 | N/A |
| HA File/on-ring | 1 | 328,192 | 31.329 | 10,476 | 393,728 |
| HA File/on-ring | 2 | 394,496 | 35.783 | 11,025 | 460,032 |
| HA File/on-ring | 3 | 328,704 | 32.286 | 10,181 | 394,240 |
| **Mean** | | | | **4,433,293 control / 10,561 HA** | |

The post-fix HA mean is 0.9% above the pre-fix HA mean. That is inside observed host variance and
was not claimed as a throughput improvement.

### After the NATS allocation fix — durable ACK proxy

| Mode | Run | Events | Elapsed seconds | Events/s | Stream messages |
|---|---:|---:|---:|---:|---:|
| Single journaled control | 1 | 111,002,880 | 30.008 | 3,699,115 | N/A |
| Single journaled control | 2 | 118,193,920 | 30.018 | 3,937,383 | N/A |
| Single journaled control | 3 | 122,445,312 | 30.033 | 4,077,089 | N/A |
| HA File/durable | 1 | 328,192 | 31.473 | 10,428 | 393,728 |
| HA File/durable | 2 | 328,704 | 33.467 | 9,822 | 394,240 |
| HA File/durable | 3 | 328,704 | 31.377 | 10,476 | 394,240 |
| **Mean** | | | | **3,904,529 control / 10,242 HA** | |

The durable proxy is 3.0% below the post-fix on-ring HA mean. The proxy waited through follower
apply, not only the narrower post-force journal watermark that YU11 later exposed. Treat 3.0% as a
local upper-bound indication, not a YU11 durable-Aeron result.

## File-backed NATS allocation comparator

Parameters: 65,536-event warm-up, 131,072 measured events, live File-backed JetStream, real
`NatsJournalReplicator`.

| Version | Allocated bytes | Bytes/event | Result |
|---|---:|---:|---|
| Before fixed payload/future ring | 218,812,336 | 1,669 | Failed zero-byte budget |
| After fixed 256-slot payload/future ring | 208,324,352 | 1,589 | Passed 1,620 B/event budget |
| Repeat after fix | not retained | 1,591 | Passed 1,620 B/event budget |

The measured reduction was 80 B/event, or 4.8%. The remaining allocation is in jnats 2.20.5's
per-publish request/future graph. Confidence is high for the harness; the 1,620 B/event value is a
trend budget, not an exact-zero claim.

## Supplied GKE booked-order comparator

This was measured by the other lane and supplied in the MDC unblock. codeX did not run or modify
GKE. It is included because it recalibrates the YU11 go/no-go.

- Cluster: `traderx-lmax`, GKE `us-east1-b`
- Capacity: `blp-pool` scaled 1→2 `c2-standard-4` nodes; default pool at 3 nodes
- Matcher image: `ci-dd0421d`
- Client: in-cluster `bench-runner`, no port-forward
- Parameters: account 11413, alternating sides, quantity 1, limit 190, tickers JPM/COF,
  batch 1,000, concurrency 48, 30 seconds, three runs
- Result integrity: zero failed submissions in every run

| Mode | Run 1 | Run 2 | Run 3 | Mean booked/s |
|---|---:|---:|---:|---:|
| Single BLP, replication off | 21,770 | 15,429 | 10,505 | 15,901 |
| HA, File-backed NATS | 10,100 | 9,290 | 3,651 | 7,680 |

HA was approximately 46% of single BLP on the cleanest run (10,100 / 21,770) and approximately
48% by the rounded means. Confidence is **medium**: the within-series decline is large because the
book was not reset, single ran before HA, and the HA mean is probably understated. Run 1 versus
run 1 is the least contaminated comparison. The historical 35k absolute ship gate is invalid on
this risk-screened basis because even single BLP measured only 15.9–21.8k.

## Inherited allocation-gate investigation

This work happened immediately before the YU11 transport gate and determined how the YU11 gate
had to be isolated. It is preserved because the raw values otherwise disappear.

| Profile | Observed allocation | Interpretation |
|---|---:|---|
| Full suite, both inherited gate methods | 7,500,000 B on BLP thread | Cross-test/JIT/TLAB interference |
| Later full suite, risk method | 7,500,072 B on BLP thread | Same interference plus one 72-byte rematerialization |
| Isolated non-risk method | 0 B | Passed |
| Isolated risk method, JDK 25 | 72 B on producer thread | One scalar-replaced 72-byte `InputEvent` rematerialized during deoptimization |
| Other isolated diagnostic | 72 B on journaler thread | Same one-time compiler/accounting signature moved with compilation profile |

The state targets Java 21, while the first runs used JDK 25. JDK changes moved where the 72-byte
counter appeared but did not turn the 7.5 MB full-suite value into a real hot-loop allocation.
No-TLAB JFR tied the 72-byte increment to C2 escape-analysis rematerialization. Running each gate
in a fresh JVM with `-Xbatch` made the inherited gates deterministic; the owner-layer note records
10/10 passing executions under that profile. Confidence is high that 7.5 MB was suite
interference and 72 B was a compiler accounting artifact, not an every-event allocation.

## YU11 Aeron allocation-gate evolution

All runs used an embedded shared-threading Media Driver and Aeron IPC, a 65,536-slot Disruptor
input ring, ON_RING ACK, a real claimed SBE encode on the primary side, and real follower
poll/decode/ring injection. Thread allocation was measured with
`com.sun.management.ThreadMXBean`.

### Failed diagnostics

| Run/configuration | Primary delta | Follower delta | Outcome |
|---|---:|---:|---|
| 50,000 warm-up / 200,000 measured; original test ran publisher on JUnit thread | 272 B | not reached | Failed |
| Exact repeat of the above | 272 B | not reached | Failed identically |
| 50,000 / 200,000 after persistent dedicated publisher thread | 0 B | 11,424 B | Failed |
| Exact repeat/JFR form | 0 B | 11,424 B | Failed identically |
| 250,000 warm-up / 1,000,000 measured | 656 B | not reached | Failed |
| Fresh-JVM run after an earlier zero pass | 208 B | not reached | Failed nondeterministically |
| Direct-C2 compiler controls, first run | 0 B / 0 B | 0 B / 0 B | Passed once |
| Direct-C2 compiler controls, fresh rerun | nonzero, exact value not preserved | not reached | Failed |

No-TLAB JFR diagnostics showed no allocation event on the follower hot loop corresponding to the
11,424-byte ThreadMX delta. Deoptimization records appeared in Aeron `Image.poll`, Agrona
Unsafe/SBE access, ACK encode, and the Disruptor sequencer. One overloaded diagnostic also entered
cold strict-timeout error formatting; moving that formatting to a cold helper removed the only
real code-path allocation found. Subsequent recordings showed the remaining target-thread
counters as one-time compiler rematerialization, not repeating allocation events.

### Retained exact-zero gate

- Minimum warm-up: 250,000 events.
- Measurement window: 1,000,000 events.
- Maximum windows: four.
- Pass condition: **two consecutive windows** with exactly 0 B on both primary and follower
  threads. A nonzero stabilization window resets the streak.
- JVM: fresh Gradle test worker with `-Xbatch -XX:-TieredCompilation
  -XX:CompileThreshold=10000`.
- Two fresh `--rerun-tasks` executions passed in 12 seconds each.
- The gate subsequently passed as a dependency of full `test` runs in 39 s, 29 s, 36 s, 26 s,
  another 39 s clean rebuild, 36 s after MDC regeneration, and 57 s in final MDC verification.

Confidence is **high for exact-zero steady state under this explicit compiler/test profile**.
It is not a throughput test and says nothing about sidecar allocations.

## `noGcTest`

The final post-MDC run was:

```text
./gradlew --no-daemon noGcTest
> Task :riskNoGcTest
> Task :noGcTest
BUILD SUCCESSFUL in 26s
```

Each inherited Epsilon gate used a 250,000-event warm-up and 3,000,000 measured events with
`-XX:+UseEpsilonGC`, 256 MiB fixed heap, `AlwaysPreTouch`, and `-Xbatch`. Both base and risk hot
paths passed exact zero. The Aeron transport gate is **not** wired into `noGcTest`; it has its own
ThreadMX exact-zero task. No events/s or latency value is emitted by these gates.

## Local replay and merge observations

These are correctness/timing observations, not throughput results.

| Design/run | Observation | Confidence |
|---|---|---|
| Early outbound-spy recording | Publication position stayed at 96 while authoritative input sequence advanced to 3 | High; proved absent peer did not keep the UDP publication connected |
| Early working MDC prototype | Archive retained business sequences 0–3; restart targeted recording position 384; three offline orders replayed; a new live order then replicated | High for that controlled local scenario; superseded because MDC was incorrectly believed deferred |
| Two same-session publications | The same controlled loss/replay script passed | Low as architecture proof; kind later disproved it under asymmetric connection timing |
| Split publication, kind | Sequence 0 reached Archive while live UDP was NOT_CONNECTED; sequence 1 arrived live and follower raised `FAULT_GAP=3` | High |
| Split publication replay chase | `ReplayMerge` stayed `ATTEMPT_LIVE_JOIN`; observed position grew from 3,456 at 02:58:18 to about 25,536 at 03:00:16 without merging | High; approximately 22,080 bytes in 118 seconds, a chase symptom, not useful replay throughput |
| Initial MDC kind pair | Catch-up began 03:50:23.084 and readiness was signalled 03:50:23.965 | High: 881 ms for an empty-tail/local constrained-kind catch-up |

The 881 ms sample must not be treated as p50/p95. It is one empty/near-empty catch-up on a heavily
constrained local Docker VM.

## Failover and replacement-follower timing

### Graceful deletion

The pre-pivot HA script used ordinary pod deletion. It measured:

```text
[error] default Lease failover 9502ms exceeds the 3000ms gate
```

Relevant log times:

- 03:51:24.878 — follower logged holder `Terminating` past the guard.
- 03:51:25.095 — alternate logged promotion to primary.
- 03:51:25.101 — engine role transition began.
- 03:51:25.656 — replicator delegate swapped to Aeron.
- 03:51:25.989 — primary routing label patch completed.

The 9,502 ms measurement starts at deletion and ends when the alternate primary label is observed.
It is not “time to accepted order.” Most of the delay was the old pod remaining in `Terminating`;
the in-code terminating guard itself is 1 second.

Confidence is high for the script measurement, low as a crash-failover estimate because it
measured graceful Kubernetes termination.

### Force deletion

The script was changed to `--grace-period=0 --force --wait=false` during the MDC pivot. **No
completed post-pivot force-delete timing was obtained.** Therefore there is no valid force-delete
events/s, latency, or failover result to report. The 9,502 ms value must not be relabelled as a
force-delete result.

### Replacement follower

- Local journal replay: 1,498 events.
- New leader epoch: 36.
- Archive catch-up start: 03:52:12.410.
- Advertised primary business input sequence: 1,464.
- Advertised primary recording position: 41,952.
- Checkpoint input sequence used: -1 (no usable checkpoint).
- Fault: 03:52:13.055, `FAULT_GAP=3`, `lastInputSeq=-1`.
- Time from catch-up start to fault: 645 ms.

This is high-confidence evidence of a real cold/replacement-follower bootstrap gap. It is not a
ReplayMerge throughput result.

## Fast-witness numbers

- Production/default configured witness TTL: 50 ms.
- An initial focused test used a synthetic 20 ms TTL and was nondeterministic because AssertJ/JIT
  work could consume the TTL; only the test TTL was widened.
- No kind or Compose 30–60 ms end-to-end fast-witness failover measurement was completed.

## Verification durations

Durations are included only to distinguish completed checks from intended checks; they are not
performance claims:

- Final post-MDC order-matcher `test`: 57 s, including Aeron, base, and risk allocation gates.
- Final post-MDC inherited `noGcTest`: 26 s.
- Final post-MDC sidecar `test installDist`: 8 s.
- Repeated final Aeron allocation-only runs: 12 s and 12 s.

## What remains unmeasured

- ~~Aeron offered/s, follower-received/s, journaled/s, or applied/s.~~ Measured 2026-07-17; see
  the post-engagement section below.
- Aeron ACK p50/p95/p99 latency.
- ~~Aeron versus File-NATS three-run local A/B.~~ Measured 2026-07-17; see below.
- YU11 booked-order rate in kind or GKE.
- Durable Aeron ACK cost versus on-ring.
- Replay rate for a bounded retained backlog.
- Force-delete crash failover time to accepted traffic.
- Fast-witness detection/claim/admission phase times.
- CPU, disk growth, retransmit, induced-loss, and Archive-lag comparisons.

Any YU11 go/no-go must treat these as missing evidence, not zeroes and not inferred wins.

## Post-engagement Aeron Phase-0 results — 2026-07-17

Measured after the codeX engagement ended, on the fable lane, with the new
`AeronReplicationPhase0Test` (owner layer, commit `d2530ed`), run via
`scripts/bench/run-aeron-replication-phase0.sh`. The harness is matched line-for-line to
`NatsReplicationPhase0Test`: 65,536 input ring, 1,024-record journal force batch, 256 publish
batch, 65,536-event warm-up, identical event fill, 30-second timed window whose elapsed time
includes the complete primary and follower drain, three runs per tier, fresh journals and a fresh
embedded Media Driver per run, ON_RING ACK, `STRICT` failure policy (any silent loss fails the
run), and a zero-fault assertion on the follower after every run.

Tiers:

- **single-control** — journaler + ReplicatorStub. Same-day journaling ceiling on this host state.
- **aeron-ipc** — one IPC publication to the real `AeronReplicationFollower` (no fan-out;
  transport upper bound).
- **aeron-mdc-udp** — the YU11 production topology: one `control-mode=manual` MDC
  `ExclusivePublication` fanned out to a drained archive destination and the real follower, both
  over UDP loopback, ACK returning over UDP loopback. The archive destination is drained by a live
  subscriber without disk recording (in production the Archive write happens in the sidecar
  process, coupled to the matcher only through flow control, which the drain models). Both
  replicated tiers include the real primary and follower journal fsync paths — the durability
  authority in this design.

| Tier | Run 1 | Run 2 | Run 3 | Mean events/s |
|---|---:|---:|---:|---:|
| single-control (journal only) | 2,661,726 | 2,973,514 | 2,519,733 | **2,718,324** |
| aeron-ipc | 1,252,696 | 1,189,034 | 1,234,972 | **1,225,567** |
| aeron-mdc-udp (production topology) | 515,693 | 531,679 | 514,187 | **520,520** |

Comparators from earlier in this file (same methodology, same host class):
File-backed NATS HA on-ring mean **10,561** events/s; its same-day control 4,433,293 events/s.

Readings:

- **Aeron production topology ≈ 49× the File-backed NATS HA transport** (520,520 vs 10,561).
- **Replication tax collapses.** NATS HA ran at 0.24% of its same-day control; Aeron MDC runs at
  19.1% of its same-day control (~5.2× cost), and Aeron IPC at 45%.
- The same-day control here (2.72M/s) is lower than the NATS-day control (3.9–4.4M/s): the host
  was also running two kind clusters, including the crash-looping YU11 replacement pod, during
  measurement. Same-day control keeps the comparison honest; if anything the Aeron numbers are
  understated.
- At 520k events/s the replication transport has ~25× headroom over the best measured end-to-end
  booked-order rate (21,770/s single-BLP on GKE), so with this transport the HA mode's bottleneck
  should return to the edges (REST ingress, DB projector) rather than replication. That is an
  inference, not a measurement — the E2E booked-order proof still requires the failover
  correctness work.
- Confidence: **high for this harness on this host** (runs within ±2% on the MDC tier); it says
  nothing about ACK latency percentiles, durable ACK mode, loss/partition behaviour, or
  cross-node network cost. Raw logs: `scripts/bench/results/aeron-replication-phase0-20260717-004427/`.

The go/no-go implication: the ship gate "≥25% above the File-backed NATS HA baseline" is
transport-level satisfied by a factor of ~49, and the performance premise of YU11 is confirmed.
What stands between these numbers and a deployable YU11 is correctness, not speed: the
cold-follower rejoin gap, the stale-hello `AUTH_CLOCK` terminal fault (found post-engagement on
the parked cluster: a restarted follower receives the primary's original hello replayed from the
retained control stream, rejects it for >30s clock skew, and terminally faults before ever
reaching Archive catch-up), and the YU02-layer graceful-shutdown Lease release.
