# Contract Delta: 009b-lmax-sequencer-architecture

Parent state: `009-order-management-matcher`

Document any API/event/schema changes for this state. Headline: **the external surface is frozen** —
this state's defining contract is that `009`'s OpenAPI, NATS, UI, and database-schema contracts are
preserved verbatim while the internal execution path is replaced. All additions below are internal
seams or metrics.

## OpenAPI Changes

- None to the `009` surface. Order-management endpoints (`POST /orders`, `GET /orders`,
  `GET /orders/{orderId}`, `POST /orders/{orderId}/cancel`, `POST /orders/{orderId}/force-fill`),
  matcher health/metrics endpoints, and all trade/position/pricing endpoints keep the `009` paths,
  request/response shapes, and status semantics (FR-09B40).
- Additive (2026-07-03, failover surface, FR-09B30..B32): `GET /admin/role` (role/live/lock/journal/
  follower-lag JSON), `GET /admin/ready` (200 on the live node, 503 on a follower — the VIP health
  check), `POST /admin/promote` (manual promotion; 409 when the leader lock is still held). On a
  FOLLOWER node, the `009` mutating endpoints answer `503 Service Unavailable` until promotion; the
  stable client address is the VIP, which only routes commands to the live node, so `009` clients
  never observe the 503s in normal operation.

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
- **Journal format contract**: append-only fixed **64-byte little-endian** record `(seq, type, side,
  orderRef, accountId, securityId, qty, limitPx, priceTicks, eventTimeMillis)` to `input-events.journal`;
  `ingressNanos` is not journaled (latency field, not state); a torn trailing record (< 64 bytes) is
  discarded on replay. (SBE-as-wire-bytes + schema-versioning is the deferred perf-profile form; today the
  record is typed fields, not SBE.)
- **Snapshot contract**: `snapshot.dat` — full BLP state (orders, net positions, last prices,
  `nextOrderRef`, `tradeCounter`) + `coveredOffset` (journal byte offset the snapshot covers); written
  atomically (temp + atomic rename) on the BLP thread at a sequenced `SNAPSHOT` marker; recovery loads it
  and replays only the journal tail after `coveredOffset`.
- **Replication contract**: followers receive the identical sequenced input stream; follower output is
  suppressed until promotion; promotion occurs at the follower's current sequence (Aeron Cluster in the
  perf profile; loopback/stub in demo).
- **Request/response event contract** for BLP cache misses (e.g. `AccountLookupRequest` ->
  `AccountLookupResponse` re-entering as a sequenced input event); a timeout/failure is itself an event.
- **Projection checkpoint contract**: last projected `seq` (`projectedSeq`), advanced only on a committed
  flush so projection is idempotent; full rebuild from the journal must reproduce identical rows
  (SC-09B11). In 009b this watermark is in-memory; persisting it to `output.projector.checkpoint-path` is
  deferred (the snapshot's `coveredOffset` is the durable recovery boundary).
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
| `snapshot.interval.ms` (env `SNAPSHOT_INTERVAL_MS`) | `0` (compose demo `30000`) | Periodic full-state snapshot cadence in ms; `0` = off. `snapshot.dat` lives under `journal.path`. |
| `recovery.source` (env `RECOVERY_SOURCE`) | `db` | `db` (warm-start + journal-replay verify) or `journal` (snapshot+journal authoritative, no DB). |
| `output.projector.db.enabled` (env `OUTPUT_PROJECTOR_DB_ENABLED`) | `true` | `false` drops all DB writes (no-DB cutover). |
| `journal.replay.verify` | `true` | In `db` mode, verify journal replay reconstructs the warm-start state. |
| `blp.role` (env `BLP_ROLE`) | `primary` | Failover role PREFERENCE (2026-07-03); the `leader.lock` file lock on the journal volume decides — a primary that finds it held demotes to follower. `standby` boots the journal-tail follower. |
| `failover.watch-url` (env `FAILOVER_WATCH_URL`) | (empty) | Peer health URL the follower's watchdog probes; empty disables auto-promotion (manual `POST /admin/promote` still works). |
| `failover.probe-interval-ms` / `failover.probe-failures` | `1000` / `3` | Consecutive connect-failures before the watchdog attempts promotion (any HTTP status counts as alive). |
| `failover.follower-poll-ms` | `10` | Journal tail poll cadence on the follower. |
| `blp.leader.acquire-timeout-ms` | `10000` | How long a configured primary waits for `leader.lock` at boot before demoting itself. |
| `blp.cache.account.warm-on-start` | `true` | Warm account cache from read-model at startup *(not yet wired)*. |
| `output.nats.enabled` | `true` | Toggle the NATS bridge. |
| `output.projector.enabled` | `true` | Toggle DB projection. |
| `output.projector.batch-size` | `500` | Max rows per flush. |
| `output.projector.flush-interval-ms` | `200` | Time-based flush bound. |
| `output.projector.checkpoint-path` | `./data/projection.ckpt` | Last projected `seq` *(not yet wired; `projectedSeq` watermark is in-memory)*. |
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
  `traderx_blp_snapshot_seconds`, `traderx_blp_replay_seconds` *(snapshot/replay durations are
  currently logged via `JOURNAL-REPLAY VERIFY` / `LIVE RECOVERY`, not yet metered)*.
- Output stage: `traderx_output_publish_latency_seconds`, `traderx_output_remaining_capacity`,
  `traderx_output_events_total{kind=...}`, `traderx_output_nats_errors_total`,
  `traderx_projector_lag_seq`, `traderx_projector_batch_size`.
- No-GC: `traderx_hotpath_alloc_bytes_total{node=...}`, `traderx_jvm_gc_pause_seconds`,
  `traderx_jit_warmup_seconds`, `traderx_nightly_bounce_seconds`.
- Failover (wired 2026-07-03): `traderx_blp_role{role=...}`, `traderx_blp_live`,
  `traderx_leader_lock_held`, `traderx_follower_lag_bytes`,
  `traderx_follower_applied_events_total`, `traderx_failover_promotions_total` — exposed by both
  matcher nodes; Prometheus scrapes `order-matcher:18110` and `order-matcher-standby:18110`.

All `009` order metric families are retained (FR-09B41); `traderx_order_match_latency_seconds` becomes
a real measurement.

## Compatibility Notes

- Existing trade/position/pricing APIs remain backward-compatible from `008`/`009`.
- The UI requires zero changes; ADR-013 push semantics are preserved by the NATS bridge.
- Eventual consistency between push streams (immediate) and the relational read-model (projector lag)
  is a documented property of this state, not a regression.
