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

## Booking addendum — match/s and wire-to-wire-to-match latency (the reject path's counterpart)

The ladder above is the **reject path** (unseeded → `UNKNOWN_SECURITY`): submit/s, no matching. To
answer "of that, how many *match*, and is the latency to a matched confirmation?", a **booking** ladder
was run with securities seeded (JPM=2 on two-account crossing, `TRADE_BRIDGE` off, price refreshed
≤8 s). Key mechanic: for a marketable crossing order the **match runs inside the same committed apply
that produces the accept-ack**, so the submit→ack latency on a booking run *is* wire-to-wire-to-match
(the ack just doesn't enumerate fills). Ground truth match/s = leader `traderx_cluster_trades` delta.

| offered (paced) | committed acks/s | match/s (trades, fine) | latency p50 / p99 / max | in-flight/conn | state |
|---:|---:|---:|---|---:|---|
| 40k | 40k | ~40k | 3 ms / 15 ms / 30 ms | 5 | clean |
| 100k | 100k | ~100k (bursts 120–130k) | 7.5 ms / 45 ms / 68 ms | 13 | **near knee** |
| 140k | 131k | bursts ~160k | 60–430 ms / **1.7–2.7 s** / 3 s | 800–1300 | **saturated** |

- **match/s ≈ submit/s ≈ offered, ~1:1** on the two-account crossing flow — total trades delta equalled
  total offered to <0.01% at every rung, i.e. **every crossing order books**. (Semantics: the `trades`
  counter increments ~once per booked order-fill; a distinct buy↔sell *match* = two fills = two
  increments, so distinct matches/s ≈ half the quoted number.)
- **Booking commits up to ~130k/s** (140k offered → 131k acks) — close to the ~149k reject-path submit
  ceiling, so **matching adds only modest per-order cost** over rejecting. The **sustainable** rate with
  sane latency is ~100k (p99 45 ms); past it (~140k) the cluster saturates and latency blows to seconds
  (queueing, not system latency — report the unsaturated rows).
- **Wire-to-wire-to-match latency (unsaturated): p50 3–7.5 ms, p99 15–45 ms** — essentially the same as
  the reject-path commit latency, because the match is part of the acked apply. This is client
  round-trip over raw TCP (includes c2d↔c4d hops), not server-internal.
- **Position-cap constraint (honest):** `/risk/control/*` 404s in this topology so the 1M cap couldn't
  be lifted; each two-account pair walls at ~2M trade-events (1M position). Each booking rung therefore
  used a **fresh seeded pair** (42422/22214 → 44044/52355 → 10031/62654); all seven seeded accounts are
  now position-loaded, so a further booking run needs a fresh epoch. Rates above are the clean pre-cap
  windows; the earlier blast attempt hit the cap in ~5 s and is excluded.

## Honesty ledger

- 12k is retired; report it only as "coasting, generator-limited, 0 backpressure."
- ~149k is a **real** ceiling (backpressure = in-flight explosion at the gateway) **at 3 gateways**;
  it is gateway-bound, not the engine's ceiling, which is higher and unmeasured. Say both.
- Ground truth is member `nextOrderRef` delta, never the gateway `accepted` counter.
- Gateway CPU is not talk-grade (metrics-server overshoot); the member-idle + in-flight-depth signals
  carry the "not consensus-bound" claim.
- This is the pure-ingress / reject path (per-order consensus+apply, the campaign's question) — never
  blended with booking (engine ~150k booked/s; end-to-end persist ~365/s) or batch (438k).
