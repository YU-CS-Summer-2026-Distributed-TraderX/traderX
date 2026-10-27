# Downstream read side

Status: implemented and measured on a cloud VM, 2026-10-25 to 2026-10-27. Every new path is opt-in;
defaults keep the pre-component behaviour. Decision: [ADR-080](../../system/adr-080-downstream-read-side.md).

Source ownership (all under the state's `generation/runtime-overrides/`, rendered last-wins over YU17):

- `trade-processor/`: `JetStreamFeed`, `OrderView`, `TradeView`, `TradeFeedHandler`, `OrderFeedHandler`,
  `TradeSeqGaps`, `model/TradeOrder`, `service/TradeSnapshotStore`, `service/TradeService`,
  `service/OrderProjectionService`, `service/ProjectionWriteLock`, `service/RunRegistry`,
  `controller/ProjectionFeedController`, `controller/OrderController`, the YU18 copies of
  `messaging/nats/NatsJSONPublisher` and `NatsJSONSubscriber`, and `application.properties`.
- `order-matcher/`: `cluster/BridgePartitions`, `cluster/TradeNatsPublisher`, `cluster/OrderNatsPublisher`.
- `postgres-database-replacement/mariadb-migrations/ri06.sql` and
  `kubernetes-runtime/manifests/base/database-init-configmap.yaml`: the `projection_cursor` table.

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md). Measurements: `docs/engineering/downstream-at-400k.md`.
