# Feature Specification: LMAX Sequencer Architecture (Trading Hot Path)

**Feature Branch**: `009b-lmax-sequencer-architecture`  
**Created**: 2026-06-09  
**Status**: Draft  
**Input**: Transition delta from `009-order-management-matcher`, derived from the design proposals `LMAX-SEQUENCER-ARCHITECTURE.md`, `LMAX-INPUT-DISRUPTOR.md`, `LMAX-BLP.md`, `LMAX-OUTPUT-DISRUPTOR.md`, and `LMAX-NO-GC-JAVA.md` (repo root)

This state re-architects the **trading hot path** of state `009` around the LMAX architecture: one
globally **sequenced**, **journaled**, **replicated** input stream feeding a **single-threaded,
in-memory Business Logic Processor (BLP)**, wired with LMAX Disruptor ring buffers and engineered for
**zero steady-state allocation (no-GC)**. Every external contract from `009` (REST/WS endpoints, NATS
subjects, payload shapes, UI behavior, `OrderBook` schema) is preserved verbatim; only the execution
model changes. `account-service`, `position-service`, `people-service`, `reference-data`, the Angular
UI, ingress, and the LGTM observability stack remain on the current stack.

Requirement IDs use the `09B` block (`FR-09Bxx`, `NFR-09Bxx`, `SC-09Bxx`). Cross-cutting no-GC
conformance requirements use the `NGC` namespace defined in `requirements/no-gc-conformance.md`.

## User Stories

- As a trader, I want order submit/cancel and matcher fills acknowledged with deterministic
  sub-millisecond in-node latency, so the order workflow remains instantaneous under load instead of
  waiting up to a full matcher polling tick.
- As a trader, I want every existing view (trade blotter, position blotter, account order blotter,
  admin order blotter, order ticket) to keep working with zero front-end change, so the re-architecture
  is invisible at the UI contract.
- As a platform engineer, I want every state-mutating input (orders, cancels, force-fills, price ticks,
  market trades) on one totally-ordered, journaled stream, so any production incident can be replayed
  deterministically in a dev environment.
- As an operations user, I want snapshot + journal-replay recovery and warm-standby failover, so a
  matcher restart or node loss does not lose or reorder accepted orders.
- As a maintainer, I want the no-GC allocation contract enforced by automated CI gates (Epsilon GC,
  static banned-API checks, penny-parity fixtures), so the latency properties are proven rather than
  assumed.
- As a maintainer, I want this transition to remain spec-first, with the sequencer/ring/BLP/read-model
  contracts documented before code generation.

## Functional Requirements

### Sequenced input stream (Sequencer + Input Disruptor)

- FR-09B01: All state-mutating inputs (order create, cancel, force-fill, price ticks, and market
  trades) SHALL enter through a single Gateway, be assigned a strictly monotonic global sequence
  number, and be written into a single pre-allocated input ring buffer (power-of-two capacity).
- FR-09B02: The matcher SHALL be event-driven. The `@Scheduled` polling tick from `009`
  (`order.matcher.tick-ms`) SHALL be removed; matching reacts to event arrival.
- FR-09B03: Each input event SHALL be processed by three parallel input handlers — Journaler (durable
  append), Replicator (stream to replicas), Un-marshaller (decode) — with the BLP gated behind a
  sequence barrier at `min(journaler, replicator, unmarshaller)`, so every event the BLP acts on is
  already durable, replicated, and decoded.
- FR-09B04: Each input event SHALL be journaled before the BLP processes it; the journal is the
  authoritative system of record for trading state.
- FR-09B05: Prices and quantities SHALL be carried as `long` fixed-point (global scale ×1,000,000) and
  securities as `int securityId` on the hot path; `BigDecimal`/`String` conversions SHALL occur only at
  the edges (Gateway in, output handlers out).
- FR-09B06: Price ticks SHALL enter the sequenced input stream as `PRICE_TICK` events through the
  Gateway rather than out-of-band NATS subscriptions inside the matcher; orders and prices form one
  totally-ordered stream.
- FR-09B07: Ring-full conditions SHALL apply bounded backpressure at the producer claim (no unbounded
  queuing); remaining capacity SHALL be observable as a metric.
- FR-09B08: Market trades from the trade ticket SHALL enter the sequenced stream as `TRADE_NEW` input
  events via the Gateway, so trade booking and position keeping share the single-writer path (the
  inline booking role of `trade-processor` on this path is fused into the BLP).
- FR-09B09: `trade-service` SHALL remain the validating edge for market trades — validating the ticker
  (`reference-data`) and account (`account-service`) exactly as in `009`/`008` — and on success SHALL
  forward the validated trade to the order-matcher Gateway (which sequences it as `TRADE_NEW`) instead
  of publishing to the `/trades` NATS subject. The `POST /trade/` request/response contract (HTTP 200
  echoing the trade) is unchanged (FR-09B40) and the forward is fire-and-forget (booking is async on
  the BLP, matching `009`'s publish semantics). The client does not supply a price; the execution price
  is stamped by the BLP (FR-09B17), subsuming `008`'s `trade-service` price-stamping role (FR-1002).

### Business Logic Processor (BLP)

- FR-09B10: Matching, trade booking, and position keeping SHALL execute on one thread, entirely in
  memory, within the handling of a single input event (no network hops or DB access between match,
  book, and position update).
- FR-09B11: The BLP SHALL make no blocking external calls (no REST, no JPA/DB, no NATS); anything not
  in memory SHALL be resolved via asynchronous request/response event pairs.
- FR-09B12: Validation that `009` performed via blocking REST (`account`, `stocks`, latest `price`)
  SHALL be served from in-memory caches kept fresh from the event streams and warmed at startup.
- FR-09B13: The `009` autofill policy and lifecycle SHALL be preserved exactly: in-the-money test
  (`Buy: marketPrice <= limitPrice`, `Sell: marketPrice >= limitPrice`), remaining `< 1000` fills
  fully, otherwise half (rounded up); statuses `NEW | PARTIALLY_FILLED | FILLED | CANCELED | REJECTED`.
- FR-09B14: The BLP SHALL be deterministic: no wall-clock reads, no unordered-collection iteration, no
  RNG/UUID on the hot path; timestamps are carried in events (`ingressNanos` stamped at the Gateway)
  and order IDs derive from the global sequence. Trade IDs SHALL derive from a BLP-assigned monotonic
  trade number (`trd-09b-<n>`, no `UUID`/RNG), warm-seeded above the maximum persisted trade id at
  startup so ids are replay-stable and never collide across restarts (replacing `009`'s
  `UUID.randomUUID()`); both the projector and the NATS bridge derive the same id from the carried
  trade number.
- FR-09B15: The BLP SHALL emit typed output events (`OrderAccepted`, `OrderRejected`,
  `OrderPartiallyFilled`, `OrderFilled`, `OrderCanceled`, `TradeBooked`, `PositionUpdated`) into the
  output ring as its sole side effect channel, rather than POSTing trades or writing the DB inline.
  `TradeBooked` SHALL carry the stamped execution price (FR-09B17) and `PositionUpdated` the resulting
  net quantity and weighted average cost basis (FR-09B18); a fill SHALL emit its order update,
  `TradeBooked`, and `PositionUpdated` as one paired ring claim. The `009` output-ring trade-submit
  handler that re-POSTed fills to `trade-service` SHALL be removed.
- FR-09B16: BLP state SHALL be recoverable via snapshot + journal replay to the last journaled
  sequence, followed by a JIT warm-up replay before going live. Warm-start from the persisted
  read-model SHALL restore the in-memory order book, the net positions (quantity AND weighted average
  cost basis), and the trade-number counter, so the single-writer BLP resumes consistent with durable
  state.
- FR-09B17: The BLP SHALL stamp every booked trade with an execution price: an order fill books at its
  fill execution price (last market price, or the limit price on a force-fill before any tick), and a
  `TRADE_NEW` market trade books at the security's last sequenced market price (`PRICE_TICK`),
  defaulting to `0` (rendered `0.000`, never null) when no tick has been seen. The price is carried on
  `TradeBooked` and rendered to the `TRADES.price` column and NATS payload at the edge, preserving the
  `008` trade-price contract (FR-1001/FR-1003).
- FR-09B18: The BLP SHALL keep, per `(accountId, securityId)`, the net quantity AND the volume-weighted
  average cost basis, updated on every fill and market trade using `008`/`009`'s running-average formula
  (`newAvg = (oldAvg*oldQty + execPx*signedQty) / newQty`, reset to `0` when the net position is flat),
  computed in `long` fixed-point on the hot path. The result is carried on `PositionUpdated` and
  rendered to `POSITIONS.quantity` / `POSITIONS.averageCostBasis` at the edge, preserving the `008`
  position contract (FR-1004). The position store SHALL be an allocation-free primitive structure
  (no `HashMap`/autoboxing/`BigDecimal`), subject to the no-GC and banned-API gates (NFR-09B02,
  SC-09B13).

### Output Disruptor and read-model

- FR-09B20: All BLP results SHALL be published into a single-producer output ring
  (`ProducerType.SINGLE`) and fanned out by parallel Marshaller, NATS Publisher, and Read-model
  Projector handlers.
- FR-09B21: The NATS Publisher SHALL reproduce the exact `009` subjects and payload shapes —
  `/orders`, `/accounts/{accountId}/orders`, `/trades`, `/accounts/{accountId}/trades`,
  `/accounts/{accountId}/positions` — so all UI consumers work unchanged. A single `TradeBooked` SHALL
  be published to BOTH `/trades` (global, formerly produced by `trade-service`) and
  `/accounts/{accountId}/trades` (formerly produced by `trade-processor`), and a `PositionUpdated` to
  `/accounts/{accountId}/positions`. The published `Trade` payload SHALL include the execution `price`
  and state `Settled`, and the `Position` payload SHALL include `averageCostBasis`, byte-compatible with
  the `008`/`009` shapes; `securityId -> ticker` and fixed-point -> 3dp decimal rendering happen here at
  the edge (FR-09B25).
- FR-09B22: Database writes SHALL move to the async, batched Read-model Projector off the
  acknowledgement path; the `OrderBook` table and trade/position rows become a read-model projected
  from output events, preserving the `009` schema contract. The order-matcher Projector SHALL be the
  SOLE writer of the `OrderBook`, `TRADES`, and `POSITIONS` tables (replacing `trade-processor`'s inline
  JPA on this path), writing `TRADES.price` and `POSITIONS.averageCostBasis` from the carried output
  events. `trade-processor` remains deployed for its REST read endpoints and smoke-suite health parity
  but books no trades and writes no positions on the order/market-trade path; the `009`
  `fill -> trade-service -> /trades -> trade-processor` booking round-trip is removed (superseding
  FR-01310).
- FR-09B23: The read-model SHALL be rebuildable by re-projecting the journal (recovery and schema
  migration path), resuming idempotently from a persisted projection checkpoint (`last projected seq`).
- FR-09B24: A slow or unavailable read-model DB or NATS bus SHALL NOT block matching beyond the
  bounded output ring; the affected handler lags and catches up after recovery.
- FR-09B25: `securityId -> ticker` and `long fixed-point -> decimal` conversions SHALL occur in the
  output handlers (the edge), never in the BLP.

### Replication and failover

- FR-09B30: The replicated input stream SHALL support follower BLPs (second node in the primary
  site plus an optional DR site) that consume the identical sequenced stream in lock-step and suppress
  output until promoted.
- FR-09B31: On leader failure, a follower SHALL be promotable at its current sequence without cold
  replay (warm-standby failover); promotion SHALL NOT lose or reorder any journaled input.
- FR-09B32: The demo/`C2` runtime profile MAY run a single replica with replication in loopback/stub
  mode; the replication contract SHALL still be exercised by tests in that profile.

### Contract preservation

- FR-09B40: External contracts from `009` SHALL be unchanged: order/trade/position REST and WS
  endpoints and response shapes, NATS subjects and payload contracts (including FR-01311/FR-01312
  semantics), UI behavior (order ticket, blotters, admin view per FR-01303..FR-01305), and the API
  explorer / pub-sub inspector surface from FR-01315.
- FR-09B41: Existing `009` order metrics (`traderx_orders_open_total`, `traderx_orders_unfilled_total`,
  `traderx_orders_pending_by_side`, `traderx_order_events_total`, `traderx_order_match_latency_seconds`,
  `traderx_order_book_age_seconds`) SHALL be retained, now sourced from in-memory BLP state;
  `traderx_order_match_latency_seconds` becomes a real measurement (no zero-filled placeholder).
- FR-09B42: The inherited state-aware header contract (`009` FR-01317) SHALL render this state's
  identity: generated snapshots update the state-ui-metadata overlay so the UI title, About page, and
  status view identify `009b-lmax-sequencer-architecture` (not the parent state id). This is the only
  permitted UI-visible change in this state.

## Non-Functional Requirements

- NFR-09B01: Latency budgets (performance profile, per `LMAX-SEQUENCER-ARCHITECTURE.md` §11): BLP
  business logic p99 `< 25 µs`; output ring + marshal p99 `< 20 µs`; in-node compute (Gateway ingest to
  output emit, excluding network/durability acks) p99 `< 150 µs`; end-to-end including durable +
  replicated acknowledgement p99 `< 3 ms`. Reported as full HdrHistogram distributions
  (p50/p99/p99.9/max), never means only.
- NFR-09B02: Zero steady-state allocation on the hot path (Gateway encode, input ring, BLP, output ring
  emit), per the no-GC conformance profile `NGC-01..NGC-08` in `requirements/no-gc-conformance.md`,
  enforced by an Epsilon-GC allocation gate in CI.
- NFR-09B03: Determinism — an identical journal SHALL produce identical BLP state and identical emitted
  output events on replay.
- NFR-09B04: Single-writer discipline — no locks or atomics on the hot path; the BLP is the sole writer
  of order books and positions; `orderMutationLock` and `AtomicInteger/AtomicLong` counters from `009`
  are removed.
- NFR-09B05: Recovery — snapshot + journal replay restores matcher state to the last journaled sequence
  with restart inside the target window (`< 1 minute`), including JIT warm-up before going live.
- NFR-09B06: Run profiles — a `demo`/`C2` profile (BlockingWaitStrategy, no core pinning, no hugepages,
  single replica) that is the container default, and a `perf` profile (BusySpinWaitStrategy on
  BLP/Journaler, pinned isolated cores, ZGC/Shenandoah, large pages, replicas + DR) documented for bare
  metal. Both profiles MUST pass the allocation gate; latency budgets in NFR-09B01 apply to `perf`.
- NFR-09B07: Backpressure and throughput — sustained demo load runs with bounded ring backpressure and
  no GC pauses; ring remaining-capacity and sequence-lag gauges exported.
- NFR-09B08: Observability — all input/BLP/output/no-GC metrics in
  `requirements/nonfunctional-delta.md` are exported to Prometheus, scraped per the `009` mandatory
  scrape policy (NFR-01308), and represented in provisioned Grafana dashboards (ring headroom, sequence
  lag, journal/replication latency, BLP event latency, egress latency, projector lag, allocation rate
  with alert at `> 0`, GC pause panel).
- NFR-09B09: Convergence level `C2` is preserved: `.github/workflows/build-and-publish.yml`, image
  namespace `ghcr.io/finos/traderx-c2/<component>` with commit-SHA + `latest` tags, GHCR run bundle,
  and deployment bundle under `runtime/deploy/` per NFR-01309..NFR-01311 and NFR-01318..NFR-01319.
- NFR-09B10: Inherited stacks remain intact: `007` LGTM observability, `008` pricing/NATS contracts,
  and the ADR-013 push-over-polling realtime model (NFR-01314/NFR-01315) are unchanged.
- NFR-09B11: The generated `database/initialSchema.sql` `OrderBook` contract (NFR-01312/NFR-01313)
  remains satisfied; rows are now written by the Read-model Projector instead of inline JPA on the
  match path.
- NFR-09B12: New dependencies (Disruptor, Agrona, SBE, Chronicle Queue / Aeron, OpenHFT Affinity,
  HdrHistogram, JMH) are pinned to latest CVE-clean releases and pass the repo dependency CVE gate.

## Success Criteria

- SC-09B01: Generation hook exists and is runnable
  (`pipeline/generate-state-009b-lmax-sequencer-architecture.sh`).
- SC-09B02: State smoke test path is defined
  (`scripts/test-state-009b-lmax-sequencer-architecture.sh`).
- SC-09B03: Functional parity with `009`: order create, account-filtered listing, user cancel, admin
  force-fill, auto-fill policy, and resulting trade/position updates produce identical REST/WS
  responses and NATS events to `009` for the same scenarios.
- SC-09B04: Penny parity — `long` fixed-point fill arithmetic matches `009`'s `BigDecimal` outcomes
  across a rounding fixture (no penny drift).
- SC-09B05: No-GC gate — `pipeline/validate-no-gc-conformance.sh` runs the hot path under
  `-XX:+UseEpsilonGC` with a small fixed heap and fails on any steady-state allocation; passes when
  allocation-free (SC-NGC-01).
- SC-09B06: Determinism — a captured journal replayed in a clean process yields identical BLP state and
  identical emitted output events.
- SC-09B07: Latency — HdrHistogram reports meet NFR-09B01 budgets on the `perf` profile, with jHiccup
  confirming no GC-induced tail spikes.
- SC-09B08: Recovery — snapshot + journal replay restores state to the last journaled sequence and the
  node restarts within the NFR-09B05 window.
- SC-09B09: UI parity — Angular blotters (trade, position, account orders, admin orders) update in real
  time via the unchanged subjects with no front-end change and no polling loops (re-validates
  SC-01311/SC-01313).
- SC-09B10: Decoupling — with the DB stopped, matching continues and the Projector catches up on
  recovery; with NATS stopped, matching continues and UI streams resume on reconnect.
- SC-09B11: Rebuild — dropping the read-model and re-projecting from the journal reproduces identical
  rows (idempotent from the projection checkpoint).
- SC-09B12: Failover — a follower BLP consuming the replicated stream is promoted after leader kill at
  the same sequence with no journaled input lost (full check on `perf` profile; loopback/stub contract
  check on `demo`).
- SC-09B13: Banned-API gate — a static/architectural check asserts hot-path packages contain no
  `BigDecimal`, `Instant.now()`/clock reads, `HashMap`/`ConcurrentHashMap`, stream pipelines,
  `String.format`, SLF4J parameterized logging, `RestTemplate`, or JPA references (SC-NGC-04).
- SC-09B14: `C2` — demo-profile images build, publish to `ghcr.io/finos/traderx-c2/<component>`, and
  run correctly without core pinning, hugepages, or isolated cores, while still passing the allocation
  gate in CI (SC-NGC-06).
- SC-09B15: Ack-path independence — measured order-acknowledgement latency is independent of DB and
  NATS latency (output fan-out and projection are off the acknowledgement path).
- SC-09B16: Generated snapshot branch and tag strategy are defined in the state catalog at
  implementation time; generated branch artifacts include the `C2` build/publish workflow and GHCR
  run-bundle assets.
- SC-09B17: Trade & position field parity — for the same order-fill and market-trade scenarios, booked
  trades carry the stamped execution `price` (`TRADES.price`, finite on `/trades` and
  `/accounts/{accountId}/trades`) and positions carry the volume-weighted `averageCostBasis`
  (`POSITIONS.averageCostBasis`) identical to `009`/`008`'s `trade-processor` outputs, extending the
  penny-parity fixture (SC-09B04) to price and cost basis.
- SC-09B18: Single-writer booking — submitting an order fill or a market trade books a trade and updates
  the position written solely by the order-matcher Projector, with `trade-processor` processing no
  trades on this path (no trade-booking activity) and positions not double-counted; the deterministic
  trade ids (`trd-09b-<n>`) remain stable and non-colliding across an order-matcher restart (warm-start
  from `POSITIONS` plus the seeded trade counter).
