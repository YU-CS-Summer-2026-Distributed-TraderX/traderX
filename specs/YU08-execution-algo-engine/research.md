# Research: YU08 — Execution Algo Engine

## Decision 1 — a new `execution-algo-engine` component, warm-path, not a BLP feature

`order-matcher` IS the BLP: a single-threaded, no-GC, in-memory matching core (`LMAX-BLP.md`)
journaled and snapshotted for crash recovery. Slicing a parent order over a multi-second-to-minute
time window is stateful scheduling work with no place on that thread — it would mean either
blocking the matching loop on a timer (unacceptable: the BLP's whole design point is that nothing
but sequenced input events touches it) or bolting an unrelated second responsibility onto the one
component this project treats as sacrosanct. A plain Spring Boot service — the same shape as
`account-service`/`trade-processor`, not order-matcher's Disruptor/journal machinery — is the right
altitude: it owns parent-order scheduling and submits child orders exactly like any other REST
client of the existing order-entry surface.

## Decision 2 — children submit through `order-matcher`'s existing `POST /orders`, unchanged

`POST /orders` (`OrderCreateRequest`: `clientOrderId`, `accountId`, `security`, `side`, `quantity`,
`limitPrice`) is the same endpoint the web front end's order ticket already calls
(`order-ticket.component.ts`). It already carries the full YU03 two-tier admission path — in-process
Gateway replica screening, then the BLP's authoritative sequenced accept/reject — with no gateway
bypass and no new endpoint. `execution-algo-engine` is simply one more caller of this endpoint;
order-matcher gains no new code, no new field, no special case for algo-sourced orders. Batch
ingress (`POST /orders/batch`) is not used — each child is submitted individually as its bucket
comes due, so the risk gateway's per-order idempotency/reservation bookkeeping applies exactly as it
does to a manually entered order.

## Decision 3 — child limit price: last price with a small aggressive offset

`POST /orders` requires a positive `limitPrice` (`OrderMatcherService.validateOrderCreateRequest`
rejects a null/non-positive one) — there is no "market order" verb on this path (that exists only
for `MarketTradeRequest`/`POST /trades`, a different flow this state does not use). Before
submitting each child, `execution-algo-engine` fetches the current price from
`GET /prices/{ticker}` on `price-publisher` (the same tick a screen would show) and offsets it by
10 bps against the order's side — `Buy` at `price × 1.001`, `Sell` at `price × 0.999` — so the
order is aggressively marketable against the resting book rather than a passive limit that might
never cross. 10bps sits far inside the default 50% price collar
(`risk.price.collar-bps=5000`) and the price is fetched immediately before submission, so it is
always within the default 30s freshness window (`risk.price.max-age-ms`) — no risk-gateway
rejection path is exercised by this offset under normal operation.

## Decision 4 — own state is event-sourced over a dedicated JetStream stream, reusing the existing client

Crash recovery must resume a parent order's schedule and observed fills without re-deriving them
from scratch. YU04 already established the pattern this project uses for durable, replayable state:
a JetStream stream plus the `io.nats:jnats:2.20.5` client every JVM service in this lineage already
depends on (`JetStreamControlFeedPublisher`, `ControlFeedSubscriber`). `execution-algo-engine` reuses
the identical client and stream-bootstrap idiom for a new stream, `TRADERX_ALGO_ENGINE` (subject
`algo.events.>`, file storage): every state transition (`ParentOrderCreated`, `ChildOrderSubmitted`,
`ChildOrderFillObserved`, `ParentOrderCompleted`) is appended as one JSON message before it is
reflected in the in-memory model. On every boot, a fresh **ephemeral** pull consumer
(`DeliverPolicy.All`, `AckPolicy.None`) replays the entire stream from the start and rebuilds every
parent order, then the same subscription keeps delivering new events live — the same consumer
serves both roles, so there is no separate "replay mode" versus "live mode" to keep in sync. No ack
bookkeeping is needed because applying an event is a deterministic function of current state
(each event fully replaces the affected bucket/order's fields rather than incrementing a counter),
so a full replay from the start is correct regardless of when a crash happened. (A durable named
consumer with explicit acks was tried first and rejected once live kind verification surfaced the
gap: acking permanently advances that consumer's position, so a restart would only replay whatever
was left unacked — any parent order that had already fully completed before the restart would be
silently missing from the rebuilt state, contradicting FR-AE08's "every parent order." See ADR-030.)

This is deliberately not a database table: this state has no existing datastore of its own to add
one to, and the append-only, replay-to-rebuild shape is exactly what JetStream already gives for
free — a new table plus ORM would duplicate infrastructure the project already runs for the same
purpose.

## Decision 5 — fill tracking: catch-all NATS subscribe + client-side filter, no new subject

`/accounts/<accountId>/orders` already broadcasts every order lifecycle event
(`orderId`, `status`, `remainingQuantity`, `limitPrice`, `lastExecutionPrice`) to multiple
subscribers (the frontend blotter stream) — no new subject, no publisher change.
`execution-algo-engine` cannot use a NATS token wildcard to select every account's subject the way
`pricing.*` does for tick-store (YU07 Decision 2), because these subjects use a literal `/` rather
than NATS's `.`-delimited hierarchy — `order-matcher` publishes the literal string
`"/accounts/" + accountId + "/orders"` (`NatsBridgeHandler`), so the entire string is one NATS
token and `*` cannot be embedded inside it (jnats's subject validator rejects
`"/accounts/*/orders"` outright: "Subject wildcard improperly placed" — discovered live during this
state's kind verification, not from documentation). Instead, `execution-algo-engine` subscribes to
NATS's full match-all subject (`">"`) and filters client-side by subject prefix/suffix
(`/accounts/` ... `/orders`) — the only correct way to select every account's order subject given
the existing publisher's non-hierarchical naming, at the cost of also receiving every other subject
on the connection and discarding non-matches.

Every message on this bus is wrapped in a `NatsEnvelope` (`topic`/`payload`/`date`/`from`/`type` —
`messaging/nats/NatsJSONPublisher`, the concrete `Publisher<T>` every JVM publisher on this branch
uses, including order-matcher's `NatsBridgeHandler`); the actual `OrderResponse` fields live under
`payload`, not the envelope's top level — also found live, not from documentation, since nothing in
this project's spec packs had previously needed to describe the wire format from a *new*
subscriber's point of view. `execution-algo-engine` unwraps `payload` before reading `orderId` etc.

Every submitted child order's returned `orderId` (from the synchronous `POST /orders`
response) is recorded in an in-memory `orderId -> (parentOrderId, bucketIndex)` index; an incoming
lifecycle event whose `orderId` matches updates that bucket's fill state and appends a
`ChildOrderFillObserved` event.

`clientOrderId` is still set on every child request (`<parentOrderId>:<bucketIndex>`) for
idempotency and audit-trail readability in order-matcher's own logs and risk-gateway idempotency
table, but `OrderResponse` (the payload rendered onto `/orders`/`/accounts/*/orders`) does not carry
`clientOrderId` today — it is built from the output-ring's zero-allocation rendering path
(`OrderResponse.from(...)`, Tier 2-C, `order-matcher/api/OrderResponse.java`), a hot-path structure
this state does not touch. `orderId`-based correlation via the algo-engine's own submit-time index
is sufficient for this state's own progress tracking without adding a field to that path.

## Decision 6 — TWAP: equal-quantity time buckets

A parent order (`accountId`, `security`, `side`, `quantity`, `durationSeconds`, `bucketSeconds`)
slices into `ceil(durationSeconds / bucketSeconds)` buckets of `floor(quantity / bucketCount)` each,
with the remainder from integer division added to the last bucket so every share is scheduled
exactly once. `bucketSeconds` defaults to 10 (within the 5-30s range that keeps a manual demo run
watchable) and is caller-overridable.

## Decision 7 — VWAP: pluggable volume-profile source, synthetic by default

`VolumeProfileSource.bucketWeights(security, bucketCount)` returns a list of non-negative weights
summing to 1, one per bucket, used instead of TWAP's equal split. Two implementations:

- `SyntheticVolumeProfileSource` (default, `algo.volume-profile.source=synthetic`): a deterministic
  U-shaped intraday curve — weight at bucket `i` of `n` is `1 + 4 × ((i / (n-1) − 0.5))²` before
  normalization, the textbook shape of higher participation near the open/close and a lull mid-session.
  Needs no external data, so VWAP has no data dependency at all when this source is selected.
- `DuckDbVolumeProfileSource` (`algo.volume-profile.source=duckdb`): runs one DuckDB query (via
  `org.duckdb:duckdb_jdbc`, the same DuckDB engine YU07's `tick-store` uses from Python, here from
  Java) over YU07's unified `ticks` Parquet store
  (`read_parquet('<algo.volume-profile.duckdb.path>/**/*.parquet', hive_partitioning=true)`),
  grouping `event_type='trade'` rows for the requested `security` by intraday time bucket to produce
  a historical volume fraction per bucket. **Bulk OneDrive → GCS TAQ ingestion is still blocked**
  (rclone pending university IT approval; CLI-triggered local hydration is a confirmed dead end —
  YU07's own handoff) and there is not yet enough captured live-trade volume to shape a profile
  either. Rather than block VWAP on that unblocking, this source falls back to
  `SyntheticVolumeProfileSource`'s weights whenever the query returns zero matching rows for the
  requested security, logging that it did so — VWAP is fully usable today and starts reading real
  volume automatically the moment enough TAQ or live-trade data exists at
  `gs://traderx-501015-tick-store`, with no code change required to pick it up.

## Decision 8 — REST-only parent-order ingress, no front-end panel

A parent order is submitted via `POST /algo/orders` on `execution-algo-engine` itself (not
`order-matcher` — this is the algo engine's own control surface, separate from the child-order
ingress in Decision 2). `GET /algo/orders/{parentOrderId}` and `GET /algo/orders` expose progress
(schedule, buckets submitted, quantity filled, status). No web-front-end panel is added in this
state — a REST client (`curl`, `run-state-kind`'s smoke check) is the only caller.

## Generation — one new component, one shared-file append

`execution-algo-engine` is a new directory in the shared component tree, like `tick-store` before
it — no ancestor state has a file at this path to conflict with. The one shared file this state
modifies is `kubernetes-runtime/manifests/base/kustomization.yaml`, already overridden by every
ancestor through YU07 (each appends its own resource entries): this state's copy starts from YU07's
current version and appends `execution-algo-engine-deployment.yaml` +
`execution-algo-engine-service.yaml`, never replacing it. Verified empirically post-generation (see
`generation/implementation-status.md`): every ancestor's resource entry, including YU07's own
`tick-store-deployment.yaml`, survives alongside the two new entries.
