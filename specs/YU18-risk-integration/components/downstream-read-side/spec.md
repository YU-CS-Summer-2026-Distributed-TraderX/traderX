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
  With no stream capturing the subject the lane logs an ALERT and publishes nothing kept; a last message that
  carries no position is FR-DRS-40's gap. A stream it cannot read is retried on a new connection (FR-DRS-38). Eviction past the
  cut is logged as an ALERT and counted (FR-DRS-38). `/health` `bridges` reports `replayed`. Found on production 2026-11-08 (epoch e1108): the
  members agreed at 80,888 trades, and `/trades`, the read model and the history store held 80,884. Sides
  16169..16172 were applied in the second after the leader was killed; e1107b showed 81,146 against 81,140.
  `ThreeMemberClusterTest#tradesInFlightAtALeaderKillReachTheStreamExactlyOnce` stops the leader's bridge, books
  three more matches and kills the leader. Before this rule the stream held sides 1..4 of 1..10. `ReceiptLanesIT`
  holds the cut on both bridges, with managed and with legacy ids (FR-DRS-40), including the records applied after
  the promotion.
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
  Past what a lane holds: while NATS is unreachable the lane waits in its reconnect and its queue (65,536) holds
  what the member applies; once the queue is full it refuses the newest records, which is the queue rule below. Past
  the ring: if the newest record the lane no longer keeps is after the stream's last, the catch-up logs an ALERT,
  increments `/health` `bridges.*.gaps`, and still publishes what it kept, in order. On kind (2026-11-09, FR-DRS-40's
  control run) NATS stayed down 49 s on its fourth restart (kubelet's crash backoff): the order lane, at about 2,000
  events/s, refused 36,293 and counted the gap; the trade lane, at about 1,000 sides/s, held everything. At P=6 and 400K orders/s a trade lane fills its ring in about 1 s, and an order
  lane in about 0.5 s. So a NATS pod restart at full rate is a loud gap, repaired from the archive. At kind's rate
  in run 2 (about 1,200 sides/s and 2,400 order events/s) the rings cover about 55 s and 27 s.
  Found on kind 2026-11-08 (trade-history lane, run 1). NATS was OOM-killed three times at its 768Mi limit. The
  members agreed at 1,846,840 sides, and the stream and the history store both held 1,845,888: sides
  1,259,773..1,260,724 were missing.
  `ThreeMemberClusterTest#tradesInFlightAtANatsRestartReachTheStreamExactlyOnce` freezes NATS while the leader
  sends sides 5..10, SIGKILLs it, starts it again and books 11..14. Before this rule the stream held 1..4 and 11..14.
  `ReceiptLanesIT#aNatsRestartRepublishesWhatTheStreamLost` covers both bridges with managed and legacy ids, and
  `ReceiptLanesIT#aNatsOutageLongerThanTheRingIsALoudGap` the gap.
  Superseded by FR-DRS-47 (2026-11-10): there is no lane queue to refuse a record. The apply thread writes into the
  lane's ring, which holds what a blocked lane has not reached (`BRIDGE_LANE_RECORDS`, 1,048,576 by default), so an
  outage that the 65,536-record queue turned into a gap after about 1 s at 400K is now held for about 16 s. A record
  the ring overwrote before the lane reached it is a counted gap with an ALERT at the next catch-up, as eviction is;
  `ReceiptLanesIT#aPromotedLaneLappedPastItsRingIsACountedGap` replaces `#aRecordDroppedAtTheQueueIsACountedGapAtTheCatchUp`.
  The queue fills when a lane publishes slower than the apply thread offers. Measured locally (2026-11-08, P=6,
  0 to 400K sides/s in one step on a JVM 3 s old): the publish path is still interpreted, the lanes run at 33-42K
  sides/s each against 67K/s offered, and the queues fill in 1-2.6 s unless the JIT finishes first. One cold run in 18 on
  this build dropped 50,338 sides and one in 12 on the build before it (9728ba51) dropped 583; six runs after a 4 s
  warm-up at 100K/s peaked at 1,649 queued. A member promoted under full load publishes with the same cold path.
- FR-DRS-39: Before a lane takes its first record it SHALL run its own encode-and-publish path on 20,000 sample
  records, so the JIT has compiled that path when load arrives: at a member's first load, and on a follower promoted
  under load, which until then has only kept records. The samples go to the scratch subject `_bridge.warm.trade` or
  `_bridge.warm.order`, which no stream captures and nothing subscribes to, so NATS discards them. They are stored
  nowhere and counted in no bridge counter. The warm-up runs once per lane, on its lane thread, after its first
  connection; a failure stops it with a WARN and the lane carries on.
  Measured locally (2026-11-09, the FR-DRS-38 cold start: P=6, 0 to 400K sides/s in one step, 2 s after the bridge
  starts). In three runs with a JIT log, `encode`, the lane's `send` and jnats' `publishInternal` were all C2-compiled
  0.9-1.7 s before the load began; without the warm-up `encode` reached C2 0.4-5.8 s into it. Over 10 runs the queues
  peaked at 3,674 in the first 3 s, and no run dropped a side. Without the warm-up the worst of 10 runs reached 56,512
  of 65,536, and one of 18 dropped 50,338.
  `ReceiptLanesIT#aLaneWarmsItsPublishPathOnAScratchSubjectBeforeItPublishes` counts both bridges' samples on the
  scratch subjects, and checks that the stream and the counters hold only the real record.
- FR-DRS-40: An order event SHALL carry its position (`consensusSequence`, `outputOrdinal`) under every id scheme,
  so FR-DRS-37 and FR-DRS-38 hold for order events on a runtime with no run descriptor. That is what production and
  kind run (ADR-101; production's `/health` read `runDescriptorHash: null` on every member in epochs e1107 to
  e1108b). Until this rule only epoch-v1 ids wrote the position, so the legacy order lane had no cut: after a
  promotion it republished nothing it had kept, and after a NATS restart the events it had sent but NATS had not
  stored were lost with an INFO line and no counter. Trade sides were never affected: a legacy trade id carries its
  `tradeSeq`.
  - The legacy message gains the two fields and nothing else: no scope, epoch, scheme or descriptor hash. The engine
    already assigns both to every output in every scheme; the deterministic core, the log and the snapshot are
    unchanged.
  - A legacy message cannot name its epoch, so the cut relies on the stream starting empty with the epoch
    (FR-PRG-29), as the trade cut does.
  - A stream whose last message has no position (a member build before this rule wrote it) gives no cut. When the
    lane then holds records it applied while following or sent earlier, it cannot tell whether they are stored: it
    SHALL count a gap (`gaps`), log an ALERT, and publish only what it has not yet sent as leader.
  - Readers. trade-processor's order view already orders by the pair when present, so a repeat or an earlier event
    no longer overwrites a newer one. Its SQL order projection SHALL accept the pair on an unattributed event and
    count it for no run (before: `RUN_DESCRIPTOR_MISMATCH` on every order event). history-feed holds a positioned
    order event once (FR-THT-11). The gateway's FIX fill feed and the algo engine read by field and ignore the pair.
  - Tests. `ThreeMemberClusterTest#tradesInFlightAtANatsRestartReachTheStreamExactlyOnce` (legacy ids) now also
    requires every order's FILLED event on the stream and the order events in apply order, once each; before this
    rule 6 of 10 orders read FILLED. `ReceiptLanesIT#aPromotedMemberPublishesOnlyWhatTheStreamIsMissing` and
    `#aNatsRestartRepublishesWhatTheStreamLost` run under both schemes (legacy failed: no position stored).
    `ReceiptLanesIT#aStreamWhoseLastOrderEventHasNoPositionIsACountedGap` (before: gaps 0).
    `OrderBatchProjectionIT#aLegacyUpdateWithAPositionIsWrittenOnBothPathsAndCountsForNoRun`.
  - On kind (2026-11-09, `scripts/proofs/kind-bridge-nats-restart.sh`): production's default shape with legacy ids,
    4 x 250 orders/s for 180 s, NATS SIGKILLed twice in place under a steady leader. With this rule the stream held
    145,590 of the members' 145,590 new sides and 291,180 of 291,180 order events; the leader republished 90 sides
    and 166 order events and counted no gap. The control, the same members rolled to the build before this rule, lost
    45 of 289,908 order events behind INFO lines only, with every trade side held. An earlier pair read the same
    way (333,528 of 333,528; the control lost 68 of 285,724).
- FR-DRS-41: A bridge lane SHALL publish everything its queue holds, up to `BRIDGE_BATCH_MAX` records (default 64, at
  most 1,024) and 256 KiB, as ONE NATS message: the records in apply order, each on its own line (the bridges' JSON
  holds no newline). One message and one receipt per drain instead of per record. At a quiet rate a drain holds one
  record and the wire is byte for byte the one before this rule; under load the drain fills, which is when the server's
  per-message cost is the wall: one NATS server stores about 400K messages/s of this wire whatever their size up to
  about 1 KB, and 1.5M records/s at 10 per message (downstream-at-400k.md §7.13 and §7.14). `BRIDGE_BATCH_MAX=1` is
  the one-record wire. The 400K campaign (RI34) found one server dropping from about 285K messages/s and the leader at
  about 185–200K orders/s with 16 publisher and 16 receipt threads; this rule attacks both.
  - The reply subject is `<inbox>.<n>.<first>~<last>` on the trade bridge (`<inbox>.<n>` on the order bridge): the
    record count, then the first and last key, so a 429 still names what was lost and `published`, `acked` and
    `rejected` stay in records; `/health` `bridges.*.messages` counts the messages. A message is stored or 429'd whole.
  - The catch-up (FR-DRS-37/38) compares the LAST record of the stream's last message; its own republish is batched.
  - Readers split a message on the newline before their per-record parse: trade-processor's trade store, order view,
    SQL order projection, recon and core-mode subscriber; history-feed (one row per record, each with the message's
    `seq`, FR-HST-01); the gateway's FIX fill feed. trade-processor's trade view holds its lock across one message's
    sides and keys its replay check by (stream sequence, record index), so a snapshot never cuts through a message and
    a message's second side is not taken for a replay of its first.
  - Ordering, exactly-once and catch-up are unchanged: a partition's records are in apply order within and across
    messages; dedupe keys (`tradeSeq`; `consensusSequence`, `outputOrdinal`) are per record.
  - Tests. `ReceiptLanesIT#everyIngestDropIsCountedAndEachPartitionStaysInOrder` runs at 1 and at 64 records per
    message (the 64-KiB ingest queue forces 429s of batched messages; counts in records; order across records);
    `ReceiptLanesIT#aCatchUpCutsAtTheLastRecordOfABatchedMessage` (300 sides in under 300 messages, a promotion
    republishes exactly 281..310); `HistoryFeedRateIT` at 1 and 64 per message (1M records: 3.5 s and 2.7 s);
    `RowsTest#a_batched_message_is_one_row_per_record_each_with_the_stream_sequence`;
    `FeedBarrierTest#aBatchedMessageBooksEveryRecordItHolds` (before: the view booked 2 of 4, taking a message's
    second side for a replay). `ThreeMemberClusterTest#tradesInFlightAt*` read the stream by record.
  - On kind: `scripts/trade-history-tiers/kind-history-rate.sh` now checks store rows = the bridges' published records
    and stream messages = their published messages. Results in RI34.

- FR-DRS-42: The capture stream (every `TRADERX_FEED*` stream trade-processor creates) SHALL be s2-compressed on
  disk (`compression: s2`); a stream created before this rule is brought to it by the same `ensureStream` that
  brings an older stream to its limits, and compression applies to the blocks written from then on. The bridges'
  JSON compresses about 2–3x. Why: at 400K orders/s the batched wire is about 480 MB/s of records (downstream-at-400k.md
  §7.15); the GKE campaign of 2026-11-09 saw the server's volume saturate twice (a 416 GB pd-balanced at 298 MB/s and
  io-busy 100%, then a Hyperdisk Balanced provisioned at 1,200 MB/s at 684 MB/s once the streams reached `max_bytes`
  and deleted while writing), each time stalling the JetStream API on a partition for 8–30 s, cutting the readers as
  slow consumers and leaving the leader's publishes unacked. With s2 the same load wrote 311 MB/s at 32% busy and the
  write fell to 311 MB/s at 32% busy, and the Hyperdisk still saturated (724 MB/s) once the streams reached
  `max_bytes` and deleted while writing. `PROJECTION_FEED_STREAM_STORAGE=memory` keeps the stream in the server's
  RAM with no volume under it (the throughput rig; `max_memory_store` and `PROJECTION_FEED_STREAM_MAX_BYTES` sized to
  the machine); unset or `file` is the production setting, and a memory stream has no compression. A server restart
  empties a memory stream, which the readers' STREAM_RESET path (FR-DRS-36) and the bridges' catch-up (FR-DRS-38)
  already cover. The NATS CPU cost of s2 is small against its 44 vCPUs (4–5 cores at 400K).

- FR-DRS-43: A pipelined feed (`JetStreamFeed.startPipelined`: the trade store, the order view, the SQL order
  projection) SHALL size each fetch by bytes, not messages: the next fetch asks for as many messages as the last
  batch's average message size fits in `FETCH_BYTES` (1 MiB), at most the configured batch. At the one-record wire
  that is the configured batch, as before; under FR-DRS-41's batching (20–40 records, ~12 KB a message) a 1,000-message
  fetch per partition, 16 partitions on one connection and two or three fetches in flight is hundreds of MB the server
  buffers for the client, and NATS cuts the connection at 64 MB pending ("Slow Consumer Detected"): GKE 2026-11-09,
  351 cuts in five minutes at 1.4M records/s, the read model 4–5M messages behind after each and unable to catch up
  at ~7 cores. history-feed, which fetches small batches, was cut 27 times in the same window and stayed current.
  Covered by `JetStreamFeedTest#aFetchIsSizedByTheLastBatchsBytes`. The structural follow-up (a read model sharded
  by partition across replicas, and a cheaper per-record parse) is RI36.
- FR-DRS-44: The per-partition capture streams SHALL be placed on the NATS cluster's servers by tag (ADR-103):
  `PROJECTION_FEED_STREAM_PLACEMENT` names the servers' tags, comma-separated (the production render:
  `nats-0,nats-1,nats-2`, each server tagged with its pod's name), and partition `p`'s stream is created with
  placement tag `p mod n`, so N servers hold ceil(P/N) partitions each and each writes its share of the bytes. A
  stream found on another server (created before the setting, or moved) SHALL be brought to its tag by a placement
  update, which NATS performs as a stream move; R1 keeps its messages through it. Unset or blank, the server places the
  stream (one server; the kind rig; the throughput rig's memory streams). The single-stream layout takes the first
  tag. The production render's per-server `max_file_store` is sized to that share (FR-PRG-41), so placement is part of
  the capacity, not a tidy-up: an unplaced render is refused there. Measured: FR-DRS-34 (four streams per server by
  tag, 1.00-1.25M msgs/s, 0 lost); RI34 §1b (one volume saturated at 400K). Covered by
  `JetStreamFeedTest#aPartitionIsPlacedOnItsServersTag`; the live placement is read back in the acceptance run
  (ADR-103 rollout).

- FR-DRS-45: trade-processor SHALL run as N replicas that each read a disjoint subset of the feed partitions, each
  partition read by exactly one live replica (RI36: one process reading 8 partitions × 2 feeds had no headroom at
  400–520K orders/s).
  - Ownership: `PROJECTION_FEED_PARTITIONS_OWNED` (the older `PROJECTION_FEED_OWNED` still read) is `all` or unset (every
    partition, as before), a list of partitions and ranges (`0,1`, `0-1`, `0-1,6`), or `replica:<k>/<N>`: replica k
    of N owns the block of partitions p with `p * N / P == k` (P = 8, N = 2: 0-3 and 4-7; N = 4: two each). A
    partition named twice or out of range, or a malformed value, SHALL refuse to start. Everything trade-processor
    does per partition SHALL take only the owned ones: the trade store's and the order view's loads, `requireRetained`
    and replays, pull threads and snapshot loops, the SQL order projection's consumers. Stream creation and the write
    lock rows stay global: they are idempotent.
  - The guard: before any feed reads, the replica SHALL claim its partitions' rows in
    `projection_partition_owner(partition_id, replica_id, owned, partition_count, heartbeat_at)` in one transaction
    under row locks. A row held by another replica id whose heartbeat (database time) is younger than
    `PROJECTION_OWNER_TTL_MS` (20,000) is a live owner. The replica SHALL then re-check once after the TTL (a crashed
    predecessor's rows expire), and, if an owner is still live, refuse to start: `PARTITION_OWNED replica <id> ...
    partition <p> is owned by replica <owner> (owns <its ownership>, heartbeat <n> ms ago)`, exit 3, as
    `RETENTION_GAP` does, having read nothing. The replica id is `PROJECTION_REPLICA_ID` (the pod name), else
    `<hostname>:<pid>`; the same id reclaims its own rows at once (a restarted StatefulSet pod). The heartbeat
    SHALL refresh the rows every TTL/4. A heartbeat that finds a row taken SHALL stop the process
    (`PARTITION_OWNERSHIP_LOST`, exit 3). A failed heartbeat is retried, not fatal: every replica's rows go stale
    together without the database. A clean stop SHALL release the rows, so the next owner starts without waiting the TTL.
  - Health: `/v2/projection-feed` SHALL report `ownership` (`replica`, `ownedSpec`, `owned`, `partitions`) and only
    the owned partitions' views. `/v2/projection-feed/accounting` SHALL report the same three fields and only the owned
    partitions (trades, orders, their streams); the union of the replicas' answers is the whole read model, each
    partition in exactly one; `rig.sh`'s `accounting()` takes that union, refusing a partition answered twice or by
    none. `GET /accounts/{id}/orders` from the memory-only view SHALL answer 421 for an account another replica owns,
    naming its partition and owner, never an empty list, when the read is not routed (FR-DRS-46). The recon sweep (ReconciliationService,
    which reads the whole blotter against shared state and keeps one cursor per scope) SHALL run only on the owner of
    partition 0.
  - Deployment: one replica is the Deployment with `strategy: Recreate` (under RollingUpdate the surge pod is refused
    and the rollout never completes). N > 1 (`prepare.py --read-model-replicas N`, `kind-yu18-rig.py --tp-replicas N`,
    both `prepare.shard_read_model`) is a StatefulSet `trade-processor`, `podManagementPolicy: Parallel`, with
    `PROJECTION_REPLICA_ID` the pod name and `PROJECTION_FEED_PARTITIONS_OWNED=replica:$(TP_REPLICA_INDEX)/N` from the
    pod-index label, and a headless Service `trade-processor-replicas` naming each pod. The production snapshot profile
    (`--read-model snapshot`) SHALL render N = 4 (two of the 8 partitions each; `min(4, P)`), each replica at the one
    replica's measured size (12 CPU, 24Gi). `rig.sh` SHALL drive either shape (`wl trade-processor`). The order view's
    reads are routed by FR-DRS-46; the SQL projection is shared and needs none.
  Covered by `PartitionOwnershipTest` (the parse, the blocks cover each partition once, the guard's decision) and
  `PartitionOwnershipIT` (three processes on one NATS and MariaDB: halves, each answering for its own; a claim on an
  owned partition refused, exit 3, reading nothing, and passing with the guard disabled fails the IT; a released
  partition taken at once and the union exact after the handover); `test_prepare.SnapshotReadModel` (both shapes, and
  validation refuses a RollingUpdate Deployment or a StatefulSet whose ownership is not its pod index);
  `test_rig.Up#test_n_read_model_replicas_are_checked_by_the_union_of_their_accountings`;
  `scripts/proofs/kind-tp-ownership.sh` on kind (2 and 4 replicas).

- FR-DRS-46: The order view's reads SHALL keep their contract for every client with N replicas (ADR-103 §3): clients call
  the `trade-processor` Service as before, and `GET /accounts/{id}/orders` (open, `?status=all`, `?clientOrderKey=`;
  the gateway's FIX status and the console) SHALL answer what the account's owner answers, whichever replica receives
  it. A replica that does not own the account's partition (`accountId mod P`) SHALL forward the read, path, query and
  `Authorization` as received, to the live owner `projection_partition_owner` names (re-read at most every 2 s, and at
  once after a failed forward), at `PROJECTION_REPLICA_URL` with `{replica}` replaced by the owner's id (the render:
  `http://{replica}.trade-processor-replicas:18091`), and return its status and body. A forwarded read carries
  `X-TraderX-Routed` and SHALL NOT be forwarded again (a non-owner answers it 421). No live owner, or one that cannot be
  reached, SHALL answer 503 naming the partition, never an empty list. `/v2/projection-feed` `ownership.orderReads`
  counts reads answered locally, forwarded and refused. Unset `PROJECTION_REPLICA_URL` (one replica) routes nothing.
  The SQL routes need no routing. yaakov's condition ("if this help then yes", 2026-11-10): the router is kept only if
  it holds the contract and beats the fan-out-and-merge alternative (the caller asks every replica and merges) on
  latency and throughput. Measured on kind (4 replicas, 8 partitions, after 330,882 sides; one 11-CPU node, so absolute
  numbers are the laptop's): open orders at 16 concurrent reads, router 1,450 reads/s p50 8.8 ms p99 42 ms, fan-out 635
  reads/s p50 21.7 ms p99 80 ms, asking the owner directly 1,410 reads/s p50 10.1 ms p99 34 ms: the router at the floor,
  2.3× fan-out's throughput; `?status=all` (16 MB, 12,500 orders a read) at 2, router 6/s p50 300 ms p99 1,369 ms,
  fan-out 5/s p50 342 ms p99 1,101 ms, owner 8/s p50 221 ms p99 802 ms: even, each paying the body once more than
  the floor. The router is kept. Covered by `PartitionOwnershipIT` (each account in each shape through either replica =
  its owner's; a routed read at a non-owner 421; no live owner 503; the forward count) and
  `scripts/proofs/kind-tp-ownership.sh` steps 5-6 (`order-read-bench.mjs`).

- FR-DRS-47: A leader change at rate SHALL lose nothing from the stream. Every trade side and order event the cluster
  applies reaches its `TRADERX_FEED` subject exactly once, in apply order, while the promoted member's lanes cannot
  read the stream or publish, for as long as their ring holds what the member applies meanwhile. A lane queue's
  overflow is therefore not a loss: there is no queue. The apply thread SHALL write each record into its lane's ring
  and never have it refused. The ring has `BRIDGE_LANE_RECORDS` slots per lane: 1,048,576 by default, at least
  131,072. The lane reads the ring by index and publishes what follows the catch-up's cut. Whatever holds the lane
  thread - the catch-up's stream read, a reconnect, a publish into a frozen socket - the records keep landing, and the
  lane publishes the backlog in order when it resumes.
  - A lane releases a record once it no longer needs it: read while following, or published while leading, and older
    than the last 65,536 (FR-DRS-37/38's tail). A lane that keeps up therefore holds what it held before this rule.
    Only a stall fills the ring.
  - `/health` `bridges.*.maxBacklog` is the most records a leading lane has held unread or unpublished: a stall the
    ring absorbed and recovered. `queuedByPartition` is each lane's current backlog. The catch-up's log line gives
    how long the stream read took and how many records were applied meanwhile.
  - `queueDropped` (and `traderx_bridge_dropped`) now counts, exactly, the records a leading lane never published
    because it fell more than its ring behind after its catch-up. Each such lap is also a gap and an ALERT. A lap
    during a catch-up is a counted gap (FR-DRS-38): how many of the overwritten records the old leader stored cannot
    be told.
  - Sizing. At 500K orders/s on 8 partitions a trade lane takes about 63K records/s and an order lane about 127K/s.
    The queue of 65,536 held about 1 s and 0.5 s. The default ring holds about 16 s and 8 s. The slots cost 4 MiB per
    lane, 64 MiB for 16 lanes, and records occupy the heap only while held. A full ring is about 64 MiB of trade
    records or 130 MiB of order records per lane: 1.6 GiB if all 16 lanes fill at once, against the members' 12 GiB
    heap.
    Measured locally against the parent build. Setup: P=6 trade lanes, 200K sides/s for 15 s after a 3 s warm-up,
    Docker NATS, two runs per arm. Every side was stored and none dropped in either arm.

    | | this build (2^20) | parent (65,536) |
    |---|---|---|
    | Process CPU | 20.7–21.0 s | 20.5–20.6 s |
    | GC | 126–141 ms | 133–169 ms |
    | Live heap after a full GC | 78 MB | 48 MB |
    | The apply thread's offer, record included | 24.6–25.5 ns/side | 21.8–34.8 ns/side |
    | Peak backlog | 3,533–4,748 | n/a |

    The extra live heap is the six lanes' slot arrays: 6 x 4 MiB.
  - Found on GKE 2026-11-10 (RI37, the ADR-103 re-test run D). The leader was killed at about 510K orders/s and every
    member agreed at 1,003,275,732 sides. The history store held 980,327,391. The new leader's
    `trades.queueDropped` was 22,948,341, the shortfall exactly, and its `orders.queueDropped` was 46,297,224. The
    FR-DRS-37 catch-up itself was small and correct: 6 to 24 records per lane after the stream's last. The loss was
    the lane thread not draining its queue while blocked. That is about 45 s of each lane's flow in about three
    episodes (`gaps` 24 over 8 trade lanes). It matches a stream read held by JetStream API stalls of 8 to 30 s under
    this load (FR-DRS-42). A catch-up allows 10 attempts of two 2 s requests each.
  - Tests. `ReceiptLanesIT#aPromotedLaneThatCannotReadTheStreamKeepsWhatItAppliesMeanwhile` freezes NATS, promotes a
    follower and offers 100,000 sides and order events. Its catch-up blocked 3.25 s with 99,936 sides held. Every
    record was stored once, in order, with `queueDropped` 0 and `maxBacklog` above 65,536. The control is the parent
    build at its 65,536 queue, the same test: 65,700 of 100,100 sides stored, 34,400 missing, behind the same ALERT
    lines as GKE. `ReceiptLanesIT#aPromotedLaneLappedPastItsRingIsACountedGap` (a 1,024-slot ring, 5,000 applied in
    the stall): one gap, the 1,024 kept in order. On kind (2026-11-10, `scripts/proofs/kind-bridge-failover-at-rate.sh`, production's default shape,
    one partition, 4 x 250 orders/s): NATS frozen 121 s, the leader killed 3 s in. With this rule the history store
    held 231,662 of the members' 231,662 sides (G3h) and 463,324 of 463,324 order events, with `queueDropped` 0 and
    no gap on every member. The promoted leader's lanes held up to 95,618 sides and 191,317 order events. The
    control, the parent's image, is the same run. It held 201,966 of 232,066 sides and 338,327 of 464,132 order
    events. The new leader's `queueDropped` was 30,100 and 125,805: each shortfall exactly, as on GKE. On GKE (2026-11-10, run E3: run D's render, members on this
    rule's image only, 1:45–1:52 pm ET), the leader was killed at about 465K sides/s and member 1 led 1.81 s later.
    Member 2 began a second election 4.9 s after that, and member 1 won it and kept the lead (corrected 2026-11-10:
    first recorded as one election, because `failover.sh` stops at the first new leader; RI38). The members agreed at 183,194,994 sides. The history store held 1..183,194,994, each once
    (G3h). `queueDropped` was 0 and `gaps` 0 on every member. The promoted lanes caught up in 201–221 ms and held
    at most 8,681 sides and 17,443 order events each (`maxBacklog` 9,254 and 18,595).

    Run D's 15–45 s stall did not recur on this fresh rig: what each lane held fits inside the old queue. E3 shows
    the failover lossless at rate on this build, but alone it does not discriminate against the old one. The kind
    proof and `ReceiptLanesIT` do, with their stalls induced.
  - Not caused by the bridges, and not changed by this rule: the second election 4 s after the first (member 2, then
    member 1). The apply thread's offer never blocks, before or after this rule. Run D kept no member logs. E3, which
    did, had one too (above), so the election's cause is open in RI38. Under this rule a second promotion is one more catch-up within the ring, not a
    second loss window.

- FR-DRS-48 (ADR-084 amendment, FR-FFR-14; 2026-11-10): The order view SHALL route each update of an order whose
  booking event named its gateway (`gateway`, the submitting session's principal) to `/fills.<gateway>` on the
  `TRADERX_FILLS` stream (`FILLS_STREAM`; R1, file, `FILLS_STREAM_MAX_BYTES` and `FILLS_STREAM_MAX_AGE_MINUTES`:
  2 GiB / 10 min in the code, the kind figure, ~5 s at 450K orders/s — the production snapshot render sets them from
  the rate, `FILLS_WINDOW_MINUTES` 3 at `READ_MODEL_RATE` × 882 B per order ≈ 71 GB, the window a gateway's ordered
  consumer may fall behind by and still find its next message, a drain and an in-place restart included, and adds the
  stream to every server's store budget since the one server holding it is any of them (FR-PRG-41;
  `test_prepare.AcceptanceShape`; what the window does not buy is in FR-FFR-14);
  created idempotently by trade-processor like the capture, placement per FR-DRS-44's first tag), so a gateway reads
  only its own orders' events instead of the whole feed. The view SHALL keep an order's gateway from its booking
  event across later updates (as it keeps `clientOrderKey`), and the gateway SHALL NOT be part of the event digest
  (`OrderProjectionService.digest` removes it as it removes `clientOrderKey`: a routing hint is not economic event
  identity, and every event retained before the field existed must still verify — `ClientOrderKeyDigestTest`), and
  the router SHALL publish, per view batch and per
  gateway, one message holding that gateway's records of the batch newline-joined in stream order (FR-DRS-41's
  shape), the record bytes as the capture carried them. Every update of one order reaches the one replica that owns
  its partition (FR-DRS-45), so an order's routed events are in order on its subject. An update of an order the view
  never held booked is not routed (the gateway's per-order check reports the gap, ADR-084 §7). `FILLS_ROUTE=0` turns
  the router off; with no booking event naming a gateway it publishes nothing. `/v2/projection-feed` `orders.fills`
  SHALL report `routed` (events), `messages`, `acked`, `failed` and the per-gateway counts. Covered by
  `FillsRouterTest` (the batch shape; the view keeps the gateway named at booking and none for an order booked
  without one), `FillsRouterIT` (NATS: each gateway's subject holds only its own orders' events, one message per
  batch per gateway, acks counted, the stream's subject `/fills.>`), and the kind proof of FR-FFR-14.
- FR-DRS-49 (RI38, 2026-11-10): A lane SHALL NOT release a record the stream has not confirmed stored. This holds in
  every role, so a dead leader's unstored tail survives on its successor for as long as the successor's ring holds it.
  The tail is what the leader applied but did not publish, plus what it published and the stream did not store.
  FR-DRS-37's catch-up then republishes it, each record once and in order.
  - Before this rule, a follower kept only its last 65,536 records per lane. On 2026-11-09 a stalled volume left about
    1.9M sides unacked, and a full archive then killed the leader: §7.15 item 4, the "FR-DRS-33 ack gap". FR-DRS-33
    is the reader's ack and does not cover it; FR-DRS-47's ring keeps only what the promoted lane applies itself.
  - Each lane asks the stream for its last stored message on its subject. It uses the same request as the catch-up
    (`$JS.API.STREAM.MSG.GET`, last by subject, after one `STREAM.NAMES` lookup), but sends it asynchronously, so
    the lane thread never waits on it. It asks at most every 250 ms, and only when the keep would otherwise release
    past what the stream last confirmed. The lane then never releases a record after that position. Records are in
    apply order, so a bisection finds the position.
  - A current stream confirms well within the keep (about 32K records at 127K/s), so a lane that keeps up holds what
    it held before. A stalled stream, or a dead leader not yet replaced, holds records in the ring instead. Cost: one
    small request per lane per 250 ms at most, 64 per second for a member's 16 lanes.
  - Past the ring, the oldest unstored records are overwritten. The successor's catch-up counts that as a gap
    (FR-DRS-38), as before.
  - With no JetStream, no stream on the subject, or a last message without a position, the keep alone decides, as
    before.
  - The same rule covers a stall within one leader's life: a reconnect's catch-up (FR-DRS-38) finds everything the
    stream did not store, not just the last 65,536.
  - Test. `ReceiptLanesIT#aDeadLeadersUnstoredTailLongerThanTheKeepIsRepublishedByItsSuccessor`: NATS freezes after
    sides and order events 1..100 are stored, both members apply 101..100,100, and the leader publishes them into the
    frozen socket. NATS is then killed, so none is stored, the leader dies and the follower is promoted. Each subject
    reads back 1..100,100 once, in order, with gaps 0. Control, this test on the parent (`922c327e`): 65,636 of
    100,100 sides stored, with 34,464 missing behind the catch-up's gap ALERT. `#aNatsOutageLongerThanTheRingIsALoudGap`
    now keeps the 16 its ring holds (before: the last 8). On kind (2026-11-10, `kind-bridge-failover-at-rate.sh` with `KILL_AFTER=100 FREEZE_SECS=140 SECS=420 RATE=400`,
    production's default shape, one partition, the members' image differing from the control's in `ReceiptLanes`
    only): NATS was frozen 141 s and the leader killed 100 s into the freeze, with 118,052 sides applied since the
    freeze and none of them stored. With this rule, G3h held all 387,332 sides, each once. The store held 774,665
    order events against the 774,664 the sides need; the load had 5 ambiguous orders. `queueDropped` was 0, with no
    new gap. The promoted leader republished the 108,828 sides and 241,208 order events it had held while following,
    and `reconnects` was 0. The control is the same run on the parent's image, `ReceiptLanes` as at `922c327e`. Its
    tail was 121,886 sides. The store held 407,430 of 465,088 sides, 57,658 lost, and 752,959 of 930,176 order events.
    The promoted leader republished exactly 65,536 of each and counted a gap on each bridge.
  - FR-DRS-38's stall check, fixed with this rule. A promoted lane read the time it spent following as silence: its
    first publish counted as 5 s without a receipt, and it reconnected and caught up a second time. On GKE run E3 all
    16 lanes did this at the promotion. Silence now counts only while publishes wait for receipts, and a catch-up's
    stream read does not count either. `/health` `bridges.*.reconnects` counts dropped connections.
    `ReceiptLanesIT#aPromotionDoesNotReadTheTimeSpentFollowingAsSilence`.

- NFR-DRS-01: The defaults (`core`, persist `true`, mode `rows`, fan-out `true`, one partition) SHALL keep
  the pre-component wire bytes and SQL behaviour, apart from the FR-DRS-02 watermark and missing-count rows and
  FR-DRS-40's position on an order event.
- NFR-DRS-02: No engine, ack, snapshot or wire format changes, apart from FR-DRS-40's two fields on `/orders`.

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
