# Feature Specification: Crossing Limit-Order Book

**Feature Branch**: `YU13-limit-order-book`
**Created**: 2026-07-20
**Status**: In implementation
**Input**: Real limit-order-book direction brief
(`docs/handoff/HANDOFF-OSFF-2-real-limit-order-book.md`), parented on `YU12-aeron-cluster`

## User Stories

- As the trading-platform owner, I want orders matched by genuine crossing against resting
  counterparty orders so an execution always has two sides at an agreed price, rather than a
  unilateral fill against a reference price.
- As a trader, I want price-time priority — better-priced resting orders execute first, and at
  equal prices the earlier order executes first — so queue position is earned and predictable.
- As a trader, I want my marketable order to execute at the resting order's price, so crossing
  the spread can only improve my price, never worsen it past my limit.
- As a trader, I want market orders to execute immediately against available depth and cancel any
  unfilled remainder, so a market order can never rest at an undefined price.
- As the risk owner, I want limit prices admitted only on the price grid and inside a banded
  window around the security's trading range, so malformed or fat-fingered prices reject
  deterministically before any exposure is reserved.
- As the availability owner, I want the entire resting book carried in the cluster snapshot so a
  restored or replacement member answers the next crossing order exactly as a never-restarted
  member — same fills, same FIFO, same identifiers.
- As the operations owner, I want gateway offer/ack accounting to stay exact when counterparty
  resting-order updates interleave on the shared egress stream, so submit acceptance counting
  does not skew under two-sided flow.

## Functional Requirements

- FR-LOB01: Each security SHALL carry a two-sided limit-order book of resting orders; an
  accepted limit order that does not cross SHALL rest at its price level's FIFO tail.
- FR-LOB02: A marketable order SHALL execute against resting opposite-side orders best price
  first and FIFO within a price level; each match step SHALL fill
  min(aggressor remaining, resting remaining) at the RESTING order's limit price.
- FR-LOB03: Both sides of every match SHALL receive an order update, a booked trade with its own
  trade sequence number, and a position update through the inherited output-event pipeline; the
  resting side's order update SHALL carry the resting-update flag.
- FR-LOB04: A market order (no limit price) SHALL execute immediately against available depth and
  SHALL cancel any unfilled remainder in place; market orders SHALL never rest. Risk validation
  of a market order SHALL price it at the last trade price, falling back to the opposite best;
  with neither available the order SHALL reject PRICE_MISSING.
- FR-LOB05: Price ticks SHALL NOT trigger fills. A tick SHALL update risk price-freshness state
  and SHALL seed the security's mark only while no trade has printed; once the book trades, the
  last trade price is the mark (ADR-051).
- FR-LOB06: Matching SHALL be deterministic from the committed consensus log alone: time priority
  IS consensus-log arrival order, and every member SHALL compute an identical book, identical
  fills, and identical trade sequence numbers from the identical log.
- FR-LOB07: Every egress ack SHALL carry a correlation class distinguishing a direct lifecycle
  response from a counterparty resting-order update; gateway offer/ack correlation SHALL count
  only direct acks.
- FR-LOB08: Limit prices SHALL be admitted only on the 0.001 price grid (reject INVALID) and
  inside the security's banded price window (reject PRICE_COLLAR), both checked before any risk
  reservation is taken.
- FR-LOB09: The cluster snapshot SHALL carry the book completely: band geometry in the header,
  each created book's band anchor, and open order rows in ascending order-reference order so
  restore reproduces each price level's exact FIFO; an open row the restored band cannot hold
  SHALL fail closed.
- FR-LOB10: Cancel of an open resting order SHALL unlink it from its price level in O(1);
  force-fill of an open order SHALL unlink it and execute the full remainder at the last trade
  price, falling back to its limit price when no price has printed.

## Non-Functional Requirements

- NFR-LOB01: The match operation itself — book add, cross, or market execution, measured directly
  around the engine apply on the BLP thread — SHALL be measured with a full-percentile
  nanosecond histogram (p50/p99/p99.9/p99.99/max) under closed-loop load with no coordinated
  omission, and recorded as the state's engine-latency artifact.
- NFR-LOB02: The steady-state hot path — including crossing, partial fills, market-order
  remainder cancel, and terminal-retention eviction — SHALL allocate exactly zero bytes on the
  producer, journaler, and BLP threads (NGC-01 extended to the crossing book), enforced by the
  allocation gates and the Epsilon no-GC run.
- NFR-LOB03: Booked throughput on the clustered path SHALL be re-measured on the crossing engine
  against the stored NFR-AC02 baseline (25,149 booked/s) with genuinely two-sided marketable
  flow; the number reported for this state SHALL come from the crossing engine, not from any
  prior auto-fill measurement.
- NFR-LOB04: Book memory SHALL be bounded and lazy: per-security level arrays allocate on the
  security's first order (log-driven, replica-identical), sized by the configured band; the
  band width and grid are config-identity values identical on every member.

## Success Criteria

- SC-LOB01: A marketable buy against a book holding asks at multiple price levels executes best
  level first, FIFO within each level, at each resting order's price — proven at the engine level
  and through the cluster ingress/egress path.
- SC-LOB02: The same committed input sequence applied to two engines produces identical recovery
  digests (book, positions, trade counter) — replay determinism holds for crossing flow.
- SC-LOB03: A member restored from a format-2 snapshot answers an identical post-restore crossing
  sweep with fills identical to the never-restarted source, including per-level FIFO order.
- SC-LOB04: All allocation gates (base, risk-gated, Aeron transport, cluster apply-path, and the
  Epsilon no-GC re-runs) pass against the crossing engine.
- SC-LOB05: A match-latency histogram artifact exists for the crossing engine reporting
  p50/p99/p99.9/p99.99/max in nanoseconds for resting inserts, limit crosses, and market orders.
- SC-LOB06: The full order-matcher test suite passes, with tick-triggered-fill scenarios
  rewritten as crossing scenarios that preserve each test's original proof intent.
