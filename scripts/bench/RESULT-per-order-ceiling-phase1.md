# Phase 1 result — the real per-order ceiling, and the hop that binds

> Campaign: "find the real per-order ceiling." Phase 0 retired the generator as a suspect (isolation
> 2.19M offered/s, ~180× the 12k number). Phase 1 drove the live 3-member GKE cluster up a ladder until
> a hop stopped keeping up **and backpressure appeared** — the signal of a real limit, not a harness one.

## Headline

**~80–87k committed orders/s** on the distributed per-order path — **~7× the retired 12k "ceiling,"**
and this one is real (backpressure present). But the binding hop is the **in-flight / pipelining
window, not any system resource** — members and gateways sat idle at the plateau — so the *system*
ceiling is above 80k and still unmeasured. The lever is cheap.

## The ladder

GKE, `:yu13-idempfix`, 3 gateways, 64 connections (`SESSIONS_PER_POD=16`), empty-cluster pure-ingress
(orders traverse consensus + apply and reject at the risk/security gate — `nextOrderRef` advances
regardless, so this isolates the per-order consensus+apply cost from matching). Ground truth = leader
`nextOrderRef` delta.

| offered/s | committed/s (nextOrderRef) | acks | write-stalls | reading |
|---:|---:|---|---:|---|
| 20,000 | 16,000 | track | 9 | keeping up |
| 40,000 | 31,000 | track | 42 | keeping up |
| 80,000 | 66,000 | track | — | nearing the knee |
| 160,000 | 79,000 | **collapse (83k of 160k)** | 523 | past the knee — backpressure |
| blast | 82,000 (plateau) | — | — | hard plateau |

`nextOrderRef` climbs 16k → 31k → 66k → 79k → **82k and flat**; offer past ~80k just queues (acks fall
to 83k of 160k offered, client write-stalls jump 9 → 42 → 523). That divergence **with** backpressure
is a genuine limit.

## The binding hop: the in-flight window (not consensus, not gateway CPU)

At the plateau, everything downstream is **idle**:

```
leader member CPU     33m / 3000m   (~1% of its 3 pinned cores)
gateway CPU           ~28m          (idle)
offered >> committed, hard plateau, backpressure at the client
```

Idle members + idle gateways + offered ≫ committed = the classic **pipelining-depth / in-flight
window** signature from Brief 08's table. The binary acceptor is **thread-per-connection and
synchronous** — each connection reads one frame, blocks on its committed ack, then reads the next — so
the effective in-flight window **equals the connection count**. 64 connections × ~800 µs commit-RTT ≈
80k/s. The plateau is the window, not the engine.

## Next lever — and what is NOT justified

**Deepen the in-flight window** (cheap): more connections and/or an async binary acceptor that allows
several orders in flight per connection. Because members and gateways are idle, committed/s should
climb well past 80k until a real resource (consensus commit rate or gateway owner-thread CPU) finally
binds — *that* number is the system's true per-order ceiling, and it is above 80k and unmeasured.

- **Partitioning is NOT justified.** It is the eventual multiplier only past a genuine consensus wall;
  members at ~1% CPU are the opposite of that. Building it now scales past a limit that is not binding.
- **Gateway owner-thread sharding is NOT justified** either — gateway CPU is idle.

The rig OOM that forced this ladder down to 64 connections is now fixed (commit `57da11cd`:
`RING_BITS`/`LAT_CAP_BITS`, `-Xmx1200m -Xss256k`), so a deep-window ladder can run:

```
# window-scaling ladder — hold rate deep, raise connections, watch where committed/s stops scaling
# AND a real resource finally gets busy (kubectl top members/gateways during each rung):
for S in 64 128 250; do PODS=4 SESSIONS_PER_POD=$S MODE=blast SECS=30 bash scripts/bench/run-bin-blast-gke.sh; done
```

## Caveats (before this feeds a talk)

- **CPU/latency are not yet talk-grade.** `kubectl top` returned a single cached datapoint (4 identical
  samples) — it corroborates "members idle" but a **60 s-window rerun** (metrics-server scrape interval)
  is needed for a defensible CPU + latency figure.
- **This is the pure-ingress / reject path**, not a booking run — it measures per-order consensus+apply,
  which is the campaign's question (where the *distributed path* knees, independent of matching). A
  booking+read-model number is a different, lower contract (engine ~150k booked/s; end-to-end persist
  ~365/s) — never blend them.
- Two topology gaps block any *booking* run on this kustomization (irrelevant to pure ingress):
  `reference-data` + a fresh-boot reseed are omitted, and `/risk/control/*` 404s in this topology.

## Honesty ledger

- 12k is retired; report it only as "coasting, generator-limited, 0 backpressure."
- The ~80–87k ceiling is **real** (backpressure at the binding hop) but is a **window** ceiling at 64
  in-flight — the system ceiling is higher and unmeasured. Say both.
- Ground truth is member `nextOrderRef` delta, never the gateway `accepted` counter.
- Per-order (this) and batch (438k) are different contracts — never blended.
