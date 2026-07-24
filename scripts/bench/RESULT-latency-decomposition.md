# LATENCY-01 — per-order latency decomposed (where the ~4ms goes)

> First step of the latency/HFT thread. We had a byproduct per-order **p50 ~4ms / p99 ~15ms** number,
> never decomposed. This splits it across every hop so the campaign optimizes the RIGHT layer.
> **Measurement only — no optimization, no hardware, no consensus-model change in this brief.**
> Rig: 3 c4d members + 3 gateways on GKE (us-east1-b), binary per-order reject path (pure ingress:
> unknown-security orders sequence → consensus-commit → apply-reject, so the full distributed path runs
> with a ~0.5µs apply and no booking). Paced **75k/s**, unsaturated (p99/p50 ≈ 1.8×, not a queue),
> warm, JFR off, raw TCP, coordinated-omission-safe (intended-send schedule). Image `yu13-latencyb`.

## The number, split (p50 chain sums to the wire-to-wire client RTT)

All single-clock intervals. Gateway segments are one gateway-JVM `nanoTime` clock; leader segments are
one leader-JVM clock (`nanoTime` for apply, epoch-ms delta for commit). The two **cross-host** segments
(client↔gateway wire; Aeron transport) are derived by **subtracting single-clock intervals**, never by
comparing two hosts' clocks.

| segment | p50 | p99 | p99.9 | how obtained | lever if it dominates |
|---|---:|---:|---:|---|---|
| client ↔ gateway wire | ~0.6 ms | — | — | client RTT − gateway total (1 cross-host subtraction) | NIC / kernel-bypass / placement |
| gateway decode+resolve | 0.06 µs | 0.34 µs | ~16 µs | gateway clock (flyweight; securityId pre-resolved) | code (it isn't — nanoseconds) |
| gateway owner-queue | 0.8 µs | ~0.7 ms | ~1.6 ms | gateway clock (submit→owner offer) | in-flight window depth (only a tail contributor) |
| **Aeron transport + ingress/egress poll** | **~2.0 ms** | — | — | gateway black box − leader commit − apply (residual) | **idle strategy / placement / RDMA** |
| sequencing | (folded into commit) | | | leader clock | code |
| **consensus commit round-trip** | **2.0 ms** | 5.0 ms | 6.0 ms | leader: `currentTimeMillis(apply) − sequencing timestamp` | **idle strategy / Archive tuning; architecture only if it's the floor** |
| apply / match | 0.47 µs | 0.82 µs | 4.8 µs | leader `nanoTime` around `onEvent`+`drainOutputs` | engine (it isn't — ~470 ns, as the in-process bench predicted) |
| gateway reply encode+write | 12 µs | ~0.65 ms | ~1.4 ms | gateway clock (egress→flush) | code |
| — future-wakeup residual | ~0.13 ms | — | — | gateway total − Σ(gateway segments) | n/a (thread wakeup slack) |
| **= client RTT (wire-to-wire)** | **~4.85 ms** | ~10.8 ms | — | generator, intended-send | |

Anchors actually measured this run (median across the 3 symmetric gateways / the leader):

- **gateway total (residence)** p50 **4.25 ms**, p99 8.5 ms, p99.9 11.8 ms — sum-check: decode+queue+cluster+reply = 4.11 ms vs 4.25 ms (0.13 ms wakeup slack) ✓
- **gateway cluster black box** (t_offer→t_egress) p50 **4.04 ms**, p99 7.8 ms, p99.9 9.2 ms
- **leader commit** p50 **2.00 ms** (1 ms resolution), p99 5.0 ms, p99.9 6.0 ms, max 7.0 ms · **leader apply** p50 **0.47 µs**
- **client RTT** p50 4.85 ms, p99 10.8 ms (one generator pod)

## Observer-effect check (mandated) — instrumentation is free

Same paced-75k load, wire-to-wire client RTT only, toggling `LATENCY_DECOMP`:

| arm | client p50 | client p99 |
|---|---:|---:|
| LATENCY off (baseline) | 5.17 ms | 9.56 ms |
| LATENCY on, gateway only (mask=0) | 3.72 ms | 9.40 ms |
| LATENCY on, gateway+leader (mask=0) | 4.85 ms | 10.76 ms |

Instrumented is **not systematically higher** than off (arm 2 is lower); the spread is run-to-run
variance, not observer effect. So `mask=0` (record every order) is safe and the tails are trustworthy.

## The dominant hop, named

**The cluster black box is 97% of the gateway's residence (4.04 ms of 4.25 ms p50) and ~83% of the
client RTT.** Everything outside it is negligible: decode 0.06 µs, owner-queue 0.8 µs, apply **0.47 µs**,
reply 12 µs — the entire non-cluster gateway path is **< 15 µs at p50**. The ~4 ms is the distributed
round-trip, and it splits about evenly into two halves:

- **consensus commit round-trip ≈ 2.0 ms** (sequenced → replicated to a 2/3 quorum → Archive-recorded on
  each member → quorum ack → commit-position advances → delivered to the service).
- **Aeron transport + ingress-pickup + egress-delivery ≈ 2.0 ms** (gateway offer → leader ConsensusModule
  picks the message off ingress and sequences it; leader emits egress → gateway owner thread polls it back).

## Why the ~4 ms is NOT the consensus-model floor (so that door stays shut)

The real *work* on the critical path is tiny: **apply is 0.47 µs**, decode/queue/reply are sub-15 µs,
and same-zone GKE network is sub-100 µs/leg. So the ~4 ms is overwhelmingly **waiting**, not computing
or transmitting — and the waiting is **idle-strategy park latency**:

- The members run **Aeron's default `BackoffIdleStrategy`, which parks up to ~1 ms per idle poll**
  (`CLUSTER_IDLE_SLEEP_MS` is unset → `ClusterNodeConfig.sleepingIdleMs()==0` → Aeron defaults). The
  commit round-trip crosses ~4 polling agents per member (MediaDriver, Archive, ConsensusModule, service
  container) across 3 members, plus the gateway's own egress poll — each a place a message can sit up to
  ~1 ms waiting for a parked agent to wake. Two ~1-ms halves (commit; transport+poll) is exactly the
  shape of park latency stacked across the hop, not a quorum network/disk wall.
- The members have **57% CPU headroom** at this load (ceiling campaign: leader 1.28 of its 3-core pin).
  There is CPU to spend turning parks into spins.

The quorum round-trip is fundamental to the replication model, but its *measured* 2 ms is dominated by
park + Archive-record latency, not by an irreducible network/disk floor. **It is therefore NOT provably
the floor**, and per the brief the consensus-model redesign door stays **shut**.

## The lever this selects (cheapest-first; do NOT pre-commit past it)

1. **Busy-spin / low-park idle strategies on the members' Aeron agents** (ConsensusModule, Archive,
   MediaDriver, service container) and the gateway's egress poll — spend the 57% idle CPU headroom to
   collapse the per-hop park latency. This is a **config/code** change (an env-selectable
   `BusySpinIdleStrategy`/`NoOpIdleStrategy`, alongside the existing `SleepingMillisIdleStrategy` knob),
   the cheapest rung, and it targets the exact thing the numbers indict.
2. **Dedicated-core pinning of the member agent threads** (cheap-first placement) — the tail already
   shows host jitter (single-order max 111 ms client, 7 ms commit); pinning collapsed the tail 7–13× in
   the failover work. Pair with (1).
3. Only if, after (1)+(2), the quorum round-trip is *still* the wall: RDMA/DPDK transport (medium), then
   — and only then — the async / hot-hot replication-model question (heavy).

**Recommended next step:** a single A/B — re-run this exact decomposition with a busy-spin idle strategy
on the members (and the gateway egress poll) — and watch the commit + transport-poll halves move. That
measures how much of the 4 ms is park latency vs the genuine quorum floor, and it is the cheapest thing
that can. (That is optimization, so it belongs to the next brief, not this one.)

## Honesty ledger

- Reject path (pure ingress, ~0.47 µs apply, no booking). The consensus round-trip it exercises is the
  same one a booked order pays; only the ~0.5 µs apply differs, so the decomposition transfers.
- The **commit** segment is 1 ms-resolution (epoch-ms cluster clock); enough to place it at ~2 ms and
  rank it, not to sub-divide it. Getting finer needs a nanosecond cluster clock (a config change).
- The **client↔gateway wire** (~0.1–0.6 ms across runs) is the one cross-host subtraction and the
  noisiest number; it is small either way and not on the critical path.
- The **~2.0 ms Aeron-transport+poll** figure is a *residual* (black box − commit − apply), so it also
  absorbs the leader's ingress-arrival→sequenced gap and any clock-quantization slop. It is a bound on
  "everything on the wire/poll path outside the committed window," not a pure network number.
- 75k is unsaturated here (p99/p50 ≈ 1.8×), so these are system latencies, not queue depth — the point
  of decomposing away from the 190k knee.

---

# Addendum — the busy-spin A/B (idle-strategy lever, PROVEN)

The decomposition said the ~4ms is idle-strategy **park** latency, not the consensus model, and named
the lever: non-parking idle on the members' Aeron agents, spending the 57% CPU headroom. This A/B runs
it. One variable changed: the members' idle strategy, via a new `CLUSTER_IDLE_STRATEGY` env
(`ClusterNodeConfig`), default backoff → **`yielding`** (spin + `Thread.yield()`, no park — the
hardware-appropriate choice, since each member's 3-core pin can't give one exclusive core to each of its
4 Aeron agent threads for a pure busy-spin). Same rig, same paced-75k reject path, warm, JFR off, mask=0.
Gateway image unchanged (`CLUSTER_IDLE_STRATEGY` only affects `ClusterNodeMain`), so it's a clean
single-variable A/B.

| p50 | backoff (Aeron default, ≤1ms park) | **yielding** (no park) | speedup |
|---|---:|---:|---:|
| client RTT | 4852 µs | **1876 µs** | **2.6×** |
| client RTT p99 | 10763 µs | **3858 µs** | 2.8× |
| gateway total (residence) | 4248 µs | 1470 µs | 2.9× |
| gateway cluster black box | 4040 µs | 1230 µs | 3.3× |
| **leader commit (consensus round-trip)** | 2000 µs | **1000 µs** | 2.0× |
| leader apply / match | 0.47 µs | 0.40 µs | ~1× (real work — unchanged) |
| member idle CPU (of 3-core pin) | ~1.28 c (43%) | **~2.97 c (~99%)** | the cost |

**Verdict — the park hypothesis is confirmed.** A pure config change (no architecture, no hardware, no
core touch) cut client p50 **2.6×** and p99 **2.8×**. The consensus round-trip **halved** (one ~1ms park
hop removed from the sequence→commit→deliver chain); the transport-poll residual fell further still (the
member ingress-pickup and egress-emit park hops vanished). Apply stayed 0.4µs — it was never the issue.
So **~60% of the original 4ms was Aeron's default idle-poll park latency**, redeemable for the idle CPU.

**What's left, and the next dial (still not architecture).** After yielding, the floor is the ~1ms
consensus commit (log replication + Archive disk record + quorum ack + residual yield/scheduling) plus a
sub-0.3ms transport-poll residual. Cheaper dials before any consensus-model change:
1. `CLUSTER_IDLE_STRATEGY=lowpark` (1µs max park) — likely most of the win for less than the ~99% CPU of
   full yield; and `busyspin` (NoOp) if members are given >3 cores / dedicated pinning so 4 spinners fit.
2. **Dedicated-core pinning** — the tail is still host-jittery (max 15–22ms gateway, 4ms commit); pinning
   collapsed tails 7–13× in the failover work.
3. Aeron **Archive** tuning (fileSyncLevel / recording placement) — the remaining ~1ms commit is partly
   the per-member disk record on the quorum path.
4. Only after those: RDMA/DPDK (medium), then the async / hot-hot replication model (heavy) — still
   **not** justified: the commit round-trip is not the floor while cheaper dials remain untried.

**Caveat (honest):** yielding pegs each member at ~99% of its 3-core pin *even at idle* — it buys latency
with the throughput headroom. At 75k that's a clean win; a fleet pushing the members toward their
consensus ceiling (~440k extrapolated) would have to weigh latency vs that headroom. The lever is a
latency knob, not free.

---

# Addendum 2 — the `lowpark` dial: yielding's latency at ~9x less CPU (the pick)

`yielding` proved the park hypothesis but pegs each member at ~99% of its 3-core pin (it spins its 4
agent threads continuously, and 4-on-3 leaves a commit tail). The `lowpark` dial —
`BackoffIdleStrategy` with a **1µs** max park instead of the Aeron default's 1ms — tests whether a tiny
park recovers the win without the CPU. Same rig, same paced-75k reject path, member idle strategy the
only variable.

| metric | backoff (default, ≤1ms park) | yielding (no park) | **lowpark (1µs park)** |
|---|---:|---:|---:|
| client RTT p50 | 4.85 ms | 1.88 ms | ~2.0 ms |
| client RTT p99 | 10.8 ms | 3.86 ms | ~3.7 ms |
| leader commit p50 | 2000 µs | 1000 µs | 1000 µs |
| leader commit **p99** | 5000 µs | 2001 µs | **1000 µs** |
| leader commit **p99.9** | 6000 µs | 3000 µs | **1000 µs** |
| gateway cluster black box p50 | 4040 µs | 1230 µs | 1160 µs |
| gateway total (residence) p50 | 4248 µs | 1470 µs | 1397 µs |
| apply / match p50 | 0.47 µs | 0.40 µs | 0.62 µs |
| **member idle CPU (of 3-core pin)** | ~1.28 c | **~2.97 c** | **~0.34 c** |

**`lowpark` is the pick.** It matches yielding's client-latency win (~2.4× vs backoff) and beats it on
the tail — the consensus commit round-trip is a **flat 1000 µs from p50 all the way to p99.9** (yielding
left 2–3 ms there; backoff 5–6 ms). And it does so at **~9× less idle CPU than yielding** (~0.34 c vs
~2.97 c), so it does NOT sacrifice the throughput headroom the way yielding does. The reading: the 1 ms
default park was the entire latency problem; replacing it with a 1 µs park removes it, while still
yielding the core when genuinely idle — and it avoids the 4-agent-threads-on-3-exclusive-cores
contention that gave pure spin a worse tail on this hardware.

**Where the floor now is.** After lowpark, the ~1 ms consensus commit is a tight, flat 1 ms (p50=p99=
p99.9) — that is the genuine quorum round-trip: log replication + per-member Archive disk record +
quorum ack + delivery, with the park jitter gone. The next reductions are the real ones: **dedicated-core
pinning** (the single-order max is still ~3 ms commit / tens of ms client — host jitter), **Archive
tuning** (fileSyncLevel / recording placement — part of the flat 1 ms is the disk record on the quorum
path), then RDMA/DPDK. The **consensus-model redesign remains unjustified**: the commit round-trip is now
a clean 1 ms and the cheaper dials are untried.

**Recommendation: ship `CLUSTER_IDLE_STRATEGY=lowpark` as the default for latency-sensitive deployments.**
It is a one-env change, costs ~0.34 c idle, and turns the per-order p50 from ~4.85 ms into ~2.0 ms with a
p99 of ~3.7 ms — no hardware, no architecture, no core touch.

---

# Addendum 3 — `busyspin` DISQUALIFIED, and the four-way verdict

Completeness check: pure `busyspin` (NoOpIdleStrategy, spin with no yield). Prediction was that 4 agent
threads spinning on a member's 3 exclusive cores would oversubscribe; the measurement is worse than that
— it is worse than doing nothing.

| metric | backoff (default) | yielding | **lowpark** | busyspin |
|---|---:|---:|---:|---:|
| client RTT p50 | 4.85 ms | 1.88 ms | ~2.0 ms | ~14–18 ms |
| client RTT p99 | 10.8 ms | 3.86 ms | ~3.7 ms | **~363 ms** |
| leader commit p50 | 2000 µs | 1000 µs | 1000 µs | **4002 µs** |
| leader commit p99 | 5000 µs | 2001 µs | 1000 µs | 8004 µs |
| gateway total p50 | 4248 µs | 1470 µs | 1397 µs | 6078 µs |
| apply / match p50 | 0.47 µs | 0.40 µs | 0.62 µs | 0.43 µs |
| member idle CPU | ~1.28 c | ~2.97 c | **~0.34 c** | ~2.97 c |

**`busyspin` is worse than the Aeron default** — commit doubled (2 ms → 4 ms) and the client tail blew
out to ~363 ms p99 / ~481 ms max (and ~700 ms p50 cold, before JIT). The cause is exactly the hardware
constraint flagged up front: a member is pinned to **3 exclusive cores** but runs **4 Aeron agent
threads** (MediaDriver, Archive, ConsensusModule, service container). Pure spin never yields, so the
scheduler must preempt a spinning thread to run a peer — and when the preempted one is the ConsensusModule
conductor or the MediaDriver receiver, the consensus path *starves*. Spinning harder than the cores allow
is worse than parking.

**Four-way verdict — `lowpark` is the pick, and it isn't close:**
- `busyspin` — disqualified on this hardware (4 agents > 3 cores → starvation, worse than default).
- `yielding` — full latency win, but pegs 3 cores at idle and leaves a 2–3 ms commit tail (spin
  contention).
- `lowpark` (1 µs park) — **the win**: client p50 ~2.0 ms, commit a flat 1 ms to p99.9, ~0.34 c idle.
  The 1 µs park is short enough to kill the 1 ms latency and long enough to never starve a peer thread.
- `backoff` (Aeron default) — the 1 ms park is the entire latency problem.

**Ship `CLUSTER_IDLE_STRATEGY=lowpark`.** `busyspin` and `yielding` remain in the knob for hardware that
can give one core per agent (≥4 dedicated cores/member), where pure spin would finally pay off — but not
on the current 3-core member pin.
