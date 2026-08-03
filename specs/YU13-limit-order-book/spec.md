# Feature Specification: Crossing Limit-Order Book

**Feature Branch**: `YU13-limit-order-book`
**Created**: 2026-07-20
**Status**: In implementation
**Input**: Real limit-order-book direction brief, parented on `YU12-aeron-cluster`

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

## Addendum: distributed tracing across the consensus boundary

Added to this state after its original implementation. Tracing instruments the clustered
`order-matcher` and its gateway, which is where this state's code already lives, so it extends
`YU13` rather than standing as a state of its own. It is bound by one constraint above all others:
**it may not change what the trading path costs, and it may not put a byte into the replicated
log.** The observability platform itself — OTel Collector, Tempo, Loki, Prometheus, Grafana — has
shipped in the Kubernetes runtime since state `007`; what was missing was anything emitting to it,
and any scrape of the cluster tier.

This state also carries the leader-side `KdbTapWriter` capture tap, for the reason above: it sits
in the clustered `order-matcher`. The store it feeds is specified in the
`YU07-historical-tick-store` pack, which owns the tick store.

**Both hold on generated `YU13`, `YU14` and `YU15`, and getting there took a repair.** The trace
classes and the gateway-side spans sit in this state's `ClusterGatewayMain`, which is the operative
copy on every descendant. The member-side spans and the tap's construction both sit in
`MatchingEngineClusteredService` — a class `YU13`, `YU14` and `YU15` each override, so only a
state's own copy is operative when that state is generated, and each copy has to carry the
capability itself. `YU14`'s did not: it was cut from a pre-tap, pre-tracing `YU13` and never
re-synced, which left generated `YU14` unable to compile at all once the gateway began calling
`spanSink()`. See the layer-coverage note in `generation/implementation-status.md` for what the
repair was and what it proves.

### Functional Requirements

- FR-TR01: One order SHALL produce one distributed trace spanning both tiers — the gateway's
  submit and queue spans, the consensus black box, and the member's commit and apply spans.
- FR-TR02: Trace identity, the member's parent span, and the head sampling verdict SHALL be
  DERIVED on each tier by a pure function of a field the replicated log already carries (the
  client idempotency key). No trace context SHALL be added to any sequenced message, and no
  schema change SHALL be made on tracing's behalf.
- FR-TR03: The member SHALL derive that key before the sequenced generator overwrites `orderRef`,
  so both tiers hash identical input and independently reach the same trace id.
- FR-TR04: A rejected order SHALL be traced whatever the sampling verdict said, with both tiers
  reaching that decision independently from committed data — error sampling is decided at the
  head, because a collector's tail sampling cannot recover a span head sampling never emitted.
- FR-TR05: A log line SHALL join its trace by carrying the derived id in the line itself, not by a
  label on the log stream.
- FR-TR06: Cluster members SHALL be scraped per pod through the headless service, since role,
  applied sequence and next order reference are per-member facts that a Service-level scrape
  would round-robin into nonsense.
- FR-TR07: Tracing SHALL be off unless explicitly enabled (`OTEL_TRACES=1`); otherwise every call
  site SHALL hold a null reference.

### Non-Functional Requirements

- NFR-TR01: A producer thread — REST or FIX submit, the gateway owner thread, a member's apply
  thread — SHALL do no more than copy a fixed record into a pre-allocated ring buffer and return.
  No lock, no allocation, no I/O, and no backpressure path back to the caller. A full ring SHALL
  drop the span and increment a counter.
- NFR-TR02: All formatting, batching, HTTP and retry SHALL happen on a daemon thread that no order
  ever touches; a collector outage SHALL cost a counter, not a millisecond.
- NFR-TR03: The trace path SHALL allocate zero bytes on the gated hot path, holding under the same
  allocation gates and the Epsilon no-GC run as the engine. The OpenTelemetry SDK SHALL NOT be
  used, since its API allocates per span; OTLP/HTTP is emitted directly with a JSON body, adding
  no dependency.
- NFR-TR04: The derivation SHALL be one-way and read-only. It SHALL NOT be written back, encoded
  into any output event, or branched on by the engine — deleting the tracing code SHALL leave
  every member's output byte-identical.

### Success Criteria

- SC-TR01: One order produces one trace whose gateway root, queue span and consensus span carry
  the member's commit and apply spans as children, proven end to end against a deployed cluster.
- SC-TR02: A rejected order's log line resolves to that order's own trace by the derived id,
  proven end to end.
- SC-TR03: Both tiers are unit-proven to reach the same trace id, parent span and sampling verdict
  from the same key, and the sink is unit-proven to drop rather than block when its ring is full.
