# Phase 2 result — the gateway lever, proven, and talk-grade gateway CPU

> Campaign: "find the real per-order ceiling." Phase 1 (`RESULT-per-order-ceiling-phase1.md`) found the
> per-order path binds at **~149k committed/s at 3 gateways, gateway-bound not consensus-bound**, and
> named the lever: **more gateways / owner-thread sharding**. Phase 2 *demonstrates* that lever with a
> clean 3-vs-4-gateway A/B on identical config, and replaces Phase 1's honesty caveat (gateway CPU
> "not talk-grade — `kubectl top` overshoots") with a per-thread `/proc` profile.

## Headline

**Adding one gateway (3→4) lifts the committed ceiling from 149.6k to 190.3k orders/s — 95% of
ideal-linear.** The per-order path is **gateway-bound**: at saturation a gateway runs **1.99 of its
2-core cap (99%)** while the consensus leader sits at **1.28 of its 3-core pin (43%)** — measured at the
same instant, both via `/proc`. "Add gateways to taste" is now a demonstrated curve, not an inference.
Partitioning the engine stays **unjustified** (members have 57% headroom — the opposite of a consensus
wall).

## 1. The A/B — ceiling scales with gateway count

Same everything both arms: `yu13-readmodel` member image (unchanged — no core swap), reject path
(`SECURITY=1`, unseeded → each order sequences then rejects `UNKNOWN_SECURITY`, so `nextOrderRef`
advances = pure ingress, no booking, no position/risk caps in play, read-model tap does not fire),
blast mode, 4 generator pods × 250 sessions = **1000 connections** (the deep in-flight window Phase 1
found reaches the knee), 60s synchronized window, generators on the `c2d-load-pool`.

| gateways | committed/s (leader `nextOrderRef` Δ, **ground truth**) | per-gateway | offered/s | in-flight/conn | backpressure |
|---:|---:|---:|---:|---:|---|
| 3 | **149,610** | 49.9k | 347,566 | ~18,200 (exploded) | write-stalls 3,876, climbing |
| 4 | **190,299** | 47.6k | 400,476 | ~18,200 (exploded) | write-stalls 4,580, climbing |

- **190.3k vs ideal-linear 199.5k (= 3-gw per-gw × 4) = 95% linear.** The 5% sublinearity is expected —
  generators now share all 4 c2d nodes with a gateway each (arm 1 had one gateway-free node), and the
  leader creeps from ~37% to ~43% CPU. Neither is the engine.
- Over-offer proven both arms: generators offered ~350–400k/s while only ~150k / ~190k came back, and
  client in-flight exploded ~10 → ~18,000 orders/conn. **That explosion at the gateway while members
  stay idle is the backpressure that makes each number a real ceiling, not a harness one.**
- The funnel is tight and internally consistent at both ceilings (4-gw arm):
  `offered 400k → gateway decoded 190.4k → offer-success 190.3k → nextOrderRef 190.3k`. The gateway is
  where 400k becomes 190k.

## 2. Talk-grade gateway CPU — the `/proc` profile (replaces the Phase 1 caveat)

`kubectl top` is unusable here and this run proves it: sampling the four gateways under identical
per-connection load, metrics-server reported **27m, 290m, 450m, 671m** in one snapshot and **1995m
across the board** in another — same load, ~70× spread. So CPU was measured from `/proc/<pid>/stat`
(process total, sums all threads incl. short-lived ones) and `/proc/<pid>/task/*` (per-thread),
self-timed off `/proc/uptime` so exec overhead can't corrupt the interval.

**Matched pair at the ~190k (4-gw) saturation, same instant:**

| pod | `/proc` CPU | of its cgroup | user / kernel split | reading |
|---|---:|---:|---|---|
| gateway (`9mb4r`) | **1.99 cores** | **99% of 2-core cap** | 22% user / **78% kernel** | **SATURATED — the bottleneck** |
| leader member (`cluster-node-2`) | **1.28 cores** | **43% of 3-core pin** | — | 57% headroom — **not** the bottleneck |

The 99%/43% split is the quotable, trustworthy version of Phase 1's "member-idle + in-flight-depth"
argument. Confirmed on three independent gateway captures (1.877c, 1.888c, 1.988c = 94–99% of cap).

### What actually fills the gateway's 2 cores (per-thread, 338 threads, ~48k orders/s/gw)

| threads | name | cores | % of 2-core cap |
|---:|---|---:|---:|
| 252 | `binary-conn-*` (thread-per-connection socket workers) | **1.216** | 61% |
| 1 | `cluster-client-*` (Aeron cluster-client **owner thread** → consensus) | 0.399 | 20% |
| 1 | `/dev/shm/aeron-*` (Aeron media-driver transport) | 0.273 | 14% |
| — | **process total** | **1.888** | 94% |

**The honest nuance the lever name obscures:** the single owner thread that funnels orders into
consensus is only **0.40c / 20% of the cap — it is *not* itself saturated.** What binds is the
**aggregate 2-core gateway budget**, and it is dominated (1.22c) by ~250 thread-per-connection socket
workers spending **78% of their time in the kernel** (per-order `read`/`write`/futex/context-switch),
not in user compute. So:

- **`owner-thread sharding` within one gateway would not move the ceiling yet** — the owner thread has
  4× headroom. The wall is the whole gateway's core budget, not that one thread.
- **The proven, cheap lever is more gateways** — each adds ~2 cores of the scarce budget, and committed/s
  scales ~linearly (this A/B) until the leader's 3-core pin finally binds. Extrapolating the leader's
  1.28c/3c at 190k, consensus wouldn't bind until **~440k/s** (unmeasured, needs ~9 gateways + generator
  capacity) — that is the true distributed-path ceiling, still far above 190k.
- **The structural per-gateway lever** (raises efficiency, not count) is replacing the thread-per-
  connection **synchronous** acceptor with an async/batched one — the 78%-kernel signature says the win
  is *fewer syscalls per order*, which shrinks the 1.22c socket-worker cost.

## 3. What is NOT justified

**Partitioning the engine.** Leader at 43% CPU with the queue backing up *ahead of* the gateways is the
opposite of a consensus wall. Building partitioning now scales past a limit that is not binding
(Brief 08's explicit warning). Revisit only if a gateway fleet ever drives the leader to its pin.

## Method / reproducibility

- Rig: `scripts/bench/run-bin-blast-gke.sh` (Phase 0/1 generator, isolation-proven 2.19M offered/s).
  Phase-2 change: `EXPECT_GW` env parameterizes the gateway-count guard (default 3; set 4 for the 4-gw
  arm) so the mid-rollout safety check still trips on a blended fleet.
- 4-gw arm: `kubectl scale deploy cluster-gateway --replicas=4` (4th lands one-per-node on the free c2d
  node by anti-affinity); restored to 3 after. No member image swap → no cross-lane core-version risk.
- `/proc` profilers (scratch): process-total + per-thread, self-timed via `/proc/uptime`; member JVM is
  a child PID (container PID 1 is the init wrapper), gateway JVM is PID 1.
- Ground truth throughout: leader `traderx_cluster_next_order_ref` delta on `:8080/metrics`, never the
  gateway `accepted` counter.

## Honesty ledger

- 149.6k and 190.3k are **real** ceilings (backpressure = in-flight explosion at the gateway + generator
  proven to over-offer at ~350–400k/s), **at 3 and 4 gateways respectively** — not the engine's ceiling,
  which is higher and still unmeasured. State both.
- Gateway CPU is **now talk-grade** (1.99c/2c = 99%, via `/proc`); `kubectl top` is not (70× spread on
  identical load — do not quote it).
- The "owner-thread sharding" half of Brief 08's lever is **not yet actionable** — the owner thread is
  at 20%; the actionable levers are *more gateways* (proven here) and *async acceptor* (per-gateway).
- Pure-ingress / reject path (per-order consensus+apply). Never blended with booking (~130k/s peak) or
  batch (438k). Per-order and batch are different contracts.
