# Phase 1 result — the real per-order ceiling, and the hop that binds

> Campaign: "find the real per-order ceiling." Phase 0 retired the generator as a suspect (isolation
> 2.19M offered/s, ~180× the 12k number). Phase 1 drove the live 3-member GKE cluster up a ladder —
> first by rate at a fixed shallow window, then by **window depth** (connection count) — until a hop
> stopped keeping up **with backpressure present**, then localized it with CPU + in-flight depth.

## Headline

**The per-order path sustains ~149k committed orders/s at 3 gateways — ~12–18× the retired 12k
"ceiling"** — and it is **gateway-bound, not consensus-bound**: at the plateau the members sit at
~⅓ CPU with the in-flight window backing up ahead of the gateways, not the engine. So the eventual
multiplier (partitioning the engine) is **not** justified; the lever is gateway-side and cheap.

## Two regimes: shallow window vs deep window

The binary acceptor is thread-per-connection and **synchronous** (each connection blocks on its
committed ack before sending the next), so the in-flight window ≈ connection count. Committed/s
(leader `nextOrderRef` delta, ground truth) vs window:

| window (conns) | committed/s | max in-flight/conn | member CPU | reading |
|---:|---:|---:|---:|---|
| 64 | ~80,000 | shallow | idle (~28m/3000m) | **window-bound** — everything idle |
| 256 | 132,000 | moderate | ~0.5c | window opening up |
| 512 | 146,000 | rising | ~1.0c | nearing the knee |
| 1000 | **~149,000** | **~18,000 (exploded)** | ~1000m/3000m | **saturated** — real backpressure |

- At a **shallow** 64-connection window the ceiling is ~80k with *everything idle* — a pipelining-window
  limit, exactly Brief 08's "offered ≫ committed, low CPU everywhere" row.
- Deepening the window (more connections, now possible after the OOM fix `57da11cd`) lifts committed/s
  to **~149k**, where it plateaus and the client-side in-flight **explodes from ~10 to ~18,000
  orders/conn** — the generator offered ~352k/s and only ~149k came back. That explosion *at the
  gateway while members stay idle* is the real limit.

## What binds: the gateway path, not consensus

At the ~149k plateau, the trustworthy signals agree:

- **Member (consensus) CPU ~1000m of its 3000m pin = ~33%** — consistent across the whole window
  sweep. Consensus has ~2/3 headroom; it is **not** the wall.
- **In-flight backs up ahead of the gateways** (18k/conn) while the members drain what they receive —
  the queue is on the gateway side of the path.
- Gateways run hot toward their 2-core cgroup cap under load. *(Caveat: `kubectl top` overshoots and
  lags — it read ~1995m even during the unsaturated 75k run where in-flight depth proves the gateway
  kept up — so the gateway-CPU number is not talk-grade. The member-idle and in-flight-depth signals
  are, and they are what carry the conclusion. A per-thread `/proc/<pid>/task` profile would be needed
  to cleanly split gateway owner-thread work from Aeron-driver poll before quoting gateway CPU.)*

## Latency (unsaturated, coordinated-omission-safe)

Paced 75k/s, 256 connections, timed from each order's intended send (max in-flight 6–10/conn confirms
the engine is unsaturated at this rate):

```
p50 ~4.0 ms   p99 ~15 ms   max ~49 ms
```

This is the per-order **commit** RTT (offer → consensus → apply → ack over raw TCP; not the HTTP path,
so the 40 ms Nagle/delayed-ACK trap does not apply). Report the unsaturated row; latency past the
~149k knee is queueing, not the system.

## Next lever — and what is NOT justified

**Deepen gateway capacity: more gateways / gateway owner-thread sharding.** Members have ~2/3 CPU
headroom and the gateway is stateless-forward (it round-robins with no coordination), so adding
gateways should scale committed/s further until consensus finally binds — *that* number is the true
distributed-path ceiling and is still above ~149k, unmeasured. There is a free c2d node ready for a
4th gateway to prove it.

- **Partitioning the engine is NOT justified** — members at ~33% CPU are the opposite of a consensus
  wall. Building it now scales past a limit that is not binding (Brief 08's explicit warning).

## Honesty ledger

- 12k is retired; report it only as "coasting, generator-limited, 0 backpressure."
- ~149k is a **real** ceiling (backpressure = in-flight explosion at the gateway) **at 3 gateways**;
  it is gateway-bound, not the engine's ceiling, which is higher and unmeasured. Say both.
- Ground truth is member `nextOrderRef` delta, never the gateway `accepted` counter.
- Gateway CPU is not talk-grade (metrics-server overshoot); the member-idle + in-flight-depth signals
  carry the "not consensus-bound" claim.
- This is the pure-ingress / reject path (per-order consensus+apply, the campaign's question) — never
  blended with booking (engine ~150k booked/s; end-to-end persist ~365/s) or batch (438k).
