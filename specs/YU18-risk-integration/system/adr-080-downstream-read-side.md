# ADR-080: The downstream read side at 400K orders/s

Status: accepted (yaakov, 2026-10-26 and 2026-10-27; decision 5 the evening of 2026-10-27). Component: [downstream read side](../components/downstream-read-side/spec.md).
Measurements: `docs/engineering/downstream-at-400k.md`.

## Context

The engine sequences 400K orders/s. The trade-processor projection behind it measured ~950 orders/s
end to end on 2026-10-25. Two things were wrong with it. Each trade row cost ~6 serial SQL round
trips. And lag became loss: an unbounded in-process queue OOM-killed the 256 MB heap in 11 s at
100K msgs/s, and the restart lost the whole backlog because core NATS does not replay.

## Decision

1. **Bound and count first (dddcdc86).** The feed queues are bounded (20K,
   `-Dtraderx.{trade,order}-feed.queue-capacity`). A full queue drops, counts and WARNs. It never OOMs.
   `TradeSeqGaps` counts trade sides missing from the engine's contiguous per-leg `tradeSeq`.
   `GET /v2/projection-feed` exposes backlog, dropped and missing. Writes are batched at 1000.
2. **A replayable feed, partitioned by account (79b754f7).** `PROJECTION_FEED_MODE=jetstream` pulls
   `/trades` and `/orders` from the JetStream capture stream `TRADERX_FEED`, with one durable
   consumer per owned partition. It acks a batch only after the SQL commit, so delivery is
   at-least-once. `BRIDGE_PARTITIONS=P` makes the leader bridges publish on
   `<subject>.<accountId mod P>`. The JSON bytes are unchanged, so the cold replay verifier stays
   byte-identical. Positions and orders never cross a partition.
3. **Orders in memory (79b754f7).** With `ORDER_FEED_PERSIST=false` there is no `orderbook` row per
   order event. `OrderView` holds open orders plus a ring of recent terminal ones, and answers
   `GET /accounts/{id}/orders` in the same `OrderRow` shape, so the gateway's FIX status source is
   untouched. A restart rebuilds the view from the last `ORDER_VIEW_REPLAY_SECONDS` of the stream.
4. **An append-only trade store (33589539).** The JetStream stream is the projection's system of
   record. With `TRADE_PROJECTION_MODE=snapshot`, `TradeView` books every trade side in memory with
   `TradeService.book`, the same math as the row path. `TradeSnapshotStore` writes one transaction
   every `TRADE_PROJECTION_SNAPSHOT_MS`: the changed positions, each changed account's last
   `TRADE_PROJECTION_BLOTTER_DEPTH` fills, and the stream cursor in `projection_cursor`. A restart
   loads the snapshot and replays from `stream_seq + 1`. Anything at or below the cursor is ignored.
   The kdb capture stays the queryable history.

5. **Production runs rows over the durable feed; loss is counted across restarts (2026-10-27).** The
   spec-only proof audit found that on the default tier a cross booked while trade-processor was down
   never reached SQL (4 of 6 sides, positions short by 5) while `/v2/projection-feed` read
   `missingTradeSides 0`. The gap counter lived in memory and took the first side after a start as its
   baseline. Two changes:
   - The CompSci render, the production tier, sets `PROJECTION_FEED_MODE=jetstream` and keeps row
     projection, with JetStream on the 32 Gi `nats-data` PVC. The render refuses either missing
     (FR-DRS-25). An outage is then lag: the durable consumer resumes from its ack floor and the replay
     books the outage's sides once, because the batch path drops an id it already holds.
   - In row projection, the last committed `tradeSeq` of each epoch is written to `projection_cursor` in
     the batch's own transaction. The first side after a start is measured against it, so a lossy restart
     raises `missingTradeSides` and an ALERT on any feed (FR-DRS-02). On the durable feed this also counts a
     side that retention discarded before the consumer reached it.

   Production stays on row projection, not snapshot. Snapshot mode is the 400K/s design and turns SQL
   `trades` into a per-account window of `TRADE_PROJECTION_BLOTTER_DEPTH` fills. The SQL readers of
   `trades` (position-service's trade history, SQL settlement, the recon, the projection transition) would
   then read a different table. The demo books well under the row path's ~25K rows/s ceiling, so moving buys
   nothing there and changes what SQL holds. The row path keeps the bond rule, settlement and recon
   unchanged. The one recon change: after an outage it waits for the feed to catch up before calling a
   side MISSING (FR-DRS-22).
   The NATS sizing is unchanged by this: the PVC, the 1 CPU / 512 Mi request and the 2 Gi limit were rolled
   with the YU18 NATS. The stream it now fills is capped at 20 GiB and 2 h (FR-DRS-24).

The other paths stay opt-in by environment variable. The defaults (`core`, `true`, `rows`, `P=1`) keep the
pre-ADR wire and behaviour, plus the decision-5 watermark row.

## Consequences

- What the production tier guarantees (decision 5): a trade side the engine books while trade-processor is
  down reaches SQL exactly once when it returns, for outages within the stream retention (20 GiB, 2 h).
  Any side that does not reach SQL, on any tier and either feed, shows in `missingTradeSides` after a restart.
- Readers are unchanged. Positions and the per-account trades window stay in SQL, at most one snapshot
  interval stale in snapshot mode.
- On a snapshot-mode tier, settlement and the live-blotter recon read the stream and the committed cursor
  (FR-DRS-20, FR-DRS-21). The bridges count every ingest drop ([ADR-083](adr-083-bridge-receipt-publish.md)).
- Measured on a c4d-standard-16: the stream dropped nothing at any offered rate up to 640K msgs/s, and a
  `kill -9` mid-drain booked 600,000 of 600,000 trade sides. In snapshot mode one partition booked 72K
  trade sides/s and four booked 159K/s. The row path is capped by the MariaDB instance at ~25–36K rows/s,
  not by the consumer.
- New NATS subjects exist only when `BRIDGE_PARTITIONS>1`.
- No wire, ack or snapshot format changes.

**Rejected:** a second store for the trade log. JetStream is already deployed, replays, and holds the
bytes the verifier checks.
