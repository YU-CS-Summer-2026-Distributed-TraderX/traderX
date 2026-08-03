# Functional Delta: YU08-execution-algo-engine (vs YU07-historical-tick-store)

Everything from `YU07-historical-tick-store` is carried forward untouched — the matching engine and
its risk gateway, the order-entry path, the message bus, and the Parquet tick store all behave
exactly as before. This state adds one new service alongside them, `execution-algo-engine`, which
takes a single large "parent" order and works it into the market as a timed series of smaller child
orders, each submitted through the same public order-entry endpoint a human trader would use.

## Added

- An `execution-algo-engine` service that accepts a parent order — account, security, side,
  quantity, algo type, duration and bucket size — at `POST /algo/orders`.
- TWAP scheduling, which splits the parent quantity into equally sized time buckets and puts any
  leftover from the integer division on the last bucket, so the buckets always total the parent.
- VWAP scheduling, which sizes each bucket by weights supplied by a pluggable volume-profile source
  instead of splitting the quantity evenly.
- Two volume-profile sources: a synthetic U-shaped intraday curve that needs no market data, and a
  DuckDB source deriving real weights from historical trades in the inherited tick store.
- Automatic fallback to the synthetic weights when the DuckDB source finds no matching history for a
  security, so a VWAP parent order is never blocked or rejected by missing data.
- Child-order submission through the matching engine's existing `POST /orders` endpoint with a
  positive `limitPrice` derived from the security's current price, with no order-entry code changed.
- Fill tracking that subscribes to the existing `/accounts/*/orders` broadcast and correlates
  incoming updates back to a bucket by `orderId`, so nothing new has to be published for it.
- Progress queries over `GET /algo/orders/{parentOrderId}` and `GET /algo/orders`, showing buckets
  submitted, quantity filled and parent status while the order is still working.
- An event log on a durable JetStream stream (`TRADERX_ALGO_ENGINE`) written before any state change
  is applied in memory, and replayed in full on boot so a crash resumes a parent order mid-schedule.
