# Messaging Subject Map: YU15-eod-risk-extract

Every inherited NATS subject is carried forward with its existing contract. YU14 changed the
instrument model and risk-gate notional math inside the cluster service, not the messaging
surface: no subject is added, removed, or re-shaped. The one observable difference is content —
option contracts appear on the inherited subjects as ordinary securities whose ticker is the
unpadded OCC symbol (e.g. `AAPL260918C00240000` in `/trades` payload `security` fields and the
per-account trade/position subjects). The YU13 volume note carries forward: a crossing books a
trade on BOTH sides of every match, two `/trades` messages per cross, dedup unchanged.

The Aeron Cluster channels and the gateway FIX endpoint are inherited from YU12 unchanged.

## Subject Families

- `/trades`
  - producer: `trade-service` (legacy REST path); **YU12 adds the Aeron Cluster leader's trade-egress
    bridge (`TradeNatsPublisher`) as the primary producer** — every booked trade (`KIND_TRADE_BOOKED`
    on the deterministic apply stream) is published here so the cluster's fills reach `trade-processor`
    → the SQL DB → the `/accounts/*/trades` + `/positions` UI feeds. Not the gateway egress (best-effort,
    submitting-session-only). Leader-only (no follower dupes); at-least-once, keyed by `tradeSeq`+side so
    `trade-processor` (Trade JPA id) dedups. Enabled by `TRADE_BRIDGE_NATS_URL`. See ADR-048.
  - consumer: `trade-processor`
  - delivery: `point-to-point`
  - wildcard: `no`
  - scope: `global`
  - payload: `NatsEnvelope<TradeOrder>` (`type` must equal `TradeOrder`); `payload` = `{id, state,
    security, quantity, price, accountId, side}` with stamped execution price

- `/accounts/<accountId>/trades`
  - producer: `trade-processor`
  - consumer: frontend trade blotter stream, `tick-store` capture
  - delivery: `broadcast`
  - wildcard: `no` (consumed with wildcard `/accounts/*/trades`)
  - scope: `per-account`
  - payload: processed trade (includes `price`)

- `/accounts/<accountId>/positions`
  - producer: `trade-processor`
  - consumer: frontend position blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `per-account`
  - payload: position snapshot (includes `averageCostBasis`)

- `pricing.<TICKER>`
  - producer: `price-publisher`
  - consumer: frontend valuation streams, `trade-processor`'s EOD closing-price source,
    `tick-store` capture, YU12 feed adapter (new — sequences conflated ticks as cluster ingress)
  - delivery: `broadcast`
  - wildcard: `yes` (`pricing.*`)
  - scope: `per-ticker`
  - payload: market tick (`price`, `openPrice`, `closePrice`, `asOf`, `source`)

- `/accounts/<accountId>/orders`
  - producer: `order-matcher`
  - consumer: frontend account order blotter stream, `execution-algo-engine` fill tracking
    (subscribes to NATS's catch-all `>` and filters client-side by subject prefix/suffix, since
    the literal `/`-separated subject has no `.`-token position for a real NATS wildcard)
  - delivery: `broadcast`
  - wildcard: `no` (no NATS-native wildcard is possible on this subject shape at all)
  - scope: `per-account`
  - payload: `NatsEnvelope` wrapping an order lifecycle event (`orderId`, `status`,
    `remainingQuantity`, `limitPrice`, `lastExecutionPrice`) under `payload`

- `/orders`
  - producer: `order-matcher`
  - consumer: frontend admin order blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `global`
  - payload: order lifecycle event (`orderId`, `accountId`, `status`, `remainingQuantity`, `limitPrice`)

- `eod.prices.ready` (JetStream, stream `TRADERX_EOD`)
  - producer: `trade-processor`
  - consumer: `position-service` (durable consumer `eod-pnl`)
  - delivery: `durable point-to-point` (JetStream file storage; redelivered until acked)
  - wildcard: `no`
  - scope: `global`
  - payload: gate event (`sessionDate`, `version`, `instrumentCount`, `publishedAtMillis`)

- `eod.pnl.done` (JetStream, stream `TRADERX_EOD`)
  - producer: `position-service`
  - consumer: none within this state
  - delivery: `durable broadcast` (JetStream file storage)
  - wildcard: `no`
  - scope: `global`
  - payload: completion event (`sessionDate`, `version`, `accountsMarked`, `accountsHalted`, `completedAtMillis`)

- `algo.events.>` (JetStream, stream `TRADERX_ALGO_ENGINE`)
  - producer: `execution-algo-engine`
  - consumer: `execution-algo-engine` (durable consumer `algo-engine-state`, itself — no other
    subscriber in this state)
  - delivery: `durable point-to-point` (JetStream file storage; explicit ack after applying to
    in-memory state, so a crash between append and ack redelivers)
  - wildcard: `yes` (subject per event carries `algo.events.<parentOrderId>`)
  - scope: `global`
  - payload: parent-order lifecycle event (`type`, `parentOrderId`, plus type-specific fields)

## FIX session endpoint (non-NATS)

| Endpoint | Transport | Producer -> Consumer | Payload | Scope |
|---|---|---|---|---|
| gateway FIX port | FIX 4.4 / TCP (point-to-point session) | FIX initiator <-> `fix-gateway` acceptor | FIX 4.4 messages: A/0/1/2/3/4/5 session-level; D/F/H in; 8/9 out | cluster-internal |

The acceptor terminates on the gateway tier; the session survives BLP leader changes.

## YU12 addition: Aeron Cluster channels (non-NATS)

| Endpoint | Transport | Producer -> Consumer | Payload | Scope |
|---|---|---|---|---|
| cluster ingress UDP | Aeron Cluster client protocol | gateway tier / feed adapter -> leader consensus module | SBE `InputEventMessage` ingress commands | cluster |
| cluster egress UDP | Aeron Cluster client protocol | leader -> gateway tier / feed adapter | committed admission responses and session events | cluster |
| consensus/log UDP | Aeron Cluster consensus protocol | leader <-> followers | log replication, votes, heartbeats, commit positions | member pods |
| catch-up/snapshot UDP | Aeron Archive + cluster catch-up | serving member -> rejoining member | snapshot retrieval and committed-log replay | member pods |

The parent state's removed surfaces — `$KV.TRADERX_BLP_FAST_WITNESS.>` and the peer
data/ACK/control replication channels — have no successor here; their roles are internal to the
consensus protocol.

## YU15 change: `pricing.<TICKER>` carries listed option contracts

The subject, payload shape, producer and consumers are unchanged; the universe is wider. An option
contract is quoted under its unpadded OCC symbol (`pricing.AAPL260918C00240000`) exactly as an
equity is under its ticker, with `source` reading `black-scholes` for a derived contract. The
binary companion subject `pricing-tick-bin.<TICKER>` carries the same contracts.

Without this, options are `MISSING` in every EOD snapshot and YU06's fail-safe halts any account
holding one, so no account with an option position is ever marked.

## YU15 addition: EOD risk-extract subjects

- `eod.pnl.done` (JetStream, stream `TRADERX_EOD`) — **now consumed**
  - producer: `position-service` (inherited from YU06, unchanged)
  - consumer: `risk-extract` (durable consumer `risk-extract`)
  - delivery: `durable point-to-point` (JetStream file storage; redelivered until acked)
  - wildcard: `no`
  - scope: `global`
  - payload: unchanged YU06 completion event (`sessionDate`, `version`, `accountsMarked`,
    `accountsHalted`, `completedAtMillis`)
  - note: the producer ensures the stream idempotently, exactly as position-service does at its
    end, so neither side has to start first.

- `risk.extract.cut`
  - producer: `order-matcher-cluster` **leader only**
  - consumer: `risk-extract`
  - delivery: `broadcast` (core NATS; one message per extract)
  - wildcard: `no`
  - scope: `global`
  - payload: the canonical position cut as US-ASCII text — a `#cut` header carrying
    `schema`, `seq`, `sessionDateEpochDay`, `priceVersion`, `rows`, then one row per
    `(accountId, security)` with quantity, average cost ticks, contract multiplier, and last
    trade price ticks. The `rows` count makes a truncated delivery detectable.

- `risk.extract.ready`
  - producer: `risk-extract`
  - consumer: the external pricing/risk engine
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `global`
  - payload: delivery record — `schema`, `uri`, `consensusSequence`, `sessionDate`,
    `priceSnapshotVersion`, `rows`, `sha256`, `cutSha256`, `quiesceWitnessSequence`

The `risk.` prefix is deliberately a family rather than a single subject: an inbound results path
(`risk.analytics.*`, computed metrics returning for a UI or dashboard surface) slots alongside
`risk.extract.*` without renaming anything that exists here.

## YU15 addition: cluster ingress template

| Endpoint | Transport | Producer -> Consumer | Payload | Scope |
|---|---|---|---|---|
| cluster ingress UDP | Aeron Cluster client protocol | `risk-extract` -> leader consensus module | SBE `RiskExtractMessage` (template 8): request id, session date, closing-price version | cluster |
| cluster egress UDP | Aeron Cluster client protocol | leader -> `risk-extract` | marker ack carrying the consensus sequence it landed at and the cut's row count | cluster |
