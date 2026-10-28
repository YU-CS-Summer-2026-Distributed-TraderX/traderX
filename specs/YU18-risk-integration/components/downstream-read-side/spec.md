# Downstream read side specification

Status: implemented, 2026-10-27 (FR-DRS-11..25 added the same day; FR-DRS-26 2026-10-28). Dependencies: JetStream on the existing NATS server, the YU18 trade
and order bridges, MariaDB.

## Requirements

- FR-DRS-01: The trade and order feed queues SHALL be bounded (default 20,000 each,
  `-Dtraderx.trade-feed.queue-capacity`, `-Dtraderx.order-feed.queue-capacity`). A full queue SHALL drop
  the event, count it and log a WARN. It SHALL NOT grow the heap.
- FR-DRS-02: The projection SHALL count trade sides that never reached it, from the contiguity of the
  engine's per-leg `tradeSeq` (`TradeSeqGaps`). `GET /v2/projection-feed` SHALL report backlog, dropped
  and missing counts. In row projection with one partition, on either feed, the count SHALL survive a
  restart of trade-processor: after the restart, `trades.missingTradeSides` SHALL equal the number of
  trade sides the engine booked in the current epoch that never committed to SQL `trades`, and SHALL NOT
  read 0 while any such side exists. A restart that skipped n sides SHALL write one log line
  `ALERT trade-feed restart GAP tradeSeq <last committed> (last applied before this start) -> <first after
  the start>: <n> trade sides were booked while the projection was down and never reached it (total
  missing=<m>)`. A side redelivered after it committed, and the first side of a new epoch, SHALL NOT count.
  The last committed `tradeSeq` of each epoch is stored in `projection_cursor` (row `trade-seq:<id prefix>`,
  partition 0, `stream_seq`), written in the same transaction as the batch that applied it.
  A side that reaches the projection but that SQL refuses (it is acked and never commits) SHALL count as
  missing when it is refused, with one log line `ALERT trade-feed REFUSED <n> trade side(s): SQL refused them
  and they never committed (total missing=<m>)` per refused batch. The epoch's running count is stored beside
  the watermark (row `trade-seq:missing:<id prefix>`, `stream_seq`) and carried into the next start
  (proof-audit suspect 14: an option fill refused by narrowed symbol columns read 0 after a restart).
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
  (`all` or a comma list) select what a replica consumes. In row mode each partition SHALL hold its own
  `PROJECTION_WRITE_LOCK` row. Snapshot writes (FR-DRS-08) SHALL all take the one row of partition 0, so
  two partitions' snapshot transactions never deadlock on each other's index ranges.
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

### Bridge receipt publish ([ADR-083](../../system/adr-083-bridge-receipt-publish.md))

These apply on the cluster member whenever `TRADE_BRIDGE_NATS_URL` is set. Only the leader publishes.
"Quiet" means no trade has been applied for 2 s.

- FR-DRS-11: Within one bridge subject (`/trades.<p>`, or `/trades` when P=1), the trade sides the
  stream stores SHALL appear in non-decreasing engine `tradeSeq` order. The same applies to `/orders.<p>`
  in apply order.
- FR-DRS-12: When `TRADERX_FEED` captures the bridge subjects, every published message SHALL get exactly
  one outcome. Once quiet, for each bridge, `published = acked + rejected` and `acked` equals the number
  of that bridge's messages the stream stored. Messages that never reached NATS (lane queue full, or a
  failed publish call) SHALL count in `traderx_bridge_dropped`, never in `published`.
- FR-DRS-13: Each member's `GET /health` SHALL carry
  `bridges: {"trades": B|null, "orders": B|null}`, where B is
  `{"published", "acked", "rejected", "failed", "rejectedByPartition": [P numbers]}`. `null` means
  the bridges are not enabled. `rejectedByPartition` SHALL have exactly P entries summing to `rejected`, and
  entry p SHALL count only rejections of messages published on partition p. A follower SHALL report
  zeros. The counters are per process and start at 0 on restart.
- FR-DRS-14: Each member's `/metrics` SHALL expose the counters `traderx_bridge_published`,
  `traderx_bridge_dropped` and `traderx_bridge_ingest_rejected`, with labels `member` and
  `bridge="trades"|"orders"`. `traderx_bridge_ingest_rejected` SHALL equal `/health`
  `bridges.<bridge>.rejected`.
- FR-DRS-15: A rejection SHALL write the log line `ALERT <trade|order>-nats-bridge partition <p>
  (<subject>): NATS dropped <n> message(s) at stream ingest (429 Too Many Requests) ...` for that
  partition's first rejection and every 10,000th one (n = 1, 10000, 20000, …).
- FR-DRS-16: The trade bridge SHALL name each rejected trade side in lines of the form `ALERT
  trade-nats-bridge partition <p> (<subject>) NATS-rejected ids: <id> <id> …`. A line is written every
  500 rejections, and at the latest 1 s after the lane goes quiet. Once quiet, the ids across these
  lines SHALL be exactly the trade sides the engine filled that the stream does not hold, each named
  once and on its own partition's line. The order bridge counts only and names no ids.
- FR-DRS-17: A rejected message SHALL NOT be published again. No rejected id ever appears on the stream,
  and FR-DRS-11 holds through any rejection.
- FR-DRS-18: The repair source for a rejected trade side SHALL be the member archive in consensus order:
  `GET /recon/catchup-events` (FR-APR01..04) serves every fill, including the rejected ones, never the
  bridge.

### Settlement, recon and retention on the trade store

- FR-DRS-19: In snapshot mode, a fill the bond rule rejects (`TradeService.book`) SHALL appear in its
  account's blotter window as `Rejected` and SHALL NOT create or change a position.
- FR-DRS-20: In snapshot mode, `GET /v2/projection-feed/settlement?asOf=<ISO instant>` (default now)
  SHALL return `{scope, asOf, tPlusDays, sides, settled, unsettled, partitions: {<p>: {sides, settled,
  unsettled, streamSeq, days: {<trade date>: {sides, settlesOn, settled}}}}}`. The trade date is the
  UTC date of the trade's `created` time. `settlesOn` is the trade date plus `SETTLEMENT_T_PLUS_DAYS`
  business days (weekends skipped). Counts SHALL cover exactly the non-rejected sides at or below
  each partition's committed cursor. Outside snapshot mode it SHALL answer 404.
- FR-DRS-21: In snapshot mode, the live-blotter recon SHALL read the stream. A blotter side SHALL be
  MATCHED (or FIELD_MISMATCH) only once its partition's committed cursor covers it, and MISSING only once
  its own partition's subject holds a later `tradeSeq` without it. Until then it is held, and later
  sides wait behind it.
- FR-DRS-22: In row projection, a blotter side with no SQL row SHALL be held, not counted, while it is
  younger than `RECON_PROJECTION_GRACE_MS` (default 120000), measured from its engine `execTimeMillis`.
  On the jetstream feed it SHALL also be held until the feed has caught up (no message pending on any owned
  partition) at an instant at least one grace after that `execTimeMillis`, so a side still behind a replay
  after an outage ends MATCHED, never MISSING.
  The recon cursor (`/recon/status` `cursor`) SHALL stay below it. If the row lands inside the grace,
  the side SHALL end MATCHED. Otherwise the first sweep after the grace SHALL count it
  MISSING_IN_PROJECTION. Every side already older than the grace is decided in that same sweep.
- FR-DRS-23: At startup in snapshot mode, a partition whose committed cursor is above the stream's
  last sequence SHALL refuse with `STREAM_RESET`. One whose cursor + 1 is below the stream's first
  retained sequence SHALL refuse with `RETENTION_GAP`. Either refusal SHALL log the code and the
  partition and exit the process with status 3. While its consumer is idle and caught up, a partition's
  cursor SHALL follow the stream head, so an idle partition never trips `RETENTION_GAP`.
- FR-DRS-24: The stream SHALL be created or updated to `PROJECTION_FEED_STREAM_MAX_BYTES` and
  `PROJECTION_FEED_STREAM_MAX_AGE_HOURS` (GKE tier: 20 GiB, 2 h, on a 32 Gi `nats-data` PVC). The NATS
  ingest queue SHALL be configured per tier: GKE 2,000,000 msgs / 1 GB, kind 500,000 / 256 MB, and the
  `kubernetes-runtime` base manifests 1,000,000 / 512 MB.
- FR-DRS-25: The CompSci GKE render (`scripts/gcp-compsci/prepare.py`), the production tier, SHALL run
  trade-processor with `PROJECTION_FEED_MODE=jetstream` and row projection, with the JetStream store on
  the `nats-data` PVC. The render SHALL refuse a candidate without both. On that tier, a trade side the
  engine books while trade-processor is down or restarting SHALL reach SQL `trades` exactly once after it
  is back, and `positions` SHALL equal the signed sum of the `trades` rows, with `missingTradeSides` 0.
  This holds for outages within the stream retention of FR-DRS-24. A side the stream discarded before the
  consumer reached it SHALL count in `missingTradeSides` (FR-DRS-02). The bond rule, SQL settlement and
  the recon of FR-DRS-22 SHALL behave as on the core feed.
- FR-DRS-26: The member's recon rows (`GET /recon/trades/blotter`, the full-history index and the regulatory
  audit rows) SHALL carry each price at the engine's full tick precision (6 dp), never rounded, so a bond's
  fraction of par (YU16 ADR-057) compares equal to the 6-dp `trades.price` the trade bridge wrote. A price
  exact at 3 dp SHALL keep its 3-dp rendering. Before this, the member used the 009 equity edge rounding
  (`Px.toDecimalOrZero`, 3 dp HALF_UP) and every bond side was FIELD_MISMATCH (proof-audit suspect 13:
  `UST-20310630` blotter 0.997 vs SQL 0.996650).
- NFR-DRS-01: The defaults (`core`, persist `true`, mode `rows`, fan-out `true`, one partition) SHALL keep
  the pre-component wire bytes and SQL behaviour, apart from the FR-DRS-02 watermark and missing-count rows.
- NFR-DRS-02: No engine, ack, snapshot or wire format changes.

## Success Criteria

- SC-DRS-01: In jetstream mode a `kill -9` mid-drain books every published trade side, with 0 gaps.
- SC-DRS-02: In snapshot mode a `kill -9` before the first snapshot replays every published trade side,
  and every position equals an independent recomputation.
- SC-DRS-03: `TradeSeqGapsTest`, `JetStreamFeedTest`, `OrderViewTest`, `TradeViewTest`,
  `BridgePartitionsTest`, `LedgerSettlementReconTest`, `ReconciliationProjectionGraceTest`,
  `TradeSnapshotStoreConcurrencyIT`, `ReceiptLanesIT`, `ClusterReconPriceTest` and `TradeFeedRefusalTest` pass in an empty-root render.
- SC-DRS-04: Live, with the default ingest queue and a 400K/s burst, the bridges' rejected ids equal
  the engine's fills minus the stream, which equal the recon's MISSING set. With the GKE queue, the
  same burst rejects 0.
- SC-DRS-05: Live, `yu18-projection-effect-end.sh NEGATIVE=1` books a cross while trade-processor is scaled
  to 0. On the core feed, after it returns, SQL holds 4 of 6 sides and `missingTradeSides` is 2. On the
  production render (FR-DRS-25), SQL holds 6 of 6 sides, positions are +15/-15 and equal the rows, and
  `missingTradeSides` is 0.
