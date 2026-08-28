---
title: Observability and replay
sidebar_label: Observability and replay
description: How an order's trace survives Raft consensus without adding a byte to the replicated log, how the platform's history is journalled and played back in kdb+/q, and how a licensed market tape is replayed on a stateless clock as both the venue's price reference and its order flow.
---

# Observability and replay

Three capabilities extend states that already exist rather than standing as states of their own.
Tracing and the tick store were both built to the same constraint — **they may not change what the
trading path costs.** The tape replay is the opposite case: it deliberately puts load on the venue,
and its whole design is about staying accountable for the load it adds.

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

### Why kdb

kdb+/q is the time-series database this corner of finance actually runs on. Tick capture,
journaling and session playback sit on it across front offices in the industry, and analysts query
it in q rather than in SQL. Putting the platform's history there — and querying it the way the
desk would — is what makes the data side of this system read as the real thing rather than a
trading demo with a database bolted on.

So the platform's history lives in KDB-X, and the same verbs work over all of it: `.ts.vwap` and
`.ts.spread` for prices, `.ts.session` to pull a window out, `.tx.fills` and `.tx.orders` for our
own executions, and `.ts.replay` / `.tx.replay` to step a captured session back through at real
time or as fast as the machine will go.

### Reading the corpus in place

KDB-X reads the existing ZSTD Parquet corpus **natively — there is no conversion step and no second
copy.** A q layer maps each object as a virtual table, prunes row groups against the `WHERE` clause,
and stitches the per-file tables into one date/symbol-partitioned table whose partition columns come
from the path the ingest already wrote.

That reader also settled the practical worry about the Community edition's 16 GiB ceiling: an
aggregate over all 47.8M quote rows peaked at **768 MiB**, because it works a row group at a time
rather than materialising the table.

### What is stored

| Tables | Source | Loader | What it is |
|---|---|---|---|
| `quote` / `trade` | NYSE TAQ tape | `tickstore.q` | what the **market** did |
| `txOrder` / `txTrade` | TraderX cluster | `txstore.q` | what **our engine** did |

The two keep separate names because a tape print and an engine execution are different objects with
different provenance — a single `trade` table holding both is how a VWAP ends up quietly answering
a question nobody asked.

### The live capture tap

Our own flow is captured off the running cluster by a **leader-side tap that sits off the consensus
path**, so recording never becomes something consensus waits for. The capture is a read-side
projection of committed output, exactly like the SQL bridge — not an input the state machine reads.
A stalled disk fills its queue and drops, loudly and counted; it cannot stall an apply thread.

### Journal and playback, in two senses

Both words do double duty in this system, and the distinction is worth stating plainly.

| | Authoritative | Analytical |
|---|---|---|
| Store | Aeron Archive (+ snapshots) | **KDB-X** |
| Purpose | consensus, recovery, determinism | query, analytics, session playback |
| Playback means | replay the log to rebuild exact state | replay a captured session to study it |
| On the hot path? | yes, synchronous, before commit | no — off-consensus, best-effort |

Nothing in the tick store is authoritative or required for recovery. What it holds is a kdb
tickerplant log, not a consensus journal: delete the whole thing and the cluster still recovers
byte-identically; delete the Aeron Archive and it does not.

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

## The market tape, replayed

### A real price at a fabricated time

The reference price a book's collar is anchored to used to be an invented number — a random walk,
honestly labelled as simulated. It is now the **licensed NYSE TAQ tape** covering February and March
2025, the same corpus the tick store holds.

That creates a category the wire format could not express. A replayed print is neither live nor
invented; it is a **real price at a fabricated time**, and the existing `simulated` boolean is a lie
whichever way it is set. So a tick now carries a `source` and an **`asOf`** — the true tape timestamp
— beside that flag. Provenance that answers "was this invented?" but not "when was this true?" has
answered the less interesting of the two questions.

### Resampled offline, not streamed

The publisher does not stream prints. For each symbol and each publish interval, **one reference
price is computed offline** — a median over the window — and that series is what gets replayed.

One choice doing four jobs, which is why no later reader should simplify away three of them:

- **The message rate is set by the resample cadence and the universe size, never by the tape's real
  print rate.** Sequenced ticks consume consensus, and a symbol's true print rate under time
  compression is not a number anything downstream was sized for. It is also what makes time
  compression free — a lookup index rather than a throttle.
- **The unfiltered print is defused.** The corpus was ingested without its trade-correction and
  sale-condition columns, so isolated erroneous or out-of-sequence prints survive in it. A median is
  robust to those. That makes the series *sane*, which is all a collar reference needs to be — it
  does not make it reference-grade and must never be described as such.
- **The extract collapses.** One price per symbol per interval across forty trading days is a few
  megabytes against a 650 GiB corpus, computed once, in-region.
- **The whole thing stays revertible**, because a small named artifact can be switched off in a way
  a streaming integration cannot.

### The clock is stateless

```
replay_position = (now − epoch_start) × compression
tape_timestamp  = FIRST_TRADING_DAY + replay_position   # skipping non-trading days
```

Position is **derived, never stored.** A publisher restart therefore resumes at the right point with
no coordination and no persisted cursor, and two publishers would agree without talking to each
other. That is the property to protect if this design is ever changed.

**The members never see the mapping.** The publisher decides which tick to emit and the cluster
sequences whatever arrives, so there is no replicated clock, no divergence risk, and no change to the
deterministic core.

### Prints become order flow

A correct reference price moves a number on a screen. It does not make the engine do the thing it
exists to do. So prints from the same tape also enter as **sampled order flow through dedicated
replay accounts**, and the engine matches, fills, moves positions and exercises the collar on
activity that genuinely occurred.

Three properties of that flow are stated rather than implied:

- **The side is inferred, not read.** TAQ trades carry no side, and it cannot be recovered from this
  corpus — reconstructing it from the quote needs an NBBO, and the columns that would rebuild one
  were dropped at ingest. The **tick rule** supplies it: an uptick is a buy, a downtick a sell. It is
  an approximation, and it is labelled as one rather than presented as fact.
- **The rate is sampled, and the sample is the design.** A single active symbol carries hundreds of
  thousands of prints per trading day, against a thirty-minute wall-clock tape day. Replaying
  one-for-one is the wrong target rather than a throughput problem to solve, so the flow is sampled
  to a tunable order rate.
- **The reference is still the reference.** Replayed orders do not set the collar's reference; that
  stays the resampled median series. A replayed print that breaches the collar is **rejected, and
  that is the band working rather than a defect.**

Reference and order flow come from **the same process and the same clock**, deliberately: a second
derivation of the position would be a second clock, and could put the order flow at a different tape
instant from the reference it is being checked against. Every order is then a pure function of its
symbol and its absolute tape slot — down to a client order ID naming both, which makes each submitted
order self-identifying against the tape and idempotent on a retry.

This is not a backtest, and no number derived from it is reference-grade. It is the system working on
real activity.

## Where each lives

None is a state of its own, so each sits in the layer of the state it extends:

- **Tracing** — the [YU13](/specs/YU13-limit-order-book) layer, alongside the order-matcher and
  gateway it instruments.
- **Tick store** — the [YU07](/specs/YU07-historical-tick-store) layer, the historical tick store
  state.
- **Tape replay** — shipped with [YU17](/specs/YU17-otc-rates), reading the corpus the tick store
  state already holds and driving the book and collar that [YU13](/specs/YU13-limit-order-book)
  defines.

## How this is verified

The 35 q gates run as their own tier. The tick store's Python side runs 24 tests in CI against the
module's pinned requirements, and the trace pipeline has two end-to-end proofs that assert a trace
actually crosses consensus and that a rejected order's log line joins its trace.

The tape replay is held to the standard its own attribution rule sets: an in-process test and an
end-to-end proof together have to **name a counter the replay does not advance, and show it standing
still on a live rig while the replay runs.** Nothing may claim attribution without passing both
halves of that.

- **[Testing strategy](testing-strategy.md)** — which tier proves what, and what stays manual.
- **[Test coverage](test-coverage.md)** — what runs automatically, and how every number was counted.
