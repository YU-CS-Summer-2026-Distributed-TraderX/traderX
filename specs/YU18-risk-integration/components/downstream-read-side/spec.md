# Downstream read side specification

Status: implemented, 2026-10-27. Dependencies: JetStream on the existing NATS server, the YU18 trade
and order bridges, MariaDB.

## Requirements

- FR-DRS-01: The trade and order feed queues SHALL be bounded (default 20,000 each,
  `-Dtraderx.trade-feed.queue-capacity`, `-Dtraderx.order-feed.queue-capacity`). A full queue SHALL drop
  the event, count it and log a WARN. It SHALL NOT grow the heap.
- FR-DRS-02: The projection SHALL count trade sides that never reached it, from the contiguity of the
  engine's per-leg `tradeSeq` (`TradeSeqGaps`). `GET /v2/projection-feed` SHALL report backlog, dropped
  and missing counts.
- FR-DRS-03: Trade and order writes SHALL be batched at 1000: one run-row read per scope, one counter
  UPDATE, one position prefetch and one multi-row position upsert per batch. Order updates SHALL use one
  transaction per batch, falling back to one per update on refusal.
- FR-DRS-04: `TradeOrder` SHALL ignore unknown JSON properties, so an additive bridge field never drops a row.
- FR-DRS-05: `PROJECTION_FEED_MODE=jetstream` SHALL consume `/trades` and `/orders` from the file-backed
  JetStream stream `PROJECTION_FEED_STREAM` (default `TRADERX_FEED`, subjects `/trades`, `/trades.*`,
  `/orders`, `/orders.*`), created idempotently with `PROJECTION_FEED_STREAM_MAX_BYTES` (8 GiB) and
  `PROJECTION_FEED_STREAM_MAX_AGE_HOURS` (24). Each owned partition SHALL have one durable consumer and
  one thread. A batch SHALL be acked only after its SQL commit.
- FR-DRS-06: `BRIDGE_PARTITIONS=P` (P>1) SHALL make the leader-side trade and order bridges publish each
  event on `<subject>.<accountId mod P>`. The JSON envelope, including its `topic`, SHALL be unchanged.
  Unset or 1 SHALL keep the bare subject. `PROJECTION_FEED_PARTITIONS` and `PROJECTION_FEED_OWNED`
  (`all` or a comma list) select what a replica consumes. Each partition SHALL hold its own
  `PROJECTION_WRITE_LOCK` row.
- FR-DRS-07: `ORDER_FEED_PERSIST=false` SHALL stop the per-event `orderbook` write. `OrderView` SHALL hold
  every open order and the last `ORDER_VIEW_TERMINAL_CAPACITY` (50,000) terminal ones, and SHALL answer
  `GET /accounts/{id}/orders` in the existing `OrderRow` shape. On restart in jetstream mode it SHALL
  rebuild from the last `ORDER_VIEW_REPLAY_SECONDS` (300) of the stream.
- FR-DRS-08: `TRADE_PROJECTION_MODE=snapshot` (requires jetstream mode) SHALL book every trade side in
  memory through `TradeService.book`. Every `TRADE_PROJECTION_SNAPSHOT_MS` (5000) `TradeSnapshotStore`
  SHALL write, in one transaction, the changed positions, each changed account's last
  `TRADE_PROJECTION_BLOTTER_DEPTH` (100) fills, and the per-partition stream cursor in
  `projection_cursor(projection_scope, partition_id, stream_seq, consensus_seq, snapshot_at)`.
  A restart SHALL load the snapshot and replay from `stream_seq + 1`, ignoring any sequence at or below it.
- FR-DRS-09: `PROJECTION_FANOUT=false` SHALL stop the per-trade `/accounts/{id}/trades|positions` publishes.
- FR-DRS-10: `NatsJSONPublisher` SHALL NOT flush after each publish. Bridge drops on a full queue and
  failed publishes SHALL be counted as dropped.
- NFR-DRS-01: The defaults (`core`, persist `true`, mode `rows`, fan-out `true`, one partition) SHALL keep
  the pre-component wire bytes and SQL behaviour.
- NFR-DRS-02: No engine, ack, snapshot or wire format changes.

## Success Criteria

- SC-DRS-01: In jetstream mode a `kill -9` mid-drain books every published trade side, with 0 gaps.
- SC-DRS-02: In snapshot mode a `kill -9` before the first snapshot replays every published trade side,
  and every position equals an independent recomputation.
- SC-DRS-03: `TradeSeqGapsTest`, `JetStreamFeedTest`, `OrderViewTest`, `TradeViewTest` and
  `BridgePartitionsTest` pass in an empty-root render.
