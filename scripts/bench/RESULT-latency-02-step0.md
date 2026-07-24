# LATENCY-02 Step 0 — the decomposition on lowpark, and the verdict on the flat 1 ms

> Measurement only, per the brief. **The headline is that the "flat 1 ms commit" never existed.** It was
> a censored histogram. The real consensus commit round-trip on lowpark is **~220 µs**, it has a normal
> continuous distribution, and it is **wire/disk-bound, not clock-quantized**. With the measurement fixed
> the per-order budget **closes to the microsecond** — there is no unaccounted millisecond.
> Rig: 3 c4d-standard-8 members + 3 gateways + 3 generator pods on GKE us-east1-b, binary per-order
> reject path, `CLUSTER_IDLE_STRATEGY=lowpark`, warm, JFR off, mask=0, coordinated-omission-safe.
> Image `cluster-node:yu13-latency02`. See `RESULT-latency-decomposition.md` (LATENCY-01) for the method.

## 1. The lead, resolved: the 1 ms was the measurement, not the system

LATENCY-02 flagged the post-lowpark commit — "a flat 1000 µs from p50 all the way to p99.9" — as the
signature of a timer, tick, or batching interval. It is the signature of a **filter**:

```java
// LeaderApplyLatency.recordCommitMillis, before
if (ms > 0 && ms < 60_000) {          // ms == 0 silently discarded
```

The commit was measured as a delta between two epoch-**millisecond** reads. On the pre-lowpark backoff
engine the true commit was ~2 ms, so discarding zero cost nothing. Once lowpark pulled the commit
**under one millisecond**, that filter deleted the fast majority and kept only the samples that happened
to straddle a millisecond boundary — every one of which reads exactly `1`. A histogram containing
nothing but 1s reports p50 = p99 = p99.9 = 1000 µs.

**Reproduced live, on demand.** Same rig, same 60k load, `CLUSTER_CLOCK` unset (millisecond clock) but
with the zeros now recorded:

| clock | commit mean | p50 | p99 | p99.9 | count |
|---|---:|---:|---:|---:|---:|
| **millisecond** (zeros kept) | **230.6 µs** | 0.000 | 1000.4 | 1000.4 | 1,800,156 |
| **nanosecond** | **236.0 µs** | 235.9 | 372.0 | 581.6 | 1,800,156 |

Read the millisecond row and the artifact is plain: **p50 is 0** and the only non-zero percentiles are
the 1000.4 µs quantum. Delete the zeros — which is what the old code did — and every surviving sample is
1000 µs, at every percentile. That is the entire "flat millisecond."

The two clocks are **independent measurements of the same quantity and they agree to 2.3 %** (230.6 vs
236.0 µs). Millisecond-truncated differencing is unbiased under uniform phase, so once the zeros are
recorded the coarse clock still recovers the true mean; the nanosecond clock adds the distribution.
`commit count == apply count` in both, so nothing is censored any more.

## 2. The load sweep — the brief's falsifiable test

Offered rate swept 6× at a fixed rig. Ground truth from the leader's `/latency` and `/metrics`.

| offered | applied/s | commit mean | p50 | p99 | p99.9 | apply p50 | leader CPU | client p99/p50 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 15k | 12,592 | 218.5 µs | 209.5 | 356.1 | 652.3 | 0.57 µs | 0.31 c | 1.47 |
| 30k | 26,154 | 226.7 | 225.8 | 367.1 | 518.9 | 0.48 | 0.32 c | — |
| 45k | 39,980 | 231.3 | 230.1 | 369.2 | 645.1 | 0.47 | 0.33 c | 1.43 |
| 60k | 50,086 | 236.0 | 235.9 | 372.0 | 581.6 | 0.45 | 0.36 c | 1.49 |
| 90k | 78,301 | 195.6 | 192.8 | 322.6 | 548.4 | 0.44 | 0.48 c | 36.7 ⚠ |

**The commit does not move with load** — 193 to 236 µs across a 6× range, no monotonic trend, and it is
*lowest* at the highest rate. The 90k row is saturated **at the gateway** (client p99 35.7 ms, gateway
owner-queue p99.9 26.7 ms) and is not a system-latency point; it is included because it is informative
that even with the gateway queueing 26 ms, **the commit is unchanged**. The consensus path is nowhere
near its limit at any of these rates.

## 3. Verdict: WIRE/DISK-BOUND, not clock-quantized

The brief's test was "a number that refuses to move with load is a timer." That inference does not
discriminate, and this is the load-bearing correction: **an unsaturated physical round-trip is also flat
with load.** Constancy separates "saturated queue" from "not saturated"; it says nothing about timer vs
wire. What discriminates is the **distribution**, and the distribution is continuous:

- **p50 209 µs → p99 356 µs → p99.9 652 µs → max ~5.8 ms.** A timer, tick, or batching interval
  quantizes: samples land on discrete multiples of the period. These do not. They are a smooth
  right-skewed latency distribution with a jitter tail — the shape of a network-plus-disk round trip.
- **~220 µs is physically the right size** for what the segment actually contains: leader → two
  followers over same-zone UDP (~50–100 µs/leg), a per-member Archive disk record, the quorum ack back,
  the commit-position advance, and delivery to the service container.
- **No Aeron interval is near 220 µs.** This deployment runs heartbeat interval 50 ms, election status
  interval 25 ms, timer-wheel resolution in milliseconds. There is no 220 µs clock to be quantized by.

The old flat 1000 µs *was* clock-quantized — by `System.currentTimeMillis()`, in the instrument.

## 4. The decomposition on lowpark — the budget closes

At 15k offered (the cleanest unsaturated point, client p99/p50 = 1.47), nanosecond clock, mask=0:

| segment | p50 | share | how obtained |
|---|---:|---:|---|
| client ↔ gateway wire | **321.2 µs** | **37 %** | client RTT − gateway total (one cross-host subtraction) |
| gateway decode | 0.06 µs | — | gateway clock |
| gateway owner-queue | 0.81 µs | — | gateway clock |
| gateway reply encode+write | 10.7 µs | 1.2 % | gateway clock |
| gateway wakeup slack | 36.8 µs | 4.2 % | gateway total − black box − the three above |
| **Aeron transport + ingress/egress poll** | **286.3 µs** | **33 %** | black box − commit − apply (residual) |
| **consensus commit round-trip** | **209.5 µs** | **24 %** | leader clock, single shared `NanosClusterClock` |
| apply / match | 0.57 µs | 0.07 % | leader `nanoTime` around `onEvent` + `drainOutputs` |
| **= client RTT (wire-to-wire)** | **866 µs** | 100 % | generator, intended-send schedule |

Sum of parts: 321.2 + 0.06 + 0.81 + 10.7 + 36.8 + 286.3 + 209.5 + 0.57 = **865.9 µs** vs a measured
client RTT of **866 µs**. Anchors: gateway total 544.8 µs, gateway cluster black box 496.4 µs, leader
commit 209.5 µs, apply 0.57 µs, leader CPU 0.31 cores. The budget also closes at 60k (481 + 11.1 + 26.2
+ 401.6 + 235.9 + 0.45 = 1156.3 vs 1156 µs measured).

**There is no missing millisecond.** The brief's "≈1.0 ms unaccounted" was two things: the commit row was
overstated ~5× by the censoring filter, and the summary table omitted two segments LATENCY-01 had already
itemized — the client↔gateway wire and the gateway wakeup residual. Correct the first and restore the
other two and the budget is exact.

## 5. The lever this selects

Ranked by measured share of the client round trip:

1. **Client ↔ gateway wire — 321 µs, 37 %, the single largest segment, and entirely outside the cluster.**
   This is a pod-to-pod hop between node pools plus the generator's own send/receive path. It was never
   on the consensus critical path at all. Attack this before anything member-side: co-locate or pin the
   client and gateway, and split the segment into true wire vs client-side scheduling (it is currently
   the one cross-host subtraction and so the least-decomposed number in the table).
2. **Aeron transport + ingress/egress poll — 286 µs, 33 %.** The members' ingress-pickup and egress-emit
   path, outside the committed window. This is what compact placement and the kernel-bypass ladder
   actually target.
3. **Consensus commit — 209 µs, 24 %.** Real, load-invariant, and physically sized. Archive
   `fileSyncLevel` / recording placement is the cheap dial here, since part of it is the per-member disk
   record on the quorum path.
4. apply — 0.57 µs. Still not the problem, and never has been.

**Compact placement is indicated** — but for the *whole* rig, not member-to-member alone: segments 1 and 2
are both transport and together are 70 % of the round trip.

**The consensus-model redesign door stays SHUT, and more firmly than before.** The commit is 24 % of the
round trip. Removing it *entirely* — giving up the correctness guarantee altogether — would buy at most
1.3×. Both cheaper transport levers are worth more and cost no guarantee.

## 6. `CLUSTER_CLOCK=nanos` as a lever — INCONCLUSIVE, do not ship on this evidence

The nanosecond clock was faster in every paired comparison (15k: 866 vs 1276 µs; 60k: 1156 vs 1471 µs),
which suggested Aeron's millisecond cached cluster clock might quantize the consensus module's own
scheduling. Four matched repeats at 15k do not support the claim:

| clock | client RTT p50, 4 runs | median |
|---|---|---:|
| nanos | 866, 889, 971, 1074 µs | 930 µs |
| ms | 929, 956, 1276, 1708 µs | 1116 µs |

The ranges overlap; a rank test on n=4 vs 4 is not significant. Direction is consistently favourable and
the commit itself is identical between arms (236 vs 231 µs — the difference, if real, is *outside* the
committed window), so it is worth revisiting with more samples. **For now `CLUSTER_CLOCK=nanos` is a
measurement instrument, not a shipped latency knob.** Run-to-run variance at fixed configuration is
~2×, which is itself the most important caveat on every single-run number in this campaign.

## 7. Instrumentation changes (commits `937cbef6`, `ebabc58f` — not pushed)

- **`recordCommitMillis` records `ms == 0`.** The one-character root cause. `dump()` now also reports
  `mean`, which is the load-bearing statistic on a quantized clock, and `commit count == apply count`
  makes any recurrence self-evident.
- **`CLUSTER_CLOCK=nanos`** → `NanosClusterClock`, a nanosecond `ClusterClock` over agrona's
  `OffsetEpochNanoClock`. Aeron's own `NanosecondClusterClock` reads `Instant.now()` and **allocates per
  sequenced message**, on the ConsensusModule hot path and again at apply; the no-GC gate (NGC-01) caught
  it. Allocating in order to measure latency puts GC jitter into the histogram being measured. The clock
  instance is **static and shared**, so both ends of the commit interval resolve to one object in one
  JVM and cannot disagree by a calibration offset — a genuine single-clock interval, not two clocks
  claiming the same epoch. All three allocation gates pass with instrumentation on.
- **The time unit is read at apply, not cached in `onStart`.** The container is told the cluster's time
  unit when it *joins the log*, which is after `onStart` runs. Caching it there left the service on the
  millisecond branch under a nanosecond clock, subtracting epoch-nanos from epoch-millis: every sample
  hugely negative, therefore dropped, commit count 0. Caught by a new `assertCommitSegmentIsSane` in
  `ThreeMemberClusterTest` (count > 0 and p99.9 < 1 s — both bounds together catch exactly a two-clock
  mismatch).
- **Determinism unaffected**: the service converts the timestamp to millis before it touches replicated
  state (a deterministic divide, identical on every member and on replay) and schedules no cluster
  timers. The full three-member gauntlet — snapshot, two failovers, disk-wiped member rejoin,
  cross-member state equality — passes under `CLUSTER_CLOCK=nanos`.

## 8. Honesty ledger

- **Run-to-run variance at fixed configuration is ~2× on client RTT p50** (see §6). Every single-run
  number here carries that. The commit figure is the exception and is solid: ~195–236 µs across ten runs
  spanning a 6× load range and both clocks.
- The **client↔gateway wire (321 µs)** is the one cross-host subtraction and absorbs the generator's own
  scheduling. It is a bound on "everything between the generator's send and the gateway's first
  timestamp", not a pure wire number — which is exactly why §5 puts *decomposing* it first.
- The **transport+poll (286 µs)** is a residual (black box − commit − apply) and so also absorbs the
  leader's ingress-arrival→sequenced gap.
- Reject path (unknown security → sequence → consensus → apply-reject, ~0.5 µs apply, no booking). Same
  consensus round-trip a booked order pays; only the apply differs.
- 15k–60k are unsaturated (client p99/p50 = 1.43–1.49). **90k is not** (36.7) and is reported only for
  the commit-invariance point.
- Leader CPU 0.31–0.48 cores of the 3-core pin across the sweep, confirming lowpark's ~0.34 c idle cost.
- The gateway ran mask=0 (every order). LATENCY-01's observer-effect check found instrumentation free at
  mask=0; the new leader-side path is additionally proven allocation-free by the NGC gates.
