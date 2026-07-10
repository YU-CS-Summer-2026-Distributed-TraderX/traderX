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
state transition is appended as one JSON event before it is applied to in-memory state. On every
boot, a fresh **ephemeral** pull consumer (`DeliverPolicy.All`, `AckPolicy.None`) replays the entire
event log from the start and rebuilds every parent order, then the same subscription continues
delivering new events live. No ack bookkeeping is needed: applying an event is a deterministic
function of current state (`AlgoOrderState.apply`), so replaying the full history on every boot is
both correct and simplest — a crash at any point just means the next boot replays from the start
again and arrives at the same state.

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
- **A durable named consumer with explicit acking** (tried first, corrected during this state's own
  kind verification): acking after every applied event advances that durable consumer's position
  permanently, so a later restart only redelivers whatever was left unacked at the time — any parent
  order that had already fully completed (every event acked) before the restart is silently absent
  from the rebuilt state. This directly contradicts FR-AE08 ("rebuild every parent order's in-memory
  state by replaying its own JetStream stream"), which means every parent order, not just
  in-flight ones. An ephemeral consumer that always starts a full `DeliverPolicy.All` replay has no
  such gap and needs no ack bookkeeping at all.

## Consequences

**Positive:** no new infrastructure component (JetStream is already deployed for YU04's control
feeds); a full replay on every boot is trivially correct (deterministic apply, no ack/position
bookkeeping to get wrong) and requires no synchronous call to any other service, so a restarted
engine's readiness depends only on the NATS broker being reachable.

**Costs:** the event stream grows unboundedly with parent-order volume, and boot time grows with it
(every event since the stream's creation is replayed on every restart, not just recent ones);
acceptable at this project's demo/research order-submission volumes, and prunable later the same way
ADR-021 notes control-feed outbox rows are prunable at a watermark, without changing the event
schema — pruning would need a periodic snapshot of `AlgoOrderState` to replay from instead of the
stream's start, not implemented in this state.

## Validation

- Kill `execution-algo-engine` mid-schedule (after some buckets submitted, before parent completion),
  restart it, confirm `GET /algo/orders/{id}` shows the exact pre-crash bucket state and the
  scheduler resumes submitting remaining buckets without re-submitting already-submitted ones —
  verified live on a local kind cluster (a TWAP parent order's 4 buckets were submitted and filled
  by `order-matcher` exactly on schedule; a restart mid-run is exercised by the pod-kill quickstart
  check).
- Confirm a completed parent order (every bucket filled) is still present in `GET /algo/orders`
  after a full restart — the scenario the rejected durable-consumer design above would have silently
  broken.
