# Messaging Subject Map: YU11-aeron-replication

Every YU10 subject and the FIX endpoint are carried forward. YU11 adds one NATS KV witness bucket for the opt-in fast-failover tiebreaker and direct Aeron replication channels listed at the end.

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
    `tick-store` capture
  - delivery: `broadcast`
  - wildcard: `yes` (`pricing.*`)
  - scope: `per-ticker`
  - payload: market tick (`price`, `openPrice`, `closePrice`, `asOf`, `source`)

- `/accounts/<accountId>/orders`
  - producer: `order-matcher`
  - consumer: frontend account order blotter stream, `execution-algo-engine` fill tracking (new —
    subscribes to NATS's catch-all `>` and filters client-side by subject prefix/suffix, since the
    literal `/`-separated subject has no `.`-token position for a real NATS wildcard; research.md
    Decision 5)
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

- `algo.events.>` (JetStream, stream `TRADERX_ALGO_ENGINE`, new)
  - producer: `execution-algo-engine`
  - consumer: `execution-algo-engine` (durable consumer `algo-engine-state`, itself — no other
    subscriber in this state)
  - delivery: `durable point-to-point` (JetStream file storage; explicit ack after applying to
    in-memory state, so a crash between append and ack redelivers)
  - wildcard: `yes` (subject per event carries `algo.events.<parentOrderId>`)
  - scope: `global`
  - payload: parent-order lifecycle event (`type`, `parentOrderId`, plus type-specific fields — see
    `data-model.md`)

`execution-algo-engine` introduces one new JetStream stream (its own event log, published and
consumed only by itself) and one new subscriber on the two rows marked `(new)` above; it publishes
nothing to core NATS.

## YU10 addition: FIX session endpoint (non-NATS)

YU10 adds no NATS subject. Its new wire surface is a FIX 4.4 TCP endpoint, listed here so this
map remains the complete inventory of the state's messaging surfaces:

| Endpoint | Transport | Producer -> Consumer | Payload | Scope |
|---|---|---|---|---|
| `order-matcher:18130` | FIX 4.4 / TCP (point-to-point session) | FIX initiator <-> order-matcher acceptor | FIX 4.4 messages: A/0/1/2/3/4/5 session-level; D/F/H in; 8/9 out (see `contracts/contract-delta.md`) | cluster-internal |


## YU11 addition: fast-witness KV

- `$KV.TRADERX_BLP_FAST_WITNESS.>` (JetStream KV bucket `TRADERX_BLP_FAST_WITNESS`)
  - producer: active failover contender in `order-matcher`
  - consumer: both order-matcher replicas
  - delivery: `durable atomic key-value compare-and-set`
  - wildcard: `yes` (KV implementation subject family)
  - scope: `cluster`
  - payload: witness record (`clusterId`, `holderIdentity`, `leaderEpoch`, previous revision,
    claim/expiry times, schema checksum)

The bucket is read/written only when `BLP_FAILOVER_MODE=fast-witness`; the default Lease path
creates no witness traffic.

## YU11 addition: Aeron replication channels (non-NATS)

| Endpoint | Transport | Producer -> Consumer | Payload | Scope |
|---|---|---|---|---|
| peer data UDP | Aeron reliable unicast | primary application -> follower application through sidecars | SBE `InputEventMessage`, 64 bytes | pod pair |
| peer ACK UDP | Aeron reliable unicast | follower ACK agent -> primary replication handler | SBE `DurableAckMessage` | pod pair |
| peer control UDP | Aeron reliable unicast | bidirectional applications | signed hello/challenge, heartbeat, catch-up and snapshot control | pod pair |
| Archive replay UDP | Aeron Archive | recording sidecar -> follower application | recorded SBE input/snapshot streams | pod pair |
