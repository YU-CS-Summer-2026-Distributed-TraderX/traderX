# Messaging Subject Map (state YU01)

Subjects and payload shapes are inherited verbatim from state `009` (FR-09B21/FR-09B40); only the
producing component changes for order/trade/position families (output-disruptor NATS bridge inside the
hot-path node). `pricing.<TICKER>` gains an additional consumer: the Gateway, which injects sequenced
`PRICE_TICK` input events.

## Subject Families

- `/trades`
  - producer: output-disruptor NATS bridge (`TradeBooked` events; was `trade-service`)
  - consumer: legacy global trade stream consumers
  - delivery: `point-to-point`
  - wildcard: `no`
  - scope: `global`
  - payload: unchanged — validated trade with stamped execution price

- `/accounts/<accountId>/trades`
  - producer: output-disruptor NATS bridge (`TradeBooked`; was `trade-processor`)
  - consumer: frontend trade blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `per-account`
  - payload: unchanged — processed trade (includes `price`)

- `/accounts/<accountId>/positions`
  - producer: output-disruptor NATS bridge (`PositionUpdated`; was `trade-processor`)
  - consumer: frontend position blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `per-account`
  - payload: unchanged — position snapshot (includes `averageCostBasis`)

- `pricing.<TICKER>`
  - producer: `price-publisher` (unchanged)
  - consumer: frontend valuation streams + **Gateway (sequences `PRICE_TICK` input events)**
  - delivery: `broadcast`
  - wildcard: `yes` (`pricing.*`)
  - scope: `per-ticker`
  - payload: unchanged — market tick (`price`, `openPrice`, `closePrice`, `asOf`, `source`)

- `/accounts/<accountId>/orders`
  - producer: output-disruptor NATS bridge (order lifecycle events; was `order-matcher` inline)
  - consumer: frontend account order blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `per-account`
  - payload: unchanged — order lifecycle event (`orderId`, `status`, `remainingQuantity`,
    `limitPrice`, `lastExecutionPrice`)

- `/orders`
  - producer: output-disruptor NATS bridge (order lifecycle events; was `order-matcher` inline)
  - consumer: frontend admin order blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `global`
  - payload: unchanged — order lifecycle event (`orderId`, `accountId`, `status`,
    `remainingQuantity`, `limitPrice`)

## Parity Gate

Subject names and JSON payload shapes are asserted byte-compatible with `009` by the smoke suite
(SC-09B09); `securityId -> ticker` and fixed-point -> decimal rendering happen only in the bridge.
