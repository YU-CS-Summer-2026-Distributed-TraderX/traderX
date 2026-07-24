# LATENCY-03 — the latency↔throughput frontier: the window is not the dial we thought

> Config sweep on the LATENCY-02 rig, no rebuild, no hardware. **The brief's premise does not survive
> measurement: there is no ~1.1 ms of load-induced queueing to recover.** Client RTT p50 is essentially
> load-invariant from 15k to 75k on this rig. And the in-flight window is not a latency↔throughput
> trade in the direction assumed — going *shallower* destroys latency (up to 350×) while barely moving
> throughput, and the damage is **invisible to server-side metrics**.
> Rig: 3 c4d-standard-8 members + 3 gateways, `cluster-node:yu13-latency02`, `LATENCY_DECOMP=1
> CLUSTER_IDLE_STRATEGY=lowpark CLUSTER_CLOCK=nanos`, binary reject path, warm, JFR off, mask=0,
> coordinated-omission-safe. 3 repeats per point. See `RESULT-latency-02-step0.md`.

## The frontier (medians of 3 runs; RTT in µs; "range" is min–max across the 3)

| config | load | conns | applied/s | RTT p50 med | p50 range | RTT p99 med | commit mean | leader CPU |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline | 15k | 201 | 13,022 | 1055 | 952–1220 | 1,841 | 217.9 µs | 0.30 c |
| baseline | 45k | 201 | 39,438 | 1426 | 870–1439 | 1,974 | 224.5 µs | 0.34 c |
| baseline | 75k | 201 | 65,093 | **916** | 891–1165 | **17,223** | 203.5 µs | 0.45 c |
| shallow | 75k | 60 | 66,248 | **10,110** | 9644–10577 | 29,360 | 185.5 µs | 0.55 c |
| deep | 75k | 402 | 66,135 | 1544 | 1399–1648 | **2,461** | 226.3 µs | 0.37 c |
| deeper | 75k | 804 | 66,293 | 2375 | 2246–2421 | 4,331 | 222.4 µs | 0.40 c |
| deep | 45k | 402 | 39,627 | 1699 | 1576–1727 | 2,749 | 223.6 µs | 0.35 c |
| semaphore 256 | 75k | 402 | 63,705 | 1399 | 1364–1544 | 2,551 | 214.5 µs | 0.41 c |
| **semaphore 16** | 75k | 402 | 61,976 | **459,239** | 322k–618k | **9,972,364** | 197.6 µs | 0.54 c |

## 1. How much of the 1.1 ms is window depth? — **None of it, because there is no 1.1 ms**

The brief's table paired *my* 15k number (866 µs) against *LATENCY-01's* 75k number (~2.0 ms). Those come
from different rig configurations and different runs — exactly the cross-run comparison the ~2×
variance finding warns against. Measured properly, on one rig, with repeats:

**p50 medians: 1055 µs (15k) → 1426 µs (45k) → 916 µs (75k).** No trend. The 75k median is the *lowest*
of the three, and every range overlaps every other range. There is no ~1.1 ms of load-induced p50 growth
to attribute to anything.

Going shallow does not recover latency — it destroys it. At 75k, dropping 201 → 60 connections took p50
from 916 µs to **10,110 µs (11× worse)** while applied throughput went *up* slightly (65.1k → 66.2k). The
"deeper window buys throughput and pays in latency" model is simply not what this path does.

## 2. Where is the knee? — **In the tail, not in p50 or throughput**

- **Throughput has no knee up to 75k.** applied/offered holds at 85–88% at every load and every window
  depth. That shortfall is a constant funnel loss (gateway decoded < offered), not saturation — it does
  not grow with load, so 75k is not past a throughput knee on this rig.
- **p50 has no knee** (see §1).
- **The tail does.** At the default 201 connections, RTT p99 median goes 1,841 → 1,974 → **17,223 µs**
  from 15k → 45k → 75k. That is a ~9× jump between 45k and 75k, and it is **bimodal** (one repeat gave
  2,155 µs, two gave 17–21 ms). The gateway owner-queue p99 tracks it (up to 5.5 ms).

**So the knee is a tail knee, it sits between 45k and 75k, and its position depends on window depth** —
at 402 connections the 75k p99 median is 2,461 µs instead of 17,223 µs.

## 3. Is 2.0 ms a system property or a load artifact? — **Neither; it is not reproducible**

We could not reproduce ~2.0 ms at any load on this rig. Across 15k–75k at the default window, p50 sits in
a **0.87–1.44 ms** band with ~1.5× run-to-run spread inside it.

**What is honest to put on a slide:** *"per-order p50 under 1.5 ms sustained to 75k/s, p99 ~2 ms at a
correctly-sized window."* Defensible because every measured p50 median (916–1426 µs) and the tuned-window
p99 (2,461 µs) sit inside it, with repeats.

**What is not honest:** any single-point figure quoted to two significant figures, in either direction.
The spread is ~1.5–2× and a point estimate is below the noise floor. Quote the band.

## 4. Recommended default — **a floor on the window, not a shallow "latency mode"**

The brief anticipated a `latency-mode` (shallow window) / `throughput-mode` (deep window) pairing. **Do
not ship that.** The measurement says shallow is never the right answer on this path — it is
catastrophically worse at *both* ends.

The governing relation is Little's Law: in-flight `L = λ × W`. At 75k with W ≈ 1 ms the system needs
~70 orders in flight, spread across connections that each carry ~1 (the acceptor is synchronous). The
recommendation is therefore a **floor**, with a broad plateau above it and slow degradation past it:

- **Provision connections so no single connection accumulates backlog: roughly `λ × W` with headroom.**
  On this rig that is ~200–400 connections for 45–75k/s. Below it, latency collapses; above ~800 it
  degrades gently (p50 2,375 µs at 804 conns, as per-connection overhead at the gateway starts to bite).
- **`GATEWAY_MAX_INFLIGHT` is a safety valve, not a latency knob.** At 256 it is statistically
  indistinguishable from the 4096 default (p50 1399 vs 1544 µs, p99 2551 vs 2461 µs — all inside the
  spread), because the real working set is only `λ×W/gateways ≈ 35` per gateway. It does nothing until
  it binds, and when it binds it is a disaster (§5).
- **The idle strategy remains the real config lever**; `lowpark` is unchanged and still correct.

Within the 200–402 plateau the differences are at or below the noise floor and **the ordering does not
generalise across loads** — 402 conns beat 201 on the tail at 75k (2,461 vs 17,223 µs) but *lost* to it
at 45k (2,749 vs 1,974 µs). So the honest recommendation is the plateau, not a specific number.

## 5. The sharpest result: throttling in-flight moves the queue somewhere you cannot see it

`GATEWAY_MAX_INFLIGHT=16` (below the ~35 working set, so genuinely binding) at 75k:

| | value |
|---|---|
| client RTT p50 | **459 ms** (322–618 ms) — ~350× worse than the 4096 default |
| client RTT p99 | **~10 seconds** |
| applied throughput | 61,976/s — only **6% below** the 66,135/s at the deep window |
| max in-flight per connection | 1,766–2,109 (vs 12 at the deep window) |
| **gateway owner-queue p99** | **86 µs** — *lower* than the healthy config's 651 µs |
| commit mean | 197.6 µs — unchanged |

Throttling the window did not remove the queue. It **relocated** it, out of the gateway and into the
clients' own send backlogs, where it grew three orders of magnitude larger — and every server-side metric
looked *healthier* while it happened. Gateway queue p99 fell, leader CPU was normal, commit was pristine,
throughput was down 6%. A dashboard built on server-side latency would have shown a green system while
clients waited ten seconds.

**Operational takeaway: client-observed RTT is not optional instrumentation.** No combination of
member/gateway metrics on this rig could have detected this failure mode.

## 6. The commit is load-invariant — reconfirmed, hard

Across every point in this sweep — six load/window combinations, a binding semaphore, and runs where
client latency reached ten seconds — the consensus commit mean stayed in **185–227 µs**:

| condition | commit mean |
|---|---:|
| 15k, default window | 217.9 µs |
| 75k, default window | 203.5 µs |
| 75k, starved window (60 conns) | 185.5 µs |
| 75k, semaphore 16 (client p50 459 ms) | **197.6 µs** |

The consensus path is **completely insulated** from everything that happens upstream of it. A client
seeing 459 ms was served by a cluster committing in 198 µs. This is the strongest evidence yet that the
consensus-model question is closed: it is not the bottleneck under any load or window depth we can
construct, and per the scope guard it stays shut.

## 7. Honesty ledger

- **Every number here is a median of 3 runs with the range printed.** Run-to-run spread is ~1.5–2× on
  p50 at fixed config, so differences smaller than that are reported as indistinguishable, not as wins.
  Specifically: 201 vs 402 connections, and semaphore 256 vs 4096, are **not** separated by this data.
- The **conclusions that survive the noise** are the ones with large effects: shallow-window collapse
  (11× and 350×), the 75k tail knee at the default window (~9×), the tail improvement at 402 conns at
  75k (7×), and commit load-invariance (flat within ±10% across a 350× swing in client latency).
- **The window knob here is the load generator's connection count**, which is a client-side property, not
  a server config. The server-side analogue is `GATEWAY_MAX_INFLIGHT`, tested separately in §5. A real
  deployment does not choose its clients' connection count — which is why the recommendation is framed as
  "provision for `λ×W`" and as a warning about capping, not as a server tuning default.
- `applied/s` is the leader's `nextOrderRef` delta (ground truth), never a booked counter.
- One 45k/402-conn repeat produced a 96.8 ms p99 against 2.5–2.7 ms in its siblings; it is in the median
  as measured and is why that row's p99 is reported as a median rather than a mean.
- Reject path (unknown security), as in LATENCY-01/02, so the apply is ~0.5 µs and only the consensus
  round-trip and transport are exercised.
- Not tested: window depth below 60 connections at loads under 75k, and loads above 90k. Compact
  placement (the 286 µs Aeron legs) is deliberately untouched — it needs a fresh bring-up and is the next
  brief.
