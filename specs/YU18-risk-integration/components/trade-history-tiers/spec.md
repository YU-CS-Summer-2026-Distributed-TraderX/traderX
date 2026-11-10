# Trade history tiers specification

Status: proposed 2026-11-01; first slice implemented 2026-11-06, local and kind only (FR-THT-03 policy, FR-THT-05
inventory and the recon move, FR-THT-06 gate check, the FR-THT-04 object-storage question); FR-THT-02's render and
rig steps 2026-11-07, local and kind only; the cutover (FR-THT-01 revised, FR-THT-09) 2026-11-08, kind only. Nothing in production. Decision: [ADR-102](../../system/adr-102-trade-history-off-mariadb.md).
Dependencies: history-store FR-HST-01..07 (`history-feed`, ClickHouse 25.8), downstream-read-side (trade-processor,
FR-DRS-33/34/36), production rig FR-PRG-27 (restore gate), KDB-X Community (licence installed by yaakov only).

## Requirements

- FR-THT-01: MariaDB SHALL hold current state only (positions, open orders, accounts, reference data, EOD prices,
  the settlement counts) and trade **exceptions**: no booked trade side SHALL be written to SQL `trades` (ADR-102
  cutover, decided by yaakov 2026-11-08, superseding option A of 2026-11-06, under which the table kept each account's
  blotter window). SQL `trades` holds only a side booking refused (`Rejected`) and an operator-forced settlement
  (`Settled`). trade-processor SHALL run the snapshot read model only: every trade side applied to `positions` exactly
  once (rows mode retired 2026-11-06; the trade feed refuses any other mode). Implemented 2026-11-08 (kind):
  `TradeView` keeps no blotter ring and its cut carries only the refused sides; `TradeSnapshotStore` writes them
  `INSERT IGNORE` and never deletes; `TRADE_PROJECTION_BLOTTER_DEPTH` is removed. The settlement sweep still runs and
  finds nothing new to settle (pre-cutover `Processing` rows only).
  Production cutover (found 2026-11-07): the CompSci render without `--read-model snapshot` never set
  `TRADE_PROJECTION_MODE`, so production ran trade-processor's default: rows until 9a2b5b90, snapshot from it. The
  889e47be build was production's first on the snapshot read model, unannounced. Since 2026-11-07 every render names
  `TRADE_PROJECTION_MODE=snapshot` and `validate` refuses a render that leaves it to the default. The default render
  is the snapshot read model at one partition with the memory-only order view and its 5 s snapshot
  (`ORDER_FEED_PERSIST=false`, `ORDER_VIEW_SNAPSHOT_MS=5000`), as production e1107 ran it (FR-PRG-22).
- FR-THT-02: ClickHouse (`history-feed`) SHALL be the trade history's system of record on GKE: every trade side and
  order event exactly once (FR-HST-03), on the per-partition streams (FR-DRS-34), rendered into the production
  manifests and started by `rig.sh up`. Its health SHALL be part of the rig's readiness.
  Implemented 2026-11-07 (local and kind): `prepare.py` `history_store` renders `history-feed/k8s/` on `history-pool`
  (c4d-standard-8, taint `traderx-history`): ClickHouse 4 CPU / 16Gi guaranteed on a 200Gi claim of class
  `clickhouse-hot` (Hyperdisk Balanced, Retain), history-feed 2 CPU / 4Gi, one `Recreate` replica, its feed
  variables copied from trade-processor; a NetworkPolicy admits only history-feed and trade-processor to port 8123.
  `--history-hot-days N` adds FR-THT-03's `tiered` policy, its cold disk on `gs://<project>-traderx-history/<ns>/`
  (Secret `history-cold-hmac`). `validate` refuses an absent, unsized, unclaimed or surging store, a writer on other
  streams, and hot days without the cold disk; `pin_images` refuses an image with no locked digest (ClickHouse 25.8,
  amd64 child `sha256:4ff0d6c1…` in `public-image-lock.json`). history-feed is a private image (`build-images.py`,
  `measure-admission.py`; admission counts 13+E private, 10 upstream, 26 slots; the build-context set is
  `build-images.py`'s `COMPONENTS`, read by `admit.py`, since a literal 11 refused the 43006beb candidate, ADR-096 §6). `rig.sh pools` creates
  `history-pool`, `up` brings it to 1 node and waits for `sts/clickhouse` with the support workloads, `down` takes
  it to 0. Not done: the GCS bucket and HMAC key (cloud provisioning), and a GKE bring-up.
- FR-THT-03: ClickHouse SHALL keep the newest `HISTORY_HOT_DAYS` date partitions on local SSD and move older ones to
  a GCS-backed disk by a TTL storage policy; queries SHALL return the same rows before and after a move.
  Implemented: `HISTORY_HOT_DAYS` (0 = off) puts both tables on the `tiered` policy of
  `history-feed/k8s/clickhouse-tiered.xml` (volume `default` = local disk, volume `cold` = an S3-protocol disk:
  GCS's XML API with an HMAC key on GKE) with `TTL toDate(ts) + n days TO VOLUME 'cold'`; an existing table moves
  onto it once. The hot volume must be named `default`: ClickHouse refuses a policy change that drops the old
  policy's volume (found by `HistoryTierIT`).
- FR-THT-04: A KDB-X analytics lane SHALL consume the same capture streams exactly once (the store is the cursor,
  FR-HST-03), keep today in memory, write each day down to a date-partitioned HDB sorted by account with `p#`, and
  serve read-only q over HTTP (the console Kdb tab's `POST /q`, FR-DA10). No query SHALL be able to hold the heap
  past the licence cap: results are paged and every call runs under a heap guard. It SHALL scale by adding HDB
  processes over date ranges behind one q gateway and by `par.txt` segments over several disks.
- FR-THT-05: Every reader of SQL `trades` SHALL read the history store instead before `trades` stops being written:
  blotter and trade history, recon (FR-DRS-33), the restore gate's fingerprint (FR-PRG-27), EOD, and every proof
  that counts SQL trades. The inventory SHALL be a test that fails while any reader remains.
  Implemented: `scripts/trade-history-tiers/sql-trades-readers.sh` over tracked files (`KEEP:` and, since the
  2026-11-08 cutover, `HISTORICAL:` lines are not counted: KEEP reads or writes exception rows or the schema,
  HISTORICAL is a dated one-off evidence script bound to a cluster that no longer exists). It matches SQL text only:
  JPA lookups (`TradeRepository.findById`) are invisible to it, and the 2026-11-08 cutover found three by hand
  (TCA, forced settlement, the rows-mode recon); no argument = the drop gate
  (exit 1 while any reader remains, 58 on 2026-11-06), `--inventory` = the tree equals
  `sql-trades-readers.txt` exactly (a new reader or a stale entry fails), `--selftest`. Moved: the FR-DRS-33
  order/fill recon (`order-fill-recon.clickhouse.sql`), which on the history store works in snapshot mode and at
  any volume.
- FR-THT-06: The restore gate SHALL verify the history store per partition (row count and an order-sensitive hash
  over `(part, seq)`) against the members' booked sides, in place of the SQL fingerprint over `trades`.
  Implemented as `rig.sh` G3h, after G3: per partition count, max, wrapped sums of `trade_seq` and `trade_seq²`
  (ClickHouse's UInt64 arithmetic wraps 2^64 like the closed form) and duplicates, against the closed form over
  sides 1..t minus `rig-unprojected` (no base: the store is fed from cursor 0). Waits for every lane open with
  nothing pending, then refuses unless history-feed's `unowned` is `{}` (FR-HST-02): nothing pending is also true of a
  lane whose filter matches nothing, and on production e1107b (2026-11-07) G3h passed that way on an empty epoch while
  the store held 0 of the next 6 sides. A health without `unowned` (an older image) refuses too.
  At volume the check SHALL run one query per partition (2026-11-10): `SELECT DISTINCT part`, then the same check row
  for `WHERE part = p`, so `uniqExact`'s set is one partition's and never the table's. The one-query form (`GROUP BY
  part` over the table) exceeded ClickHouse's 21 GiB query memory at 1.4B rows, and the 2026-11-09/10 hours at 400K and
  464K orders/s were checked by hand (count, bounds, the two wrapped sums) in its place; a 1.0B-row store answered the
  per-partition form in seconds. Each query has 600 s. Covered by
  `test_rig.Up#test_the_history_check_is_one_query_per_partition`, which fails on the one-query shape.
  Mandatory since FR-THT-02 renders the store (2026-11-07): no `sts/clickhouse` is a refusal. The sums are
  order-insensitive: the order is the stream's, held by FR-HST-03's cursor, so a set check is the exact one.
- FR-THT-08: A fresh epoch (ADR-101) SHALL archive the history tables with the read model's epoch tables
  (`arc_<label>_trades`, `_orders`, `_history_cursor`), because trade and order refs restart at 1 and G3h and the
  recon read one numbering. `rig.sh up`'s `archive_read_model` renames them with history-feed stopped, before the
  capture streams are deleted, and history-feed recreates them empty on start. Each moves once (a retry skips it);
  a live table found beside its archive is an earlier attempt's and SHALL be empty, else the archive is refused, and
  its `history_cursor` is emptied because the retry deletes the stream incarnation it binds (2026-11-08, e1108:
  without the move history-feed refused `STREAM_RESET` on the old epoch's cursor, and the tables were renamed by
  hand).
- FR-THT-09: The blotter SHALL read the history store (ADR-102 cutover, 2026-11-08). position-service's
  `GET /trades/{acct}`, `/trades/` and `/v2/projections/{scope}/accounts/{a}/trades` SHALL return the active
  scope's newest history rows (`BLOTTER_LIMIT`, 100; the unfiltered list the newest date partition only), each with
  its exact trade id and source order id (FR-HST-01's `id_prefix`, `order_epoch`), its state by trade-processor's T+N
  rule (`SETTLEMENT_T_PLUS_DAYS`, which `validate` requires equal on both), and an SQL exception row (FR-THT-01) in
  place of the history row with the same id. Another scope SHALL answer the SQL rows it holds. `clickhouse-clients`
  SHALL admit position-service. A forced settlement of a side with no SQL row SHALL read it from the store, check the
  caller's entitlement on its account, and record a `Settled` exception row; an id the store does not hold is 404.
  Implemented 2026-11-08: `TradeRepository` (position-service, `TradeRepositoryTest`), `HistoryStore` and
  `SettlementService.forceSettle` / `SettlementController` (trade-processor, `SettlementServiceTest`), prepare.py
  `HISTORY_CLIENTS` and `validate_history_store` (`test_prepare`). Proven on kind `traderx-cut`
  (`kind-cutover-check.sh`, yu18-projection-effect-end, a live forced settlement); not on GKE or production.
- FR-THT-10: The history feed SHALL keep up with production load (2026-11-08). A lane's consumer SHALL be granted an
  unacked window of at least one batch (`maxAckPending` 2 x `HISTORY_BATCH_ROWS` + `HISTORY_FETCH`), read back from
  the server, and a lane granted less SHALL NOT start. Production e1108b, 2026-11-08 (`tip-1ef5e86e-j21`): the lanes
  asked for -1, NATS 2.14 stored its default 1000, and the ack waits for the INSERT, so each lane moved 1000 rows per
  500 ms flush, about 1,500 rows/s. A 12-minute load of 9,657,584 trades left 3.07M in the store and 16.2M order
  events queued, and G3h timed out. G3h's wait SHALL be 600 s plus the lanes' backlog at `HISTORY_DRAIN_FLOOR` rows/s
  (20,000, the production order rate): a backlog gets time in proportion, and a feed slower than the floor still
  refuses. Measured locally (NATS 2.14, ClickHouse 25.8, the feed on 2 CPUs, P=1, production defaults): 1,450
  rows/s per lane before; a 5M-message backlog drained in about 13 s after (about 200K rows/s per lane, 1M in 2.9 s
  in `HistoryFeedRateIT`). One partition is about 10x the 20K orders/s rate (about 35K messages/s). The bench's 400K
  orders/s (about 700K messages/s) needs the partitioned layout (P=4, 730K rows/s in the bake-off), not yet in the
  production render. Tests: `HistoryFeedRateIT` (fails with the old consumer, times out at 60 s),
  `test_rig.Up.test_the_history_wait_scales_with_the_backlog`.
  At 400K orders/s (about 1M feed messages/s) the render SHALL be the partitioned one (`--read-model snapshot
  --partitions 8`, FR-DRS-34's per-partition streams, so trade-processor, the order view and history-feed's 16 lanes
  each read one partition's stream; one stream acks about 240-280K messages/s, bake-off). That render SHALL size
  history-feed at 8 CPU / 8Gi and ClickHouse at 6 CPU / 24Gi with a 500Gi claim (`HISTORY_*_FULL_RATE`), on one
  c4d-standard-16 (`HISTORY_MACHINE`); one partition keeps 2 CPU, 4 CPU and 200Gi. The basis, measured 2026-11-08: the
  feed's process costs about 170K rows per CPU-second at P=8 (jnats about 320K messages, the parser over 1M), so about 6
  cores at 1M/s; the bake-off store took 1.5-3 cores draining 730K rows/s. The laptop could not measure the store at
  1M/s (fsynced inserts on Docker Desktop's disk took about 300 ms per 7K rows), so the store's rate at 1M/s is the 400K
  campaign's measurement, not yet this requirement's. Test: `test_prepare.HistoryStore.test_the_store_and_its_writer_are_sized_by_the_partitions`.
  On GKE (2026-11-08/09, namespace `pa-proof-400k` on traderx-demo, own pools, torn down; candidate `p4k-da9c3030`, 8
  partitions, 18 gateways, the store at the full-rate sizes) 400K orders/s was NOT reached, and the limit was NATS, not
  the feed or the store (RI34):
  - PASS at a flat 67K orders/s (202K feed messages/s) for 10 minutes: members = read model = store = 40,252,682
    sides, stream 120,758,046 messages = store rows, G3 and G3h ok, history lag about 1 s, caught up 6 s after the load.
  - At 100K orders/s (285K messages/s) NATS dropped 1,021,951 sides at stream ingest; at 147K orders/s, 31.2M.
  - history-feed and ClickHouse stayed under 1 CPU each while NATS starved them, then drained an 83M backlog at 640K
    rows/s (peak 813K rows/s). The store equalled the stream exactly in every run.
  - The fill feed was off for these runs (18 gateways x 8 partitions of consumers otherwise), so they measure the
    capture and history path, not the full production configuration.
  Proven on kind `traderx-rate` (P=1, production defaults; `kind-history-rate.sh`, 2026-11-08 9:23-9:51 pm ET):
  - the load: binary-load.mjs, 1,787,296 orders, with history-feed held at 0
  - the pre-fix image: 2,983 rows/s
  - this image: the 4,517,458-message backlog drained in 39 s (114K rows/s, G3h's wait 825 s)
  - G3h: sides 1..1,787,296, each once
  - stream 5,361,888 messages = store 1,787,296 trades + 3,574,592 orders; members 1,787,296 (since FR-DRS-41 a
    message holds one or more records, so the script compares the store with the bridges' published records and the
    stream with their published messages, and checks the orders table by event, not by stream sequence)
  - an earlier run refused at G3h with 952 sides absent from the stream itself, lost when kind's 768Mi NATS was OOM-killed
    under the members' core publishes. That is not the feed.
- FR-THT-11: The history store SHALL hold each trade side and each order event once, however many times the capture
  stream holds it (2026-11-09, RI34 section 2). The bridges republish at a promotion and at a new NATS connection
  (FR-DRS-37/38): at least once. On GKE `pa-proof-400k` 1,104,397 trade sides were in the stream twice and the store
  held both, because it stored one row per stream message.
  - A lane's positions only rise: a trade side's `tradeSeq`, an order event's (`consensusSequence`, `outputOrdinal`),
    the bridges' own catch-up position. The lane keeps the highest position it has stored, read from the store at
    every open. A batch whose every row is above it takes the plain INSERT, as before: the write path does not change
    until the stream goes back over a position.
  - A batch with a row at or below it SHALL be written by one INSERT that leaves out a row when the lane's rows in the
    store, or an earlier row of the batch, already hold a row equal in everything the engine produced: every column
    but `seq`, and for a trade side `ts` (the bridge's publish time, which a republish changes). The first stored
    wins. The lookup is bounded by the batch's `trade_seq` range (trades) or its `ts` range (orders, whose `ts` is the
    engine's and does not change). The lane does not decide which rows are repeats: the store answers, so a record
    that arrives late and was never stored is kept.
  - The same id with different content is not a repeat. It SHALL be stored, and G3h's duplicate count refuses the
    store.
  - An order event with no position SHALL never be left out and never causes the lookup. That is every order event
    under the legacy id scheme (no run descriptor), which the production render and the kind rig run today: their
    order events carry no `consensusSequence` or `outputOrdinal`, so nothing tells a repeat from a second identical
    event. The bridges do not republish those either (their catch-up needs the same position, FR-DRS-37). Order
    events are held once only under epoch-v1 ids. Trade sides are held once under both schemes: a legacy side still
    has its `tradeSeq`. From FR-DRS-40 the members' bridge writes the position on legacy order events too, so they
    are held once like the rest; an order event with no position is then one a member build before FR-DRS-40 wrote.
  - No reader changes. The tables are plain MergeTree and hold each side once at every moment, so G3h, the blotter,
    TCA, the export and the recon count each side once with no `FINAL` and no wait for a merge.
  - The cursor stays max(`seq`) (FR-HST-03). It can sit behind repeats at the stream's tail; a restart reads them
    again and leaves them out again. When caught up, the store's rows are the stream's messages less the repeats.
  - `/health` reports `duplicates` per lane: messages left out since the process started.
  - Positioned order events get the same treatment as trade sides: the same catch-up republishes them, and the
    export and any sum over order events would count a repeat twice.
  - Cost, measured locally 2026-11-09 (ClickHouse 25.8 on Docker Desktop, the laptop at load average 16):
    `HistoryFeedRateIT`'s 1M rows took 4.9 s as built, 5.1 s with the check forced off and 6.1 s with every batch
    forced through the leaving-out INSERT. On a 40M-side store with 5,000 accounts the lookup took 4-8 ms on unmerged
    parts and 70-120 ms where the range fell inside one merged 40M-row part (the `trade_seq` minmax index prunes
    whole parts there, not granules). Not measured on GKE or on a cold (GCS) partition.
  - Tests: `HistoryFeedDuplicateIT` (NATS 2.14 + ClickHouse 25.8, the shipped jar, P=2): 35,000 repeated messages,
    of rows stored before a SIGKILL (the sides' publish time a day later), back to back in one batch, and at the
    stream's tail read again by a third process; a late cross is kept; a side with another quantity under a stored id
    is stored; two identical order events with no position are both stored. With the leaving-out INSERT disabled the
    test fails.
  - Proven on kind `traderx-dup` (`kind-history-duplicates.sh`, 2026-11-09 9:36-9:38 am ET; production's default
    shape and its legacy ids; history-feed from this change, the other images 2026-11-08's): 290,806 sides booked;
    4,057 stored sides read back off the stream and published again, 2,030 with history-feed running and 2,027 across
    its restart; history-feed left out 2,030, then 4,057 after the restart (it reads the first 2,030 again); the
    store held 290,806 trades throughout; G3h: sides 1..290,806, each once. The stream also held the repeats of three
    earlier runs of the script. The 4,016 order events republished were unpositioned and were stored, as above.
- FR-THT-07: Either store SHALL be rebuildable from the capture streams from its cursor 0, with the result equal to
  the store it replaces (count and hash, FR-THT-06).

## Success criteria

- SC-THT-01: At 400K orders/s for 10 minutes on GKE, MariaDB's write rate is positions and order state only, and
  ClickHouse holds every trade side exactly once (FR-HST check).
- SC-THT-02: 1.5 billion trade sides stored; one account's page of trades in under 1 s cold from ClickHouse and from
  KDB-X; a quarter's VWAP by symbol in KDB-X in under 1 s; no `'stop` (heap cap) during the query set.
- SC-THT-03: A date partition moved to GCS answers the same query with the same rows.
- SC-THT-04: The restore gate passes on a clean shutdown and refuses a store with one deleted row and one duplicated row.

## Local evidence (2026-11-06)

- SC-THT-03 (local): `HistoryTierIT` (ClickHouse 25.8 + S3Mock): three days on local disk, put on the policy with
  hot days 1; the two old partitions move to the object disk, today's stays; counts, the ordered whole-table hash, a
  fills page and a day's export are equal before and after; a second start issues no mutation. Controls: without the
  TTL ALTER it fails (nothing moves); with the objects deleted the answers differ.
- SC-THT-04 (local): `scripts/trade-history-tiers/g3h-clickhouse-check.sh`: rig.sh's own SQL and verdict on a real
  ClickHouse, 200,000 sides numbered past 4e9 (sums of squares wrap): clean OK; one deleted, one duplicated, and one
  deleted plus one duplicated (count unchanged) each refused. Offline: `test_rig.py` history cases, which fail with
  the gate call removed.
- FR-THT-04 object storage: `scripts/trade-history-tiers/kdb-objstor-check.sh` (KDB-X Community, real `gs://`).

## Kind evidence (2026-11-07, traderx-tht2)

`scripts/trade-history-tiers/kind-history-store.sh` on `kind-yu18-rig.py --history` (prepare.py's `history_store`,
minus the GKE pool, class and sizes), images built at this branch, 4 trade sides crossed by
`yu18-projection-effect-end` (PASS twice). rig.sh's own `g3_history`: PASS on the store as fed; REFUSED with side 4
deleted ("holds 3 sides") and with side 4 twice ("1 duplicated"); PASS restored. Untiered render: no `tiered` policy
("Unknown storage policy"), the tier's negative control. Re-applied with `--history-hot-days 1` (cold disk on an
S3Mock): G3h PASS again after ClickHouse's restart, `trades` on `tiered`, a probe table's 5-day-old partition moved
to `cold_object` alone, rows equal before and after, 26 objects in S3. kindnet does not enforce the NetworkPolicy:
rendered, not proven.

## GKE evidence (2026-11-07, traderx-demo, disposable namespace pa-proof-tht; yaakov: "Yes, run on GKE")

Pools beside production's (untouched, all at 0): `tht-pool` 1× e2-standard-8 (trading tier, `kind-yu18-rig.py
--registry --pool`), `tht-history-pool` 1× c4d-standard-8 (prepare.py's history shape: ClickHouse 4 CPU / 16Gi on a
200Gi Hyperdisk Balanced claim, history-feed 2 CPU / 4Gi), `tht-system-pool` 1× e2-standard-2 untainted (with
production's default-pool at 0, kube-dns and konnectivity had no node: pods could not resolve names, and logs, exec and
port-forward failed with "No agent available"). Images `tht-1a8ce4f7` (amd64), ClickHouse by its locked digest.
Isolation before any trade: no live object in the namespace names `.traderx.svc`, and every TCP and UDP peer of every
pod (members' Aeron included) is a pod or Service of `pa-proof-tht` (0 of 29 `traderx` IPs). 4 sides crossed
(`yu18-projection-effect-end` PASS twice). `kind-history-store.sh`, untiered: G3h PASS; side deleted and side doubled
REFUSED; PASS restored; no `tiered` policy. Tiered (`--history-hot-days 1`, cold disk on
`gs://project-compsci-484414-tht-objstor-test/pa-proof-tht/` through a bucket-scoped service account's HMAC key in
Secret `history-cold-hmac`): G3h PASS again, `trades` on `tiered`, a probe partition 5 days old moved to `cold_object`
alone with equal rows, 26 objects on GCS. Teardown: namespace, its 5 volumes and their disks, the StorageClass, the three
pools, the HMAC key, the bucket binding, the service account and the prefix deleted; `gcloud compute instances list`
shows no tht instance. Not proven: the NetworkPolicy (traderx-demo enforces none: no Dataplane V2, network policy off).
Gap found: account 42422's first admission answered 200 but reached no member; a resend applied (not diagnosed).

## Open questions

- Resolved 2026-11-06, by a real read: KDB-X Community includes object storage. A `par.txt` naming a `gs://`
  segment serves every row identically to the local copy (80,000 rows, 4 dates; a mutated object is detected).
  It needs `use`kx.objstor` and its `init[]` first, `GCLOUD_PROJECT_ID`, and gcloud credentials. Without `init` the
  `gs://` dates are absent with no error, so an HDB guard must count dates, never trust a clean load.
- `HISTORY_HOT_DAYS` and the GCS bucket's region and class (us-east1 Standard, same as the TAQ buckets).
- RI06 projection recovery (explicit and automatic catch-up from a peer, managed-run transitions, the console's
  operator catch-up) books recovered trades as SQL rows (`TradeService.processRecoveredTrades`); snapshot mode has no
  equivalent but its own stream replay from the cursor. Retiring rows mode retires or ports it: yaakov to decide.
