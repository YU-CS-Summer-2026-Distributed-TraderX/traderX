# ADR-030: Warm-Path Algo Engine Service with JetStream Event-Sourced State

**Status:** Accepted
**Date:** 2026-07-10
**State:** `YU08-execution-algo-engine` (parent `YU07-historical-tick-store`)

## Context

A parent order sliced into child orders over a time window needs multi-second-to-minute scheduling
state (which buckets are due, submitted, filled) that must survive a process crash. `order-matcher`
is the one component in this project explicitly built to exclude anything but sequenced input events
from its thread (`LMAX-BLP.md`), and its own crash-recovery mechanism (journal + snapshot) is scoped
to order/position/risk state, not to an unrelated scheduling concern. Whatever holds the algo
engine's state must not require a synchronous fetch from another service at boot (that would leave a
window where a restarted engine either serves stale progress or blocks indefinitely on a dependency).

## Decision

`execution-algo-engine` is a standalone Spring Boot service (same shape as `account-service`), not a
BLP feature. Its own state — the parent-order schedule and observed fills — is event-sourced over a
dedicated JetStream stream (`TRADERX_ALGO_ENGINE`, subject `algo.events.>`, file storage), using the
same `io.nats:jnats:2.20.5` client and stream-bootstrap idiom YU04's `JetStreamControlFeedPublisher`/
`ControlFeedSubscriber` already established for durable, replayable state in this project. Every
state transition is appended as one JSON event before it is applied to in-memory state; a single
durable pull consumer (`algo-engine-state`, `DeliverPolicy.All`, `AckPolicy.Explicit`) both rebuilds
state at boot (replaying every unacked message) and receives new events live. An event is acked only
after being applied, so a crash between append and ack simply redelivers and reapplies it — safe
because every event is idempotent (it fully replaces the affected bucket's fields).

## Alternatives Considered

- **A database table for parent-order/bucket state:** rejected. This state has no existing
  datastore of its own, and a table plus an ORM would duplicate the append-only,
  replay-to-rebuild capability JetStream already provides — the same reasoning ADR-021 used to
  reject dual-write in favor of a single durable log.
- **In-memory only, no crash recovery:** rejected outright by the parent handoff's explicit
  requirement (crash resumes from the log, not a restart of the schedule) and by NFR-AE03.
- **Folding scheduling into `order-matcher` itself:** rejected — see Context; this is exactly the
  class of multi-second stateful work the BLP's single-threaded design excludes by construction.
- **A custom file-based journal (mirroring the BLP's own journal format):** rejected. The BLP's
  journal is purpose-built for the ring's binary event format and byte-offset-keyed snapshot
  recovery (YU03 research.md); reusing or imitating it here would mean hand-rolling a second journal
  format for a service with far lower throughput requirements, when JetStream already gives the same
  durability guarantee with the client this project already depends on.

## Consequences

**Positive:** no new infrastructure component (JetStream is already deployed for YU04's control
feeds); the append-before-apply/ack-after-apply pattern gives exactly-once-effective replay without
a distributed transaction; state rebuild requires no synchronous call to any other service, so a
restarted engine's readiness depends only on the NATS broker being reachable.

**Costs:** the event stream grows unboundedly with parent-order volume (no pruning is implemented in
this state — every event since the stream's creation is replayed on every restart); acceptable at
this project's demo/research order-submission volumes, and prunable later the same way ADR-021 notes
control-feed outbox rows are prunable at a watermark, without changing the event schema.

## Validation

- Kill `execution-algo-engine` mid-schedule (after some buckets submitted, before parent completion),
  restart it, confirm `GET /algo/orders/{id}` shows the exact pre-crash bucket state and the
  scheduler resumes submitting remaining buckets without re-submitting already-submitted ones.
- Confirm the durable consumer's ack only occurs after the in-memory model reflects the event (unit
  test on `AlgoEventStore`).
