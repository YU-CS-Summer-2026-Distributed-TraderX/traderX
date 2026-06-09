# Functional Delta: 009b-lmax-sequencer-architecture

Parent state: `009-order-management-matcher`

Document only functional behavior changes introduced by this state. External behavior is intentionally
parity-locked to `009`; the delta is in how that behavior is produced.

## Added

- Single sequenced input stream: all state-mutating inputs (order create/cancel/force-fill, price
  ticks, market trades) enter through one Gateway, receive a strictly monotonic global sequence number,
  and are written into a pre-allocated input disruptor ring (FR-09B01, FR-09B06, FR-09B08).
- Parallel input handlers — Journaler (durable append), Replicator (replica/DR stream), Un-marshaller
  (SBE decode) — with the BLP gated behind a sequence barrier at the minimum of the three, so every
  event the BLP acts on is already durable, replicated, and decoded (FR-09B03, FR-09B04).
- Single-threaded, in-memory, event-sourced Business Logic Processor fusing matching + trade booking +
  position keeping into one thread; in-memory order books, positions, last prices, and validation
  caches (FR-09B10..FR-09B12).
- Typed output events (`OrderAccepted|Rejected|PartiallyFilled|Filled|Canceled`, `TradeBooked`,
  `PositionUpdated`) on a single-producer output disruptor with parallel Marshaller / NATS Publisher /
  Read-model Projector handlers (FR-09B15, FR-09B20).
- Asynchronous request/response event pattern for BLP cache misses (e.g.
  `AccountLookupRequest/Response`) replacing blocking lookups (FR-09B11).
- Event sourcing operability: snapshot + journal replay recovery, JIT warm-up replay before going
  live, nightly bounce window, deterministic replay for diagnostics (FR-09B16, NFR-09B05).
- Replication and warm-standby failover: follower BLPs consume the identical replicated input stream in
  lock-step with output suppressed; promotion at current sequence on leader failure (FR-09B30..32).
- No-GC conformance gate (cross-cutting): Epsilon-GC allocation gate, banned-API static check,
  penny-parity fixture (see `requirements/no-gc-conformance.md`).

## Changed

- Matching trigger: tick-driven polling (`@Scheduled`, up to ~1 s latency) becomes event-driven; every
  order/cancel/force-fill/price event is evaluated immediately on arrival (FR-09B02). The auto-fill
  *policy* is unchanged; "on every matcher tick" semantics from FR-01309 are now "on every relevant
  sequenced event".
- Concurrency model: `ReentrantLock orderMutationLock` and atomic counters are removed; the BLP is the
  sole writer of order books and positions (NFR-09B04).
- Validation path: trade-service's three blocking REST calls (ticker, account, price) become in-memory
  cache lookups at the Gateway/BLP (FR-09B12).
- Booking path: matched fills no longer `POST /trade/` back through trade-service inside the match
  loop; the BLP books in memory and emits `TradeBooked`/`PositionUpdated` output events. The trading
  hot path's booking/position-keeping role of `trade-processor` is fused into the BLP (FR-09B08,
  FR-09B10).
- Price consumption: the matcher no longer subscribes to pricing subjects out-of-band; price ticks are
  sequenced `PRICE_TICK` input events in the same totally-ordered stream as orders (FR-09B06).
- Source of truth: the input journal becomes authoritative; the `OrderBook` table and trade/position
  rows become an async, batched, checkpointed, rebuildable read-model (FR-09B22, FR-09B23).
- Numeric representation: prices/quantities are `long` fixed-point and securities `int securityId` on
  the hot path; `BigDecimal`/`String` only at the edges, with penny parity locked to `009` (FR-09B05).
- Order ID and timestamp derivation: from the global sequence and event-carried time rather than
  `String.format` counters and `Instant.now()` (FR-09B14); externally rendered shapes are unchanged.
- State-identity branding: the state-ui-metadata overlay inherited from `009` (FR-01317 header/About/
  status state-id rendering) is updated to identify `009b-lmax-sequencer-architecture` (FR-09B42).
  This is a generation-time metadata change only; no component, route, or interaction changes.

## Removed

- `@Scheduled` matcher polling loop and the `order.matcher.tick-ms` configuration key.
- `ReentrantLock orderMutationLock`; `AtomicInteger`/`AtomicLong` matcher counters.
- Blocking REST calls from the matcher hot path (`order.matcher.price-service-url`,
  `order.matcher.trade-service-url` configuration keys).
- Inline JPA writes and per-tick JPA queries from the match path (replaced by in-memory books + the
  Projector; the schema itself is preserved).
- No external (REST/WS/NATS/UI) behavior is removed.

## Flow Impact

- `F2` (submit and process trade): market trades now flow Gateway -> sequenced `TRADE_NEW` event ->
  BLP booking -> output events -> NATS fan-out + projected persistence. Same external observables.
- `F4` (realtime updates): unchanged subjects and payloads; producer moves to the output-disruptor NATS
  bridge. Push-over-polling contract from ADR-013 is re-affirmed.
- `F5` (order management and matching lifecycle): internal execution collapses from
  validate -> publish -> book -> match -> POST-back into a single-threaded in-memory event handle;
  lifecycle statuses and policy unchanged.
- `F6` (order ticket + account orders blotter cancel workflow): unchanged at the UI/API contract.
- New flow: `F7` (event-sourced recovery) — snapshot load -> journal replay -> warm-up -> live.
- New flow: `F8` (failover) — leader loss -> follower promotion at current sequence -> output
  un-suppressed -> Gateway re-targets.
