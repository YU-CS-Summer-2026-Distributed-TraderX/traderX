# ADR-014: Sequenced Input Disruptor Replaces Matcher Poll and Lock

## Status
Proposed

## Date
2026-06-09

## Context

State 009's `OrderMatcherService` evaluates open orders on a `@Scheduled` tick (default 1000 ms),
guards all order mutation with a `ReentrantLock`, receives price ticks out-of-band via a NATS
subscription into a `ConcurrentHashMap`, and queries JPA per tick. This adds up to a full polling
interval of latency, lock contention and context switches, racy interleaving between price updates and
matching (mediated only by the lock), and continuous hot-path allocation.

The LMAX architecture (Fowler) removes all of these with one mechanism: every state-mutating input is
funneled through a single pre-allocated ring buffer, stamped with a strictly monotonic global sequence
number, journaled and replicated in parallel, and consumed by a single-threaded processor gated behind
a sequence barrier.

## Decision

1. All state-mutating inputs — order create/cancel/force-fill, price ticks, and market trades — enter
   one input disruptor ring via the Gateway and receive a global sequence number (FR-09B01,
   FR-09B06, FR-09B08).
2. The `@Scheduled` polling tick and `order.matcher.tick-ms` are removed; matching is event-driven
   (FR-09B02).
3. `orderMutationLock` is removed; the BLP is the sole writer of matcher state (NFR-09B04).
4. Three parallel input handlers (journaler, replicator, un-marshaller) run ahead of the BLP, which is
   gated at `min(J, R, U)` so every event it processes is already durable, replicated, and decoded
   (FR-09B03, FR-09B04).
5. Ring capacity is power-of-two and sized per the burst math in `data-model.md`; ring-full applies
   bounded producer backpressure with a remaining-capacity gauge (FR-09B07).
6. Wait strategy is profile-selected: `BlockingWaitStrategy` in `demo`/CI, `BusySpinWaitStrategy` on
   BLP/journaler in `perf`.

## Consequences

- Matching latency drops from up-to-a-tick to event-arrival; prices and orders are one totally-ordered
  stream, eliminating the price/match race by construction.
- Durability precedes processing: an acknowledged order is journaled and replicated before the BLP
  acts, enabling deterministic replay and warm failover (ADR-016 relies on this).
- The ring is the only contention point and is lock-free by construction; under overload the system
  degrades via bounded backpressure instead of unbounded queue growth.
- Cost: a new moving part (ring + handlers + sequence barrier), a busy-spun core in the perf profile,
  and ring-sizing as an explicit operational concern.
