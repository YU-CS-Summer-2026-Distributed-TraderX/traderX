# Downstream read side specification

**2026-11-06 (trade-history-tiers FR-THT-01):** the trade feed's row projection and the core feed are retired; the
trade feed runs jetstream + snapshot only. Requirements and clauses scoped to "row projection" of trades (FR-DRS-02's
persisted watermark, FR-DRS-22's jetstream catch-up hold, the row-path cases of FR-DRS-35) no longer apply to trades;
the order feed's modes are unchanged.

Status: implemented, 2026-10-27 (FR-DRS-11..25 added the same day; FR-DRS-26 and FR-DRS-27 2026-10-28; FR-DRS-28, FR-DRS-29..32 2026-10-29). Dependencies: JetStream on the existing NATS server, the YU18 trade
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
  SHALL write, in one transaction, the changed positions, the sides booking refused since the last snapshot
  (the ADR-102 cutover, 2026-11-08, retired the per-account window of `TRADE_PROJECTION_BLOTTER_DEPTH` fills: the
  blotter reads the history store, FR-THT-09), and the per-partition stream cursor in
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
  of that bridge's messages the stream stored. Messages that never reached NATS because the lane queue
  was full SHALL count in `traderx_bridge_dropped`, never in `published`. A failed publish call is retried on a new
  connection (FR-DRS-38) and counts in `failed` only.
- FR-DRS-13: Each member's `GET /health` SHALL carry
  `bridges: {"trades": B|null, "orders": B|null}`, where B is
  `{"published", "acked", "rejected", "failed", "replayed", "gaps", "rejectedByPartition": [P numbers],
  "queuedByPartition": [P numbers]}`. `queuedByPartition` is each lane queue's depth now (capacity 65,536). `null` means
  the bridges are not enabled. `rejectedByPartition` SHALL have exactly P entries summing to `rejected`, and
  entry p SHALL count only rejections of messages published on partition p. A member that has
  never led SHALL report zeros for `published`, `acked`, `rejected` and `failed`. The counters are per process and start at 0 on restart.
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

- FR-DRS-19: In snapshot mode, a fill the bond rule rejects (`TradeService.book`) SHALL be written to SQL `trades`
  as a `Rejected` exception row (the blotter shows it in place of the history row, FR-THT-09) and SHALL NOT create
  or change a position.
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

### Filled quantity ([ADR-088](../../system/adr-088-canceled-order-fill.md))

- FR-DRS-27: Every order the read model returns (`GET /accounts/{id}/orders`, both projections) SHALL carry
  `filledQuantity`, the quantity filled so far: `quantity − remainingQuantity` while open, `quantity` when
  FILLED, and for CANCELED or REJECTED the order's filled quantity before that update (0 when the read model
  holds no earlier update), because the engine reports those with `remainingQuantity` 0. The gateway's
  `OrderStatusRequest`/`OrderMassStatusRequest` reports SHALL use it as `CumQty`. Against a read model that
  does not send it, `CumQty` SHALL be 0 for a CANCELED or REJECTED order, never its whole quantity.
  The SQL projection SHALL derive it from the row's own previous state (column `orderbook.filledquantity`,
  added by `900-migrations.sql`), and it SHALL NOT enter the event digest.

### Batched orderbook writer

- FR-DRS-28: With `traderx.order-feed.persist=true`, a batch of order updates
  (`OrderProjectionService.persistAll`) SHALL be applied in arrival order under exactly the rules of one
  update: scoped sequence and output ordinal (a late update never rewinds an order), `ORDER_EVENT_CONFLICT`
  on a same-position digest mismatch, the FR-DRS-27 filled quantity, trace id and client order key carried
  forward, and the sealed legacy run's identical-state check. It SHALL then write only each order's
  resulting row, every mapped column, in multi-row `INSERT … ON DUPLICATE KEY UPDATE` statements of at most
  500 orders, before the run counter update, in the batch's one transaction.
  - The `orderbook` rows and the run counters (`order_updates`, `checkpoint_seq`) SHALL equal, column for
    column, what the same updates leave applied one per transaction (`persist`).
  - A batch with any refused update SHALL write nothing; the feed then retries it one update at a time, as
    before. The stream is acknowledged after the commit, so a redelivered batch changes nothing and a
    process killed inside a batch leaves none of it.
  - A batch SHALL issue a fixed number of statements, independent of its size up to 500 orders: the run row
    is read once per scope per batch, for managed and unattributed (`legacy-unknown`) runs alike.
  - No new configuration.

### Exactly-once accounting and the order view snapshot (the production snapshot read model, ADR-097)

- FR-DRS-29: In snapshot mode each trade partition SHALL keep, committed in the same transaction as its cursor
  (`projection_accounting`), the count of sides it booked, the highest `tradeSeq`, and the sums of `tradeSeq` and
  `tradeSeq²` modulo 2^64.
  - Within a partition the bridge publishes in engine order (one receipt lane per partition), so a side whose
    `tradeSeq` is not above the partition's last booked one (same epoch prefix) SHALL NOT be booked. It SHALL move
    the cursor, be counted as a duplicate, and be logged as an ALERT.
  - `GET /v2/projection-feed/accounting` SHALL return, per partition, the committed cursor and accounting, and the
    stream's last sequence on that partition's subject, with the sums as unsigned decimal strings. It reads committed
    state only, so every replica gives the same answer.
  - With duplicates refused, the booked sides are distinct. Count, highest and the two sums equal to the closed form
    over an expected range then establish the booked set as that range in O(P), at any volume (FR-PRG-21).
- FR-DRS-30: With `traderx.order-feed.persist=false` and `traderx.order-view.snapshot-ms` above 0, each order
  partition SHALL write, every `snapshot-ms`, its open orders that changed since the last snapshot (upsert, the update
  as JSON in `order_view_snapshot`), delete those that are no longer open, and move its cursor
  (`projection_cursor` scope `order-view:<scope>`), all in one transaction, on the partition's own pull thread.
  - A restart SHALL load the partition's committed open orders, refuse (`RETENTION_GAP`, `STREAM_RESET`) when the
    stream no longer holds the message after the cursor or is a different stream, and replay from `cursor + 1`.
  - An order that opens and ends between two snapshots SHALL never be written, so the work is bounded by open orders
    and not by the update rate.
  - With `snapshot-ms` 0 (the default) the view SHALL rebuild from the last `replay-seconds`, as before. An order
    that rested longer than that is then absent after a restart, which is why the production profile sets it
    (FR-PRG-20).
  - Terminal orders are not kept. After a restart the view holds the terminal ones the replay brings back.
- FR-DRS-31: The consumers whose committed cursor is their acknowledgement (the trade store, the order view
  snapshot) SHALL pull pipelined. A fetch thread keeps the next pull in flight while an apply thread applies the
  previous batch, through a hand-off of two batches. The consumer SHALL allow 4 batches unacknowledged so the pulls can
  overlap. Batches and idle notices SHALL reach the apply thread in fetch order. A stopped fetch thread SHALL stop
  its apply thread. Ack-driven consumers (row projection, durable) SHALL keep the serial fetch-apply-ack loop: there a
  batch acknowledged ahead of an earlier failed one would be lost.
  - A cursor-acknowledged view SHALL move its cursor to the stream head when idle only when, read after the head,
    its consumer has nothing pending AND nothing ack pending (the ack follows the apply). Before this rule only
    "pending" was checked. A message delivered to the client after an empty fetch is not pending, so the cursor
    passed it and the next fetch ignored it as a replay: 1,408 of 3,000,000 sides were lost at P=16 and 50K orders/s,
    and the FR-DRS-29 accounting named the deficit. `JetStreamFeedIdleTest` pins the rule on both loops.
- FR-DRS-32: `traderx.feed.stream-layout=partition` (`PROJECTION_FEED_STREAM_LAYOUT`) with P > 1 SHALL capture each
  partition in its own stream, `<stream>_P<p>`, subjects `/trades.<p>` and `/orders.<p>`, each with the stream
  limit divided by P. Every feed, retention check, accounting read and ledger-recon reader SHALL use the partition's
  stream. The default `one` keeps the single stream. A stream is one serial ingest-and-delivery path in NATS. On
  the bench one stream stored ~95K of 405K msgs/s offered, while eight per-partition streams stored 357K msgs/s with
  none dropped (ADR-097).
- FR-DRS-33: No JetStream feed SHALL let a cumulative ack (AckPolicy.All, the last message of each batch) retire a
  message it was delivered and did not apply. The server redelivers such a message only after ackWait (60 s), and
  the next batch's ack covers it first. That happens to a batch delivered to a process killed before applying it,
  and to a pull response lost with its connection. Every delivery carries the next consumer sequence, so the feed
  SHALL check that the deliveries it receives are contiguous. On a jump, or when its handler throws, it SHALL
  recreate its consumer at the last message it applied + 1. A durable SHALL be recreated the same way at every
  start, from its ack floor (the ack follows the apply). The redelivered messages are applied twice, and both row
  projections already take that safely: a trade side whose id is booked is skipped, and an order update at or below
  the row's consensus sequence and ordinal is ignored. `JetStreamFeedAckGapIT` runs on a real JetStream. Before this
  rule the durable resumed after a kill -9 lost the batch lifetime A was delivered, and the running durable and the
  pipelined feed each lost a stolen pull response.
  The one-off recon of rows written before this rule (`scripts/gcp-compsci/rig/fr-drs-33-order-fill-recon.sql`, removed with rows mode on 2026-11-06 in favour of `scripts/trade-history-tiers/order-fill-recon.clickhouse.sql`; read
  only, before trading) compares each order row's filled quantity with its trade sides, and lists trade sides whose
  order has no row. A booking through the gateway's `/trades` (the demo ticket, `TYPE_TRADE_NEW`) is a trade with no
  order: since ADR-100 its sides publish `bookingSource: TICKET` and no `sourceOrderId` (null join key), and no order
  row is ever written for it. Sides published before ADR-100 named the reserved `<epoch>-0`; the engine issues refs
  only on `ORDER_NEW`, in `[1, 2^31-1]`, failing closed at the ceiling, so excluding `-0` cannot hide a real order.
  The ticket's sides are still counted by the rig's G3 trade check. Production 2026-10-30: disagree 0 against 3,392,648 agreeing
  filled orders; the only order without a row was `1-0`, the 2026-10-27 roll's ticket check (`13-B`, `14-S`).
- FR-DRS-34: The capture SHALL be sized to store 1M msgs/s of the bridges' JSON wire (400K orders/s) with 0 lost
  and 0 backlog, with the bridges publishing receipts (FR-DRS-32's layout, §7.10's publish):
  - `PROJECTION_FEED_STREAM_LAYOUT=partition` with P=12;
  - R1 streams `TRADERX_FEED_P<p>` placed four per server on a three-server NATS cluster of c4d-standard-16;
  - each server with the GKE ingest queue (2M / 1 GB).

  One NATS server stores ~0.4–0.5M msgs/s whatever the stream count (8–32), so it SHALL NOT be the 400K-tier capture.
  The JetStream-acked publish held 270–548K msgs/s and SHALL NOT be the bridges' mode at that tier.
  Measured: `docs/engineering/downstream-at-400k.md` §7.13, with `HsbLoad mode=receipt servers=3` (the bench harness
  and `vm/capture.sh` are on commit `3ecfa9d8`, not landed here). On three servers, 180M of 180M were stored at 1.00M msgs/s, and
  1.25M msgs/s was held with 0 lost and 24 reader lanes 0.2–0.3 s behind. Loss is read at the server, as published
  minus stored after the streams settle, and every receipt run satisfies stored = PubAcks and lost = 429s.
- FR-DRS-35: A store failure SHALL be a barrier on every JetStream feed: no message after it is applied, and no
  cursor, snapshot or ack passes it, until it is applied. A store failure is a failure of the database rather than
  the message (a Spring `DataAccessException` or `TransactionException`, or an `SQLException`, other than a
  constraint or data violation). The feed applies the message again: the serial feed by recreating its consumer
  (FR-DRS-33), and the pipelined feed by retrying the failed batch, with backoff, before anything queued behind it.
  Any other failure refuses that one message, which the handler SHALL skip, log and count. That covers a malformed
  message, a failed run validation and a constraint, even when a transaction exception wraps the constraint. The
  trade store counts refusals in `refused` on `/v2/projection-feed`. The row projections keep counting them in
  `missingTradeSides` and `rejected`; on the core feed, which cannot redeliver, a store failure is still counted
  there. A pipelined consumer the server deleted while its apply thread was held at a barrier (the inactive
  threshold) SHALL be recreated after the last message it handed to the handler. Found by the codeX review of
  2026-10-30 (§1): snapshot mode logged a side whose run lookup failed as skipped, booked the next one, and its
  snapshot saved the cursor past a valid trade, which replay then ignored. `FeedBarrierTest` covers the trade
  store, both row projections and the classification. The pipelined case of `JetStreamFeedAckGapIT` holds stream
  sequence 25 at a barrier for 4 s against a 1 s inactive threshold. Before this rule, these four tests failed:
  sequences 25..30 were lost, and nothing was thrown.
- FR-DRS-36: A sequence is not an identity. Every saved cursor (the trade store's and the order view's, in
  `projection_cursor`) SHALL be bound to the stream it was taken on, by that stream's creation time in
  `stream_created_ns`, before the first message after the resume is applied. A restart SHALL refuse a cursor past 0
  bound to another creation time (`STREAM_RESET`, naming both times). A stream deleted and recreated under the same
  name restarts its sequences, and once it grows past the cursor it passes both sequence checks (FR-DRS-08's
  retention gap and reset). Resuming at the cursor + 1 would then skip its first messages for good. The remedy is
  explicit: reconcile, then set the row's `stream_seq` to 0 and `stream_created_ns` to NULL. A first binding SHALL
  rest on evidence, not assumption: a cursor past 0 with a NULL binding (a row written before this rule) may already
  sit on a recreated stream, so it is refused (`STREAM_UNBOUND`) until the read model is reconciled (rig G3, the
  FR-DRS-33 recon) and an operator attests the row. The attestation SHALL name the stream that was reconciled: it is
  the binding itself, that stream's creation time, written by the statement the refusal prints for each row
  (`UPDATE projection_cursor SET stream_created_ns=<ns> WHERE projection_scope=… AND partition_id=… AND stream_seq=<cursor>
  AND stream_created_ns IS NULL`). It changes a row only while the row holds that cursor unbound, so replaying it grants
  nothing, and a stream recreated after it is refused at the start (`STREAM_RESET`) like any other. One start SHALL
  list every unbound partition of a view. A bare `0`, the first build's attestation, names no stream and is refused
  (codeX review §16: A reconciled with `0`, recreated as B, and B's new6..new8 alone delivered). A cursor at 0 skips
  nothing and binds or rebinds without either. A running feed
  SHALL NOT resubscribe across streams: a cursor-acknowledged view stops its partition (ALERT), and a durable (the
  idempotent row projections) reads the new stream from its first message, as a restart would. An update of the
  stream's limits keeps its creation time. Found by the codeX review of 2026-10-30 (§6): cursor 5, a recreated
  stream of 8, and only 6..8 delivered; its re-review found the first build accepted an unbound cursor 5 on that
  recreated stream. `StreamIdentityIT` and `StreamRecreateIT` run on NATS 2.10 and 2.14; `AttestationIT` runs the printed attestation on
  MariaDB and NATS 2.14.
  A persistent eod-price-db volume never runs `ri06.sql` again, so `stream_created_ns` reaches it only through
  `scripts/gcp-compsci/rig/migrate-fr-drs-36.sql` (the same two idempotent statements), applied before the build runs
  snapshot mode or order-view snapshots; rows mode never reads the column. Its last statement lists every unbound
  cursor past 0, each of which the build refuses until it is reconciled and attested. Rolling the image back needs no schema
  change: the earlier build names its columns, and a binding it leaves stale is refused, not skipped.
- FR-DRS-37: Every trade side and order event the cluster applies SHALL be stored on its `TRADERX_FEED` subject
  exactly once, in apply order, across a leader change. Only the leader publishes, so the bridges SHALL NOT depend
  on the role at apply time: every member offers every record, tagged with its role. A lane keeps the records its
  member applied while following (the last `capacityPerLane`, 65,536), and when the member becomes leader it reads
  the last message the stream stored on its subject and publishes the kept records after it, in order, before
  anything new. Records applied after the promotion are held to the same cut, so a record the log applies on the
  new leader after its role change that the old leader had stored is skipped. A trade side's position is its
  `tradeSeq` (one per side). An order event's is (`consensusSequence`, `outputOrdinal`). A message of another epoch
  or id scheme means none of this run's records is stored. When the subject is empty, everything kept is published.
  With no stream capturing the subject, or a legacy order message that carries no position, the lane logs an ALERT
  and publishes nothing kept. A stream it cannot read is retried on a new connection (FR-DRS-38). Eviction past the
  cut is logged as an ALERT and counted (FR-DRS-38). `/health` `bridges` reports `replayed`. Found on production 2026-11-08 (epoch e1108): the
  members agreed at 80,888 trades, and `/trades`, the read model and the history store held 80,884. Sides
  16169..16172 were applied in the second after the leader was killed; e1107b showed 81,146 against 81,140.
  `ThreeMemberClusterTest#tradesInFlightAtALeaderKillReachTheStreamExactlyOnce` stops the leader's bridge, books
  three more matches and kills the leader. Before this rule the stream held sides 1..4 of 1..10. `ReceiptLanesIT`
  holds the cut on both bridges with managed ids, including the records applied after the promotion.
- FR-DRS-38: The same exactly-once, in-order guarantee SHALL hold across a NATS restart under a steady leader. The
  bridges publish without waiting for the stream's confirmation, so what was in flight when NATS died is not stored.
  A lane therefore keeps its last `capacityPerLane` records (65,536) in every role, not only while following, and
  replaces its own connection instead of using jnats' reconnect, which would resend jnats' buffer to the restarted
  server and race the stream read. A connection is replaced when NATS closes it, when a publish throws, or when no
  receipt (ADR-083) arrives for 5 s with publishes outstanding. On every new connection while leading, the lane runs
  the FR-DRS-37 catch-up: it reads the subject's last stored message and publishes the kept records after it, in
  order, before anything new. Before reading, it waits until two reads 200 ms apart agree, so a message the server is
  still storing from the old connection is not sent twice. If the stream cannot be read, the lane retries on a new
  connection while the ring keeps absorbing records. A failed publish is retried this way and is no longer counted as
  dropped.
  Past the ring: an outage longer than the ring holds loses the oldest records. If the newest record the lane no
  longer keeps is after the stream's last, the catch-up logs an ALERT, increments `/health` `bridges.*.gaps`, and still
  publishes what it kept, in order. At P=6 and 400K orders/s a trade lane fills its ring in about 1 s, and an order
  lane in about 0.5 s. So a NATS pod restart at full rate is a loud gap, repaired from the archive. At kind's rate
  in run 2 (about 1,200 sides/s and 2,400 order events/s) the rings cover about 55 s and 27 s.
  Found on kind 2026-11-08 (trade-history lane, run 1). NATS was OOM-killed three times at its 768Mi limit. The
  members agreed at 1,846,840 sides, and the stream and the history store both held 1,845,888: sides
  1,259,773..1,260,724 were missing.
  `ThreeMemberClusterTest#tradesInFlightAtANatsRestartReachTheStreamExactlyOnce` freezes NATS while the leader
  sends sides 5..10, SIGKILLs it, starts it again and books 11..14. Before this rule the stream held 1..4 and 11..14.
  `ReceiptLanesIT#aNatsRestartRepublishesWhatTheStreamLost` covers both bridges with managed ids, and
  `ReceiptLanesIT#aNatsOutageLongerThanTheRingIsALoudGap` the gap.
  A record the lane's full queue refuses never reaches the ring, so no catch-up can republish it. The lane SHALL
  count it as a gap with an ALERT: at the next catch-up if it is after the stream's last message (a follower's drop
  that a promotion would otherwise publish around), or at once while leading. It still counts in
  `traderx_bridge_dropped`. `ReceiptLanesIT#aRecordDroppedAtTheQueueIsACountedGapAtTheCatchUp` fills a follower's
  queue of 8 with 12 sides and promotes it; before this rule `gaps` stayed 0.
  The queue fills when a lane publishes slower than the apply thread offers. Measured locally (2026-11-08, P=6,
  0 to 400K sides/s in one step on a JVM 3 s old): the publish path is still interpreted, the lanes run at 33-42K
  sides/s each against 67K/s offered, and the queues fill in 1-2.6 s unless the JIT finishes first. One cold run in 18 on
  this build dropped 50,338 sides and one in 12 on the build before it (9728ba51) dropped 583; six runs after a 4 s
  warm-up at 100K/s peaked at 1,649 queued. A member promoted under full load publishes with the same cold path.

- NFR-DRS-01: The defaults (`core`, persist `true`, mode `rows`, fan-out `true`, one partition) SHALL keep
  the pre-component wire bytes and SQL behaviour, apart from the FR-DRS-02 watermark and missing-count rows.
- NFR-DRS-02: No engine, ack, snapshot or wire format changes.

## Success Criteria

- SC-DRS-01: In jetstream mode a `kill -9` mid-drain books every published trade side, with 0 gaps.
- SC-DRS-02: In snapshot mode a `kill -9` before the first snapshot replays every published trade side,
  and every position equals an independent recomputation. The proof (`yu18-jetstream-feed.sh`) SHALL establish
  the window: the restarted process books at least one side the killed one had not snapshotted. A kill that
  missed the window is an ordinary restart; the proof SHALL refuse it, not pass it.
- SC-DRS-03: `TradeSeqGapsTest`, `JetStreamFeedTest`, `OrderViewTest`, `TradeViewTest`,
  `BridgePartitionsTest`, `LedgerSettlementReconTest`, `ReconciliationProjectionGraceTest`,
  `TradeSnapshotStoreConcurrencyIT`, `ReceiptLanesIT`, `ClusterReconPriceTest`, `TradeFeedRefusalTest` and `FeedBarrierTest` pass in an empty-root render.
- SC-DRS-04: Live, with the default ingest queue and a 400K/s burst, the bridges' rejected ids equal
  the engine's fills minus the stream, which equal the recon's MISSING set. With the GKE queue, the
  same burst rejects 0.
- SC-DRS-06: `OrderBatchProjectionIT` passes in an empty-root render: per-update vs batched at batch sizes
  1000, 37 and 1 on an adversarial stream (reordering across batches, redelivery, multi-output commands,
  typed and legacy orders; every column and the counters), a refused batch, rollback then replay, a sealed
  legacy run, and SIGKILL inside a batch that has written uncommitted rows. Opt-in
  `OrderBatchThroughputIT`, 1000-update batches, 1 ms added between the JVM and MariaDB: an unattributed
  stream (the CompSci render today) goes from 929 to 58,315 updates/s, a managed one from 3,017 to 38,949.
- SC-DRS-07: `SnapshotReadModelIT` passes in an empty-root render, on the shipped schema with a real JetStream, P = 2.
  A first lifetime books sides 1..1000 and opens 300 orders, then 100 of them fill. While it is down, for longer than
  the 1 s replay window, sides 1001..1500 arrive, plus a second copy of side 1200 and 50 cancels. The second lifetime
  then holds:
  - 150 open orders, in both the view and the committed snapshot;
  - accounting equal to the closed form over 1..1500, with the copy refused and counted as 1 duplicate;
  - every position equal to the net of the sides.
  Negative controls: with the view snapshot off (`-Dsrm.viewSnapshotMs=0`) the view holds 0 of 150, and with the
  duplicate refusal removed 1,501 sides are booked. `TradeViewTest` covers the refusal and epoch handling directly.
  It runs on the per-partition layout by default and on the single stream with `-Dsrm.layout=one`, and passes on both.
- SC-DRS-05: Live, `yu18-projection-effect-end.sh NEGATIVE=1` books a cross while trade-processor is scaled
  to 0. On the core feed, after it returns, SQL holds 4 of 6 sides and `missingTradeSides` is 2. On the
  production render (FR-DRS-25), SQL holds 6 of 6 sides, positions are +15/-15 and equal the rows, and
  `missingTradeSides` is 0.
