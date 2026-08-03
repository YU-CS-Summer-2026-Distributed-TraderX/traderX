# Messaging Subject Map: YU13-limit-order-book

Every inherited NATS subject is carried forward with its existing contract. YU13 changes the
matching policy inside the cluster service, and adds ONE subject — `/orders` — the order-state
sibling of `/trades`: the leader-side order-lifecycle bridge that finally gives order state a home
outside the cluster (a read-model row, an enumerable blotter, and a SQL effect-end for order-level
proofs). No inherited subject is removed or re-shaped. The one other observable difference is
volume — a genuine crossing books a trade on BOTH sides of every match, so the leader's trade-egress
bridge publishes two `/trades` messages per cross (each keyed by its own `tradeSeq`+side) where the
price-triggered policy published one; `trade-processor` dedup and every downstream contract are
unchanged.

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

- `/orders`
  - producer: **the Aeron Cluster leader's order-lifecycle bridge (`OrderNatsPublisher`)** — every
    order-state transition the crossing book already emits (`KIND_ORDER_ACCEPTED`/`REJECTED`/
    `PARTIALLY_FILLED`/`FILLED`/`CANCELED` on the deterministic apply stream, both the input's own
    order and counterparty resting orders hit by an aggressor) is published here so order state
    reaches `trade-processor` → the `orderbook` SQL projection → the `GET /accounts/{id}/orders`
    enumeration. Same discipline as `/trades`: leader-only (no follower dupes), best-effort
    off-consensus tap (never the gateway egress), non-blocking on the apply thread. Enabled by
    `TRADE_BRIDGE_NATS_URL` (shared with `/trades`).
  - consumer: `trade-processor` (`OrderFeedHandler`, upserts `orderbook` by primary key)
  - delivery: `point-to-point`
  - wildcard: `no`
  - scope: `global`
  - payload: `NatsEnvelope<OrderUpdate>` (`type` must equal `OrderUpdate`); `payload` = `{id,
    accountId, security, side, quantity, remainingQuantity, limitPrice, status, lastExecutionPrice,
    lastFillQuantity, createdAt, updatedAt}`. **`id` is epoch-qualified `<epoch>-<orderRef>`** — the
    epoch (`CLUSTER_EPOCH`, default `1`, identical on every member) keeps the read-model key from
    colliding across cluster incarnations the way the bare-`tradeSeq` trade id still can. At-least-
    once; the read model upserts by `id`, so a replay is idempotent. A queue-full drop (flood only)
    and a DB-refused write are both COUNTED and logged — this path is not allowed to drop silently.

- `/accounts/<accountId>/orders` (REST, not NATS)
  - producer -> consumer: any client (blotter UI, a restarted client recovering its open refs) ->
    `trade-processor` `OrderController`
  - payload: JSON array of `orderbook` rows; open orders (`NEW`+`PARTIALLY_FILLED`) by default,
    `?status=all` for every terminal state too (so a cancel/replace proof can assert the row went
    `CANCELED` rather than merely vanished). This is the enumeration that unblocks client-restart
    recovery and the 107k-style drain.

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
