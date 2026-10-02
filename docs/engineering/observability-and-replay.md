---
title: Observability and replay
sidebar_label: Observability and replay
description: Distributed order traces, market history and analytical playback.
---

# Observability and replay

Tracing extends YU13; kdb+/q history and capture extend YU07. Both use bounded asynchronous paths. Their performance effect depends on load and configuration; allocation and timing are checked separately.

## OpenTelemetry: a trace across consensus

An order trace spans gateway, sequencing, consensus commit, apply and egress. The gateway and members derive trace identity from the client idempotency key already in the replicated message. They do not add a separate tracing field to the log.

```
traceId       = splitmix64(key), splitmix64(key ^ TRACE_SALT)   (128-bit)
sampled?      = (splitmix64(key ^ SAMPLE_SALT) & mask) == 0
clusterSpanId = splitmix64(key ^ CLUSTER_SALT)
```

The same pure functions produce the same trace/parent IDs and sampling decision on both sides. Rejected orders escalate sampling from the deterministic acknowledgement kind. A collector cannot recover a span that was never emitted. Log lines carry derived trace IDs as text rather than high-cardinality Loki labels.

A producer copies eight longs into a preallocated ring. Full rings drop and count observations; exporter formatting and OTLP/HTTP requests run on a daemon thread. Exporter pacing also applies on failure. This prevents direct collector backpressure on the order path, while CPU, disk and backlog contention still require measurement.

Allocation gates cover the declared steady-state JVM profile. Epsilon tests run without reclamation and have a finite heap; they are separate from byte-allocation assertions and do not mean that every single allocated byte immediately terminates a process.

Tracing is enabled by `OTEL_TRACES=1`. The local observability launcher provisions Collector, Tempo, Prometheus, Grafana and Loki for the selected rig. Inspect context and endpoint wiring before running it:

```sh
bash scripts/yu15/start-observability-kind.sh
bash scripts/yu15/demo-otel-traffic.sh
```

These are operational commands and can deploy services or submit traffic. They were not run for the docs refresh. A working order path with empty Tempo may indicate collector routing, sampling or exporter failure; it is not proof that no orders occurred.

## KDB-X tick store (kdb+/q)

The query layer exposes market VWAP/spreads and session windows, plus engine fills/orders and analytical playback. `.ts.vwap`, `.ts.spread`, `.ts.session`, `.tx.fills`, `.tx.orders`, `.ts.replay` and `.tx.replay` address those separate datasets.

| Tables | Source | Loader |
|---|---|---|
| `quote` / `trade` | Authorized market tape | `tickstore.q` |
| `txOrder` / `txTrade` | Captured TraderX output | `txstore.q` |

A tape print and an engine execution have different provenance. Do not merge them into a single undifferentiated trade dataset or compare counts without checking that the run and time windows agree.

The historical reader supports an existing ZSTD Parquet corpus through row-group pruning and virtual tables. This describes a supported input format, not a requirement to convert newly supplied raw data. A historical aggregate over **47.8M quote rows** reported **768 MiB** peak memory against a **16 GiB** edition limit. That is a bounded historical workload; the exact runtime manifest is not supplied here and this refresh did not rerun it.

The leader-side capture tap projects committed output outside consensus. A stalled sink fills the queue and increments drops rather than blocking apply. After a saturating flood, exporter backlog drain can still affect co-resident latency; the tracing/capture comparison observed that boundary.

## Recovery journal versus analytical history

| | Aeron Archive and snapshots | kdb+/q capture |
|---|---|---|
| Purpose | Replicated state recovery | Queries and analytical playback |
| Replay | Rebuild deterministic engine state | Study a captured session |
| Completeness | Required by the selected recovery procedure | Best-effort capture with explicit drop counters |

Analytical playback does not repair a missing authoritative archive. Retain the archive required by managed catch-up and the configured snapshot/recovery protocol.

## Checks and prerequisites

The historical q suite lists **17** market-store checks and **18** capture checks. Expected values were independently computed for the checked datasets. These counts belong to those scripts and fixtures, not a new full-system run.

```sh
TICKSTORE_ROOT=/path/to/authorized/ticks q kdb/tickstore.q
TICKSTORE_ROOT=/path/to/authorized/sample q kdb/selfcheck.q
```

`txstore.q` and `txselfcheck.q` operate on an authorized captured session. Confirm the actual script location in the rendered tick-store module and use its working directory. The source tree's q files are not necessarily at repository root.

Trace-join and reject/log-join proofs live under `scripts/proofs/`. They assert observed spans and negative controls on a configured rig. The historical Python tick-store report listed **24** cases; current totals must come from the selected run's reports. See [testing strategy](testing-strategy.md) and [counting rules](test-coverage.md).

## The market tape, replayed

The tape mode uses licensed historical observations as an external reference. Its existing resampler computes one median per symbol per interval, keeping the publication rate tied to configured cadence and universe size. The older corpus omitted trade-correction and sale-condition fields; a median reduces sensitivity to isolated prints but does not restore those fields or make the result reference-grade.

The historical deployment described February–March 2025 tape samples. That window must not be confused with another dataset or a current market feed. `source` and `asOf` retain the observation's provenance beside the simulation label.

```
replay_position = (now - epoch_start) * compression
```

The publisher derives playback position instead of persisting a cursor, so restart resumes on the same clock. Members only sequence the emitted events; they do not read the publisher's wall clock during deterministic apply.

Sampled prints also generate orders through dedicated replay accounts. Sides are inferred with a tick rule because the retained corpus does not reconstruct an NBBO. The rate is sampled rather than replaying every print. Reference ticks and orders share one clock; order IDs identify symbol/tape slot for idempotency. Orders outside the reference collar can be refused normally.

This is demonstration traffic, not a backtest or a statement of historical execution quality. Keep replay account effects distinct from operator effects and preserve dataset access restrictions. Existing ingest/Parquet support does not authorize converting or publishing another raw corpus.
