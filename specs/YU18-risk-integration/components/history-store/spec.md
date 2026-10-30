# History store specification

Status: prototype, 2026-10-29. Dependencies: the `TRADERX_FEED` capture stream (downstream-read-side, FR-DRS-05
and §7 of `docs/engineering/downstream-at-400k.md`), ClickHouse 25.8. Chosen over QuestDB 9.1.0 and KDB-X
Community 5f by the bake-off in `docs/engineering/history-store-bakeoff.md`.

## Requirements

- FR-HST-01: Every trade side and every order event the bridges publish on the partition subjects
  (`/trades.<p>`, `/orders.<p>`, `p = accountId mod P`) SHALL be stored as exactly one row of `trades` or
  `orders`, carrying its partition `part` and its stream sequence `seq`. Trades keep `trade_seq`, `side`,
  `account`, `security`, `qty`, `px`, `order_ref`, `cseq` (consensus sequence) and `ts` (the envelope `date`);
  orders keep `order_ref`, `account`, `security`, `side`, `qty`, `remaining`, `limit_px`, `status`, `last_px`,
  `last_qty`, `created`, `cseq`, `ordinal` (output ordinal), `client_key` and `ts` (`updatedAt`). Ids are stored
  as their numeric parts (the epoch prefix is the run's, not the row's).
- FR-HST-02: The feed (`HistoryFeed`) SHALL run one lane per (feed, partition) on a durable pull consumer
  `hs-<name>-<feed>-p<p>` (AckPolicy.All, ackWait 30 s), buffer up to `batch` rows (100,000) or `flushMs`
  (500 ms), write the buffer in one atomic store write, and ack the stream only up to the store's durable
  watermark for that (table, partition). It SHALL NOT ack a message before the row it carries is durable.
- FR-HST-03: Exactly once. The store SHALL be the cursor: on start a lane reads max(`seq`) for its (table,
  partition), deletes its durable and recreates it at `seq + 1`, and skips any delivered message at or below
  the cursor. It SHALL NOT resume the old durable: that redelivers the dead process's unacked window
  interleaved with new messages, and an AckPolicy.All ack of a later message retires earlier redeliveries that
  were never written (measured: 19 of 360,000,000 order events lost across one `kill -9`).
- FR-HST-04: ClickHouse writes SHALL be one `INSERT ... SELECT ... FROM input(...) FORMAT TabSeparated` per
  batch into MergeTree tables `PARTITION BY toDate(ts) ORDER BY (account, ts)` with `fsync_after_insert=1` and
  `fsync_part_directory=1`; the INSERT returning is the durability point the ack waits for.
- FR-HST-05: The store SHALL answer, from the same tables: (a) one account's fills today, paged by
  `ORDER BY ts DESC, seq DESC LIMIT 100 OFFSET n`; (b) one account's VWAP against the market's per security
  over a window (TCA), and VWAP by account and security over the window; (c) the full end-of-day export of both
  tables; (d) one account's position and average buy price per security as of a time. The queries are the ones
  `HsbQuery` runs.
- NFR-HST-01: No engine, bridge wire, stream, trade-processor, production or GKE change. The feed is a reader of
  the existing stream with its own durables.
- NFR-HST-02: On one c4d-standard-16 store host the store SHALL sustain the full-rate volume, 1,000,000 rows/s
  (400K orders/s: 1 trade side and 1.5 order updates per order).

## Success Criteria

- SC-HST-01: Bake-off rig (`scripts/history-store-bakeoff/vm/`), stream filled with 600,000,000 events
  (240,000,000 orders), the feed `kill -9`ed 240 s into the drain and restarted: the store holds
  240,000,000 trade rows and 360,000,000 order rows, distinct (`part`, `seq`) and distinct business key
  (`trade_seq*2+side`, `order_ref*4+ordinal`) equal to the row counts. Met 2026-10-29 (ClickHouse r3; QuestDB and
  KDB-X on the same restart path).
- SC-HST-02: Sustained ingest at or above 1,000,000 rows/s through the drain. Met: 1.14M rows/s over the first
  240 s, 1.10M over the whole drain, at the feed's own ceiling (1.37M rows/s with no store), 3.8 cores and 2.9 GiB
  anon on the store host; 21.8 GiB of active parts for the 10-minute volume; queries (a) 5.9 ms, (b) 298 ms,
  (b2) 502 ms, (d) 4.9 ms warm; export 600M rows in 61 s. Recorded in `docs/engineering/history-store-bakeoff.md`.
