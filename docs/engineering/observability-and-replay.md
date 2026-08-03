---
title: Observability and replay
sidebar_label: Observability and replay
description: How an order's trace survives Raft consensus without adding a byte to the replicated log, and how the KDB-X tick store keeps the market's prints and our engine's own flow deliberately apart.
---

# Observability and replay

Two capabilities extend states that already exist rather than standing as states of their own. Both
were built to the same constraint: **they may not change what the trading path costs.**

This page is the long version. The short one is on
[What's new](whats-new.md#other-additions).

## OpenTelemetry — a trace that survives consensus

### The problem

A useful trace has to span gateway → sequence → consensus commit → apply → egress. But the gateway
and the members are **different processes joined only by the replicated log**, so the trace has to
cross a consensus boundary.

The obvious fix — put a `traceparent` in the sequenced message — is the one thing we may not do.
Bytes in the log are replicated state, so adding them is a schema change, a member roll, and a
permanent determinism risk taken on behalf of a debugging feature. It is also a correctness hazard:
a resend carrying a *new* trace id would no longer be byte-identical to the original, so replay
would stop reproducing.

### Derive, don't carry

Every order already carries a **client idempotency key** through the log — set by the gateway from
the client's ClOrdID and read by the engine for duplicate suppression. It is business data that is
already replicated, already unique per order, and already identical on every member and on replay.

Both sides run the *same pure function* over it:

```
traceId       = splitmix64(key), splitmix64(key ^ TRACE_SALT)   (128-bit)
sampled?      = (splitmix64(key ^ SAMPLE_SALT) & mask) == 0
clusterSpanId = splitmix64(key ^ CLUSTER_SALT)
```

So a member independently arrives at the same trace id, the same parent span id and the same
sampling verdict the gateway did — with **zero bytes added to the log, zero schema change, and
nothing new for the state machine to read.**

Two consequences fall out of that choice:

- **A rejected order is traced whatever the sampling verdict said.** "Was it rejected" is likewise a
  committed, deterministic fact that both sides read off the same ack, so both escalate together and
  the trace stays whole. Error sampling is only possible here *because* the decision is derivable —
  a collector's tail sampling cannot recover a span that head sampling never emitted.
- **A log line can join a trace by computing its id** rather than by being handed one. The id lives
  in the line itself, not in a label.

### Why this is not telemetry in replicated state

The derivation is one-way and read-only. It consumes a committed field and produces an id that is
never written back, never encoded into an output event, and never branched on by the engine. Delete
the class and every member still emits byte-identical output.

### Not slowing the trade path

A producer — a REST or FIX submit thread, the gateway's owner thread, a member's apply thread — does
exactly one thing: copies eight longs into a pre-allocated ring buffer and returns. No lock, no
allocation, no I/O, and deliberately **no backpressure path back to the caller**. If the ring is
full, the write fails, a counter increments, and the order carries on untouched.

Dropping telemetry under load is correct behaviour. Stalling an owner thread behind a slow collector
would be the worst outcome available, so the design makes it unreachable rather than unlikely.

Everything expensive — hex formatting, JSON assembly, HTTP, retries, the collector being down —
happens on one daemon thread that no order ever touches. A collector outage costs a counter, not a
millisecond.

### Why not the OpenTelemetry SDK

Its batching processor has the right shape — bounded queue, drop on full — but the API above it
allocates per span. This path runs under an allocation gate, and under Epsilon GC (no collector at
all) in the no-GC proofs, where a single allocated byte fails the build.

The sink emits OTLP/HTTP with a JSON body instead: a documented, stable wire format, posted to the
same `/v1/traces` endpoint any SDK would use. About a hundred lines, and no new dependencies.

### Running it

Tracing is off unless `OTEL_TRACES=1`; otherwise every call site holds a null reference.

```bash
bash scripts/yu15/start-observability-kind.sh   # OTel Collector, Tempo, Prometheus, Grafana, Loki
bash scripts/yu15/demo-otel-traffic.sh          # drive traffic and watch traces arrive
```

The observability stack has shipped in the manifests since state 007, but the cluster bring-up
deploys only the trading tier — so on kind the two halves land in different clusters and the
collector endpoint resolves to nothing. The trace pipeline is then *silently* dead: orders book
fine, spans go nowhere, and the only symptom is an empty Tempo. That script exists to prevent
exactly that.

## KDB-X tick store (kdb+/q)

### Two stores, deliberately separate

The tick store holds two datasets side by side, and keeping them apart is the whole design.

| Tables | Source | Loader | What it is |
|---|---|---|---|
| `quote` / `trade` | NYSE TAQ tape | `tickstore.q` | what the **market** did |
| `txOrder` / `txTrade` | TraderX cluster | `txstore.q` | what **our engine** did |

A tape print and an engine execution are different objects with different provenance. A single
`trade` table holding both is exactly the mistake that makes a VWAP silently answer the wrong
question — a number that looks right, reconciles against nothing, and is wrong. The two stores load
side by side and stay distinguishable.

### The live capture tap

Our own flow is captured off the running cluster by a **leader-side tap that sits off the consensus
path**, so recording never becomes something consensus waits for. The capture is a read-side
projection of committed output, exactly like the SQL bridge — not an input the state machine reads.

### The gates

Both stores are checked by q gates that are **cross-implementation**: every expected value was
computed independently in a second engine over the same files, so the store is verified against
something other than itself.

| Gate | Checks | Covers |
|---|---:|---|
| `selfcheck.q` | 17 | per-partition row counts, deduplication, the quote/trade split, first trades to the tick, regular-hours VWAP across every symbol-day, replay ordering and pacing |
| `txselfcheck.q` | 18 | schema, the leader-only guard, and capture count equal to the cluster's own trade count |

### Running it

```bash
TICKSTORE_ROOT=/path/to/ticks  q kdb/tickstore.q     # the market tape
TICKSTORE_ROOT=/path/to/sample q kdb/selfcheck.q     # 17 gates over it
```

`txstore.q` and `txselfcheck.q` take the same shape over a captured session.

## Where each lives

Neither is a state of its own, so each sits in the layer of the state it extends:

- **Tracing** — the [YU13](/specs/YU13-limit-order-book) layer, alongside the order-matcher and
  gateway it instruments.
- **Tick store** — the [YU07](/specs/YU07-historical-tick-store) layer, the historical tick store
  state.

## How this is verified

The 35 q gates run as their own tier. The tick store's Python side runs 24 tests in CI against the
module's pinned requirements, and the trace pipeline has two end-to-end proofs that assert a trace
actually crosses consensus and that a rejected order's log line joins its trace.

- **[Testing strategy](testing-strategy.md)** — which tier proves what, and what stays manual.
- **[Test coverage](test-coverage.md)** — what runs automatically, and how every number was counted.
