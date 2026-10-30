# History store specification

Status: implemented 2026-10-30 as the `history-feed` service, local and kind only (FR-HST-06); bake-off prototype
2026-10-29. Dependencies: the `TRADERX_FEED` capture streams (downstream-read-side FR-DRS-05, FR-DRS-32, FR-DRS-33),
ClickHouse 25.8. Chosen over QuestDB 9.1.0 and KDB-X Community 5f by the bake-off in
`docs/engineering/history-store-bakeoff.md`. Decision: [ADR-098](../../system/adr-098-history-store.md).

## Requirements

- FR-HST-01: Every trade side and every order event the bridges publish on the partition subjects
  (`/trades.<p>`, `/orders.<p>`, `p = accountId mod P`) SHALL be stored as exactly one row of `trades` or
  `orders`, carrying its partition `part` and its stream sequence `seq`. Trades keep `trade_seq`, `side`,
  `account`, `security`, `qty`, `px`, `order_ref`, `cseq` (consensus sequence) and `ts` (the envelope `date`);
  orders keep `order_ref`, `account`, `security`, `side`, `qty`, `remaining`, `limit_px`, `status`, `last_px`,
  `last_qty`, `created`, `cseq`, `ordinal` (output ordinal), `client_key` and `ts` (`updatedAt`). Ids are stored
  as their numeric parts (the epoch prefix is the run's, not the row's).
- FR-HST-02: The feed SHALL run one lane per (feed, partition) on a durable pull consumer, `history-<feed>-p<p>`
  on the partition's stream (`<stream>_P<p>` with `PROJECTION_FEED_STREAM_LAYOUT=partition`, the single stream
  otherwise, the same naming as trade-processor's `JetStreamFeed.streamFor`), AckPolicy.All, ackWait 60 s. It SHALL
  buffer up to `HISTORY_BATCH_ROWS` rows (100,000) or `HISTORY_FLUSH_MS` (500 ms), write the buffer in one atomic
  store write, and ack the last message only after that write is durable. A buffer SHALL NOT cross a UTC day: one
  INSERT is atomic only within one date partition. The bake-off prototype (`scripts/history-store-bakeoff`) uses
  `hs-<name>-<feed>-p<p>` and acks to a store-reported watermark, which also serves KDB-X's write-down.
- FR-HST-03: Exactly once. The store SHALL be the cursor: on start a lane reads max(`seq`) for its (table,
  partition), deletes its durable and recreates it at `seq + 1`, and skips any delivered message at or below
  the cursor. It SHALL NOT resume the old durable: that redelivers the dead process's unacked window
  interleaved with new messages, and an AckPolicy.All ack of a later message retires earlier redeliveries that
  were never written (measured: 19 of 360,000,000 order events lost across one `kill -9`). The same reopening SHALL
  happen, in the running process, when a delivery's consumer sequence is not the previous one + 1 (a pull response
  lost with its connection, FR-DRS-33), after it writes what it holds, and after any failed write.
- FR-HST-04: ClickHouse writes SHALL be one `INSERT ... SELECT ... FROM input(...) FORMAT TabSeparated` per
  batch into MergeTree tables `PARTITION BY toDate(ts) ORDER BY (account, ts)` with `fsync_after_insert=1` and
  `fsync_part_directory=1`; the INSERT returning is the durability point the ack waits for.
- FR-HST-05: The store SHALL answer, from the same tables: (a) one account's fills today, paged by
  `ORDER BY ts DESC, seq DESC LIMIT 100 OFFSET n`; (b) one account's VWAP against the market's per security
  over a window (TCA), and VWAP by account and security over the window; (c) the full end-of-day export of both
  tables; (d) one account's position and average buy price per security as of a time. The queries are the ones
  `HsbQuery` runs; the service answers them over HTTP (FR-HST-06).
- FR-HST-06: The `history-feed` service (`generation/runtime-overrides/history-feed`, rendered; image
  `traderx/history-feed:yu18`) SHALL read the capture streams only, never create or size one, with trade-processor's
  environment names (`NATS_ADDRESS`, `PROJECTION_FEED_STREAM`, `PROJECTION_FEED_PARTITIONS`,
  `PROJECTION_FEED_STREAM_LAYOUT`) plus `CLICKHOUSE_URL`. It SHALL serve `GET /health` (per lane: stream, cursor,
  rows, pending, recreates, write failures; 503 until every lane is open) and, on port 18095,
  `/history/accounts/{id}/fills?day=&limit=(≤1000)&offset=`, `/history/accounts/{id}/tca?from=&to=`,
  `/history/accounts/{id}/positions?asOf=`, `/history/vwap?from=&to=` (JSON) and `/history/export/{trades|orders}?day=`
  (CSV, streamed). Times are ISO-8601 instants or epoch ms, days ISO dates, UTC. Every caller value SHALL reach
  ClickHouse as a bound query parameter; a malformed value SHALL be a 400. Exactly one instance SHALL run (its lanes
  are not fenced): one replica, `Recreate`. Its manifests (`history-feed/k8s/`) are opt-in, applied by
  `scripts/history-feed/kind-smoke.sh`, and in no base kustomization, CompSci render or GKE manifest.
- FR-HST-07: A lane's cursor SHALL be bound to its stream's incarnation, the stream's creation time
  (`StreamInfo.createTime`, ISO-8601 at full precision), recorded in `history_cursor` (stream, table, part,
  incarnation) before the lane's first row from that incarnation. This is trade-processor's scheme (FR-DRS-36). A
  lane SHALL open only on the incarnation its rows came from. A lane binds to the current incarnation when it has no
  rows (a cursor at 0 skips nothing) or when its binding is `0`, ATTESTED: written only by an operator who has
  reconciled the lane's rows with the current stream, and used up by that bind (the latest binding wins; the bind wins
  a same-millisecond tie). A lane with rows and no binding SHALL refuse with `STREAM_UNBOUND <durable>`: the rows'
  stream is unknown, and binding them to whatever stream exists now would skip that stream's first messages on the
  first upgrade (codeX re-review, FR-DRS-36's first-binding rule). Its message gives the remedy: reconcile, then attest
  with the `INSERT INTO history_cursor ... '0'` it prints, or move the rows aside to rebuild. A
  lane with rows and a different incarnation SHALL refuse: no consumer is opened and no row is written, `/health`
  reports it not ready (503) with `STREAM_RESET <durable>`, an `ALERT history-feed STREAM_RESET` line names the cursor
  and both creation times, and it re-checks every 30 s. A sequence number alone cannot tell a recreated stream from
  the original. A stream deleted and recreated under the same name restarts at 1, and once it passed the cursor,
  opening at cursor + 1 would skip its first messages for good (codeX review 2026-10-30 §6). A limits update keeps
  the creation time; a delete and recreate changes it. Recovery is an operator decision: move the lane's rows aside,
  or reconcile them and attest (`0`), then restart.
- NFR-HST-01: No engine, bridge wire, stream, trade-processor, production or GKE change. The feed is a reader of
  the existing streams with its own durables; the render gains one module (`history-feed`).
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
- SC-HST-03: `HistoryFeedKillIT` (`./gradlew integrationTest`; NATS 2.14 JetStream and ClickHouse 25.8 containers):
  the shipped jar in its own JVM, partition layout P = 2, 120,000 crosses (240,000 trade sides, 360,000 order events)
  published before, between and during two SIGKILLs mid-drain; every row exactly once (rows = distinct (part, seq) =
  distinct key = published) and the fills page and `/health` answer. Met 2026-10-30. Negative controls (the test
  must be able to fail both ways): replaying from the stream start without the cursor filter FAILS it (duplicates);
  reopening 200 sequences past the cursor FAILS it (loss). Acking before the insert passes, as designed: the restart
  reads the store's cursor, not the ack floor. A plainly resumed durable also passed this kill pattern on NATS 2.14
  (FR-DRS-33 measured the loss on 2.10, the bake-off on 2.14 with an ackWait wait); the recreate stays for those
  cases and for consumer-sequence gaps.
- SC-HST-04: `scripts/history-feed/kind-smoke.sh`: the image and `k8s/` manifests on a throwaway kind cluster,
  per-partition streams (P = 2), load on the bridges' wire while the pod is force-deleted twice; every message stored
  exactly once, the four query paths and the export answer.
- SC-HST-05: `HistoryFeedIncarnationIT` (NATS 2.14 + ClickHouse 25.8 containers, P = 2): a restart on unchanged
  streams resumes and takes new load (control). After `TRADERX_FEED_P0` is deleted, recreated and refilled past the
  cursor, the restarted feed writes no row from it, `/health` is 503 with `STREAM_RESET` on both P0 lanes and not
  on P1, and P1 keeps flowing. With P1's binding removed and P0 attested (`0`), the next start writes no P1 row,
  reports `STREAM_UNBOUND` on both P1 lanes, resumes P0 on the new stream and replaces the attestation with its
  creation time; attesting P1 then makes every lane ready (200). With the check removed the test fails at the reset;
  with unbound rows accepted it fails at the unbound lane (expected 10000 rows, was 10500).
