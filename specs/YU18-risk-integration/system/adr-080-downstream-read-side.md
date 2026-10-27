# ADR-080: The downstream read side at 400K orders/s

Status: accepted (yaakov, 2026-10-26 and 2026-10-27). Component: [downstream read side](../components/downstream-read-side/spec.md).
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

Every new path is opt-in by environment variable. The defaults (`core`, `true`, `rows`, `P=1`) keep the
pre-ADR wire and behaviour.

## Consequences

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
