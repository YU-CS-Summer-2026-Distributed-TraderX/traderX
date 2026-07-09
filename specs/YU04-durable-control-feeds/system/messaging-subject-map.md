# Messaging Subject Map (State YU04)

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
  - consumer: frontend trade blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
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
  - consumer: frontend valuation streams
  - delivery: `broadcast`
  - wildcard: `yes` (`pricing.*`)
  - scope: `per-ticker`
  - payload: market tick (`price`, `openPrice`, `closePrice`, `asOf`, `source`)

- `/accounts/<accountId>/orders`
  - producer: `order-matcher`
  - consumer: frontend account order blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `per-account`
  - payload: order lifecycle event (`orderId`, `status`, `remainingQuantity`, `limitPrice`, `lastExecutionPrice`, `riskReason`)

- `/orders`
  - producer: `order-matcher`
  - consumer: frontend admin order blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `global`
  - payload: order lifecycle event (`orderId`, `accountId`, `status`, `remainingQuantity`, `limitPrice`, `riskReason`)

- `traderx.control.account.deltas` (JetStream, stream `TRADERX_CONTROL_ACCOUNT`)
  - producer: `account-service` (transactional-outbox publisher)
  - consumer: `order-matcher` `ControlFeedSubscriber` (account source; ephemeral pull consumer, `DeliverPolicy.New`)
  - delivery: `durable point-to-point` (JetStream file storage; `Nats-Msg-Id="account:<version>"` dedupe)
  - wildcard: `no`
  - scope: `global`
  - payload: account existence/identity control delta (`accountId`, `displayName`); source version carried in `Nats-Msg-Id`

- `traderx.control.security.deltas` (JetStream, stream `TRADERX_CONTROL_SECURITY`)
  - producer: `reference-data` (transactional-outbox publisher)
  - consumer: `order-matcher` `ControlFeedSubscriber` (security source; ephemeral pull consumer, `DeliverPolicy.New`)
  - delivery: `durable point-to-point` (JetStream file storage; `Nats-Msg-Id="security:<version>"` dedupe)
  - wildcard: `no`
  - scope: `global`
  - payload: security existence/identity control delta (`ticker`, `companyName`); source version carried in `Nats-Msg-Id`

## Snapshot endpoints (HTTP, not NATS)

The subscribe-buffer-snapshot bootstrap (ADR-019) pairs each durable stream above with a
watermarked-snapshot HTTP endpoint on its source service, fetched cold-path during bootstrap:

- `GET /account/control-snapshot` (`account-service`) — schema version, source epoch, watermark,
  count, SHA-256 checksum, record array.
- `GET /stocks/control-snapshot` (`reference-data`) — same wrapper shape for securities.
