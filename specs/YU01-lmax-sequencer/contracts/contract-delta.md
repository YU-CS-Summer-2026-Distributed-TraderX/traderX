# Contract Delta: YU01-lmax-sequencer

Parent state: `009-order-management-matcher`

Document any API/event/schema changes for this state. Headline: **the external surface is frozen** —
this state's defining contract is that `009`'s OpenAPI, NATS, UI, and database-schema contracts are
preserved verbatim while the internal execution path is replaced. All additions below are internal
seams or metrics.

## OpenAPI Changes

- None. Order-management endpoints (`POST /orders`, `GET /orders`, `GET /orders/{orderId}`,
  `POST /orders/{orderId}/cancel`, `POST /orders/{orderId}/force-fill`), matcher health/metrics
  endpoints, and all trade/position/pricing endpoints keep the `009` paths, request/response shapes,
  and status semantics (FR-09B40).

## Messaging Contract

- Subjects and payload shapes are unchanged: `/orders`, `/accounts/{accountId}/orders`, `/trades`,
  `/accounts/{accountId}/trades`, `/accounts/{accountId}/positions`, `pricing.*`
  (see `system/messaging-subject-map.md`).
- Producer change (internal): order/trade/position subjects are now published by the output-disruptor
  NATS bridge instead of inline service code; `pricing.*` ticks are additionally consumed by the
  Gateway to inject sequenced `PRICE_TICK` input events. Payload byte-parity with `009` is a smoke
  gate (SC-09B09).

## Internal Contract Additions

- **SBE input schema** (`sbe/order-input.xml`): message `TradeEvent` — `seq:int64`, `type:uint8`
  (`ORDER_NEW|ORDER_CANCEL|FORCE_FILL|PRICE_TICK|TRADE_NEW`), `accountId:int32`, `securityId:int32`,
  `side:uint8`, `qty:int64`, `limitPx:int64` (×1e6), `priceTicks:int64`, `ingressNanos:int64`.
  Versioned `schemaId`/`version`; codecs generated at build time (`generateSbe` before `compileJava`).
- **SBE output schema** (`sbe/order-output.xml`): message `OutEvent` — `seq:int64`, `kind:uint8`
  (`ORDER_ACCEPTED|ORDER_REJECTED|ORDER_PARTIALLY_FILLED|ORDER_FILLED|ORDER_CANCELED|TRADE_BOOKED|
  POSITION_UPDATED`), `accountId:int32`, `securityId:int32`, `side:uint8`, `qty:int64`,
  `pxTicks:int64`, `remainingQty:int64`, `status:uint8`, `ingressNanos:int64`.
- **Journal format contract**: append-only `(seq, type, raw SBE bytes, ingressNanos)`; same bytes as
  the wire/ring (zero re-serialization); Chronicle Queue (demo) / Aeron Archive (perf) layout,
  retention, and roll policy documented with the implementation; schema-versioned for forward replay.
- **Snapshot contract**: serialized BLP state (books, positions, caches) + reflected `seq`.
- **Replication contract**: followers receive the identical sequenced input stream; follower output is
  suppressed until promotion; promotion occurs at the follower's current sequence (Aeron Cluster in the
  perf profile; loopback/stub in demo).
- **Request/response event contract** for BLP cache misses (e.g. `AccountLookupRequest` ->
  `AccountLookupResponse` re-entering as a sequenced input event); a timeout/failure is itself an event.
- **Projection checkpoint contract**: last projected `seq` persisted at
  `output.projector.checkpoint-path`; projection is idempotent from the checkpoint; full rebuild from
  the journal must reproduce identical rows (SC-09B11).
- **Symbol table contract**: `ticker <-> securityId` owned by the Gateway; `securityId -> ticker`
  rendering only in output handlers.

## Database Contract

- `database/initialSchema.sql` `OrderBook` table contract from `009` (NFR-01312/NFR-01313) is
  preserved bit-for-bit, including decimal(18,3) external precision. The writer changes from inline JPA
  to the batched Read-model Projector; the generated-state publish gate for the `OrderBook` schema
  continues to apply.

## Configuration Contract

Added keys (demo-safe defaults):

| Key | Default (demo) | Purpose |
| --- | --- | --- |
| `runtime.profile` | `demo` | `demo` / `perf` / `noGcTest`. |
| `disruptor.input.ring-size` | `65536` | Power-of-two input slots. |
| `disruptor.input.wait-strategy` | `blocking` | `blocking` / `yielding` / `busyspin`. |
| `disruptor.input.producer-type` | `multi` | Gateway + price feed. |
| `disruptor.output.ring-size` | `65536` | Power-of-two output slots. |
| `disruptor.output.wait-strategy` | `yielding` | Egress less latency-critical. |
| `journal.enabled` | `true` | Toggle the Journaler handler. |
| `journal.type` | `chronicle` | `chronicle` / `aeron-archive`. |
| `journal.path` | `./data/journal` | Durable log location. |
| `replication.enabled` | `false` | Demo stubs the Replicator (loopback). |
| `replication.endpoints` | (empty) | Replica/DR addresses (perf). |
| `price.input.via-ring` | `true` | Price ticks through the ring (vs `009` out-of-band). |
| `blp.books.max-securities` | `4096` | Pre-size `OrderBook[]`. |
| `blp.book.pool-size` | `65536` | Pooled resting-order entries. |
| `blp.snapshot.interval` | `nightly` | Snapshot cadence. |
| `blp.snapshot.path` | `./data/snapshots` | Snapshot location. |
| `blp.cache.account.warm-on-start` | `true` | Warm account cache from read-model at startup. |
| `output.nats.enabled` | `true` | Toggle the NATS bridge. |
| `output.projector.enabled` | `true` | Toggle DB projection. |
| `output.projector.batch-size` | `500` | Max rows per flush. |
| `output.projector.flush-interval-ms` | `200` | Time-based flush bound. |
| `output.projector.checkpoint-path` | `./data/projection.ckpt` | Last projected `seq`. |
| `affinity.enabled` | `false` | Core pinning (perf only). |
| `affinity.blp-core` / `affinity.journaler-core` | (unset) | Pinned core IDs. |
| `nogc.*` | see `requirements/no-gc-conformance.md` | No-GC profile keys. |

Removed keys (from `009`): `order.matcher.tick-ms`, `order.matcher.price-service-url`,
`order.matcher.trade-service-url`.

## Build & Dependency Contract

Additions to the hot-path Gradle modules (Java 21 / Spring Boot as in `009`; versions pinned to latest
CVE-clean releases, repo dependency CVE gate applies):

| Concern | Coordinate (illustrative) |
| --- | --- |
| Ring buffers (input MULTI, output SINGLE) | `com.lmax:disruptor:4.0.0` |
| Off-heap buffers + primitive collections | `org.agrona:agrona:1.22.0` |
| Binary codec + build-time generation | `uk.co.real-logic:sbe-tool:1.30.0` |
| Durable journal (demo) | `net.openhft:chronicle-queue:5.25ea` |
| Replication/consensus + journal (perf) | `io.aeron:aeron-cluster` / `io.aeron:aeron-archive` 1.46.x |
| Core pinning (perf) | `net.openhft:affinity:3.23.3` |
| Latency measurement | `org.hdrhistogram:HdrHistogram:2.2.2`, `org.openjdk.jmh:jmh-core:1.37` |

Spring remains for lifecycle/wiring/actuator only; never on the per-event path.

## Metrics Contract Additions

Prometheus metric names required for this state (full table with types/meanings in
`requirements/nonfunctional-delta.md`):

- Input stage: `traderx_disruptor_input_remaining_capacity`, `traderx_input_published_seq`,
  `traderx_input_gating_seq`, `traderx_input_seq_lag`, `traderx_input_events_total{type=...}`,
  `traderx_input_backpressure_events_total`, `traderx_journal_write_latency_seconds`,
  `traderx_replication_ack_latency_seconds`.
- BLP: `traderx_blp_event_latency_seconds`, `traderx_blp_book_depth{security=...}`,
  `traderx_blp_positions_total`, `traderx_blp_cache_miss_total{cache=...}`,
  `traderx_blp_snapshot_seconds`, `traderx_blp_replay_seconds`.
- Output stage: `traderx_output_publish_latency_seconds`, `traderx_output_remaining_capacity`,
  `traderx_output_events_total{kind=...}`, `traderx_output_nats_errors_total`,
  `traderx_projector_lag_seq`, `traderx_projector_batch_size`.
- No-GC: `traderx_hotpath_alloc_bytes_total{node=...}`, `traderx_jvm_gc_pause_seconds`,
  `traderx_jit_warmup_seconds`, `traderx_nightly_bounce_seconds`.

All `009` order metric families are retained (FR-09B41); `traderx_order_match_latency_seconds` becomes
a real measurement.

## Compatibility Notes

- Existing trade/position/pricing APIs remain backward-compatible from `008`/`009`.
- The UI requires zero changes; ADR-013 push semantics are preserved by the NATS bridge.
- Eventual consistency between push streams (immediate) and the relational read-model (projector lag)
  is a documented property of this state, not a regression.
