# ADR-016: Journal-Authoritative Storage with an Async Read-Model Projector

## Status
Proposed

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
