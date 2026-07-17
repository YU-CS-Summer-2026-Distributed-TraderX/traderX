# Messaging Subject Map: YU12-aeron-cluster

Every inherited NATS subject is carried forward with its existing contract. The parent state's
fast-witness KV bucket and Aeron peer replication/ACK/control channels are removed with the
machinery that used them. YU12 adds the Aeron Cluster channels listed at the end, and the FIX
endpoint moves from the order-matcher process to the gateway tier.

## Subject Families

- `/trades`
  - producer: `trade-service`
  - consumer: `trade-processor`
  - delivery: `point-to-point`
  - wildcard: `no`
  - scope: `global`
  - payload: validated trade order with stamped execution price

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
