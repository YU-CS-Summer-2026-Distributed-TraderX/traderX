# Downstream read side tasks

- [x] Bounded feed queues with counted drops; `TradeSeqGaps`; `GET /v2/projection-feed`.
- [x] Batched writes; `TradeOrder` ignores unknown properties; no flush per publish.
- [x] `PROJECTION_FEED_MODE=jetstream` with durable per-partition consumers, ack after commit.
- [x] `BRIDGE_PARTITIONS` on the leader bridges; per-partition `PROJECTION_WRITE_LOCK`.
- [x] `OrderView` behind `ORDER_FEED_PERSIST=false`, rebuilt from the stream on restart.
- [x] `TradeView` and `TradeSnapshotStore` behind `TRADE_PROJECTION_MODE=snapshot`; `projection_cursor`
      in `ri06.sql` and both init-configmap keys.
- [x] Crash-replay and rate-ladder measurements on a cloud VM.

## Still open

- The YU16 bond fail-closed rejection is not on the snapshot path.
- No deployed tier sets the opt-in env yet.
