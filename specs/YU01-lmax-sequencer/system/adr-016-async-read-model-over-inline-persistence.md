# ADR-016: Journal-Authoritative Storage with an Async Read-Model Projector

## Status
Accepted — implemented; projector decoupling + write-path refinement 2026-06-25 (see below)

## Date
2026-06-09

## Context

State 009 treats the shared database as the system of record: order-matcher saves every order mutation
inline via JPA, and the trade pipeline writes trades/positions on the critical path. DB latency, locks,
and outages therefore sit directly on the trading path. With ADR-014 in place, every input event is
journaled and replicated before the BLP processes it — making the journal a complete, ordered,
durable record of everything that ever changed trading state.

## Decision

1. The sequenced input journal is the authoritative store for trading state; BLP state is a pure
   function of it (snapshot + replay rebuilds it; FR-09B04, FR-09B16).
2. All BLP results flow through a single-producer output disruptor with parallel marshaller, NATS
   publisher, and read-model projector handlers (FR-09B20).
3. Database writes move to the projector: batched on `endOfBatch`, bounded by
   `output.projector.batch-size`/`flush-interval-ms`, idempotent from a persisted `seq` checkpoint,
   and fully rebuildable by re-projecting the journal (FR-09B22, FR-09B23).
4. The `OrderBook`/trade/position schema from 009 is preserved bit-for-bit (NFR-09B11); only the
   writer changes.
5. The NATS bridge reproduces 009's subjects and payload shapes exactly, so the UI and all stream
   consumers are unchanged (FR-09B21); `securityId -> ticker` and fixed-point -> decimal conversions
   happen only in output handlers (FR-09B25).
6. A slow or down DB/NATS never stalls matching beyond the bounded output ring; the affected handler
   lags and catches up (FR-09B24).

## Consequences

- The user acknowledgement path ends at input durability + replication; NATS fan-out and DB projection
  are measurably independent of it (SC-09B15).
- DB outage degrades to projector lag instead of trading failure; read-model loss is a rebuild, not a
  data loss (SC-09B10, SC-09B11).
- Eventual consistency becomes explicit: push-fed UI state slightly leads the relational read-model.
  This is documented behavior, bounded by projector lag metrics.
- Cost: a projection checkpoint to operate, rebuild tooling to maintain, and the conceptual shift that
  "the database" is no longer the source of truth.

## Implementation refinement (2026-06-25)

Decision 3 was first implemented as a batched handler that wrote to the database *inline on its
output-ring consumer thread*. Measurement showed this still gated the engine: an output-ring consumer
holds its slot until `onEvent` returns, so the per-row JPA `merge` writes (~1.4k rows/s — latency-bound
on round-trips, not DB CPU; Postgres ran ~0.5 cores) backed the ring up and stalled the BLP under
sustained load — the opposite of decision 6. Three refinements make decisions 3 and 6 hold in practice:

1. **Decoupled projector queue (FR-09B44).** The on-ring handler now only converts each event and
   enqueues it (O(1)) into a dedicated bounded queue (`output.projector.queue-capacity`, default 1,000,000);
   a separate `projector-drain` thread performs the DB writes. DB lag becomes queue depth — bounded,
   monitored (`traderx_projector_queue_depth` / `traderx_projector_lag_seq` /
   `traderx_projector_enqueue_blocks_total`), and absorbed up to the queue capacity before any
   backpressure reaches the ring. A full queue blocks the enqueue (counted), degrading to the original
   bounded-ring behavior rather than dropping rows or growing unbounded. The drain is single-threaded
   FIFO, so the database stays a consistent prefix; the `projectedSeq` watermark advances only after a
   committed flush (the recovery resume point).
2. **Insert-only batched writes (FR-09B45).** Trades are append-only, so the drain writes them with one
   multi-row `INSERT … ON CONFLICT (id) DO NOTHING` per flush — removing `merge`'s per-row SELECT while
   staying idempotent for journal replay. Order/position writes enable Hibernate JDBC batching
   (`reWriteBatchedInserts`).
3. **Async NATS publish (FR-09B46).** The NATS bridge no longer `flush()`es per message (a broker
   round-trip that made it a second per-item gate alongside the projector, ~2k/s); the client writer
   flushes asynchronously.

Measured arc on the demo stack (single laptop): sustained end-to-end booking rose ~1,060/s (inline
projector, sync NATS) → ~2,045/s (decoupled projector alone, NATS-gated) → ~3,720/s (decoupled + async
NATS + insert-only/batched writes), while the in-memory engine bursts to ~34k/s until the queue fills.
The widened eventual-consistency window (consequence 3) is now an explicit, bounded, monitored quantity:
a full queue is ~1 minute of DB lag at the measured drain rate. The remaining sustained ceiling is the
per-row JPA `merge` on the order/position tables; upserting those (`ON CONFLICT … DO UPDATE`) and
de-duping order rows per flush is the identified next step.

Realized in `generation/runtime-overrides/order-matcher/` (the overlay the render pass applies last);
the captured patchset is intentionally not re-derived, as the overlay supersedes it.
