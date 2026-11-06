# Trade history tiers specification

Status: proposed 2026-11-01; first slice implemented 2026-11-06, local and kind only (FR-THT-03 policy, FR-THT-05
inventory and the recon move, FR-THT-06 gate check, the FR-THT-04 object-storage question). Nothing in production. Decision: [ADR-102](../../system/adr-102-trade-history-off-mariadb.md).
Dependencies: history-store FR-HST-01..07 (`history-feed`, ClickHouse 25.8), downstream-read-side (trade-processor,
FR-DRS-33/34/36), production rig FR-PRG-27 (restore gate), KDB-X Community (licence installed by yaakov only).

## Requirements

- FR-THT-01: MariaDB SHALL hold current state only (positions, open orders, accounts, reference data, EOD prices,
  and each account's blotter window with the settlement counts, ADR-097's snapshot store). trade-processor SHALL run
  the snapshot read model only: every trade side applied to `positions` exactly once, no SQL `trades` row per side
  (rows mode retired) once FR-THT-05's gate passes. Nothing SHALL read SQL `trades` as history (decided 2026-11-06,
  option A: the window is current state; moving it to ClickHouse would put history-feed lag and availability on the
  blotter for no growth bound the window does not already have).
- FR-THT-02: ClickHouse (`history-feed`) SHALL be the trade history's system of record on GKE: every trade side and
  order event exactly once (FR-HST-03), on the per-partition streams (FR-DRS-34), rendered into the production
  manifests and started by `rig.sh up`. Its health SHALL be part of the rig's readiness.
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
  Implemented: `scripts/trade-history-tiers/sql-trades-readers.sh` over tracked files; no argument = the drop gate
  (exit 1 while any reader remains, 58 on 2026-11-06), `--inventory` = the tree equals
  `sql-trades-readers.txt` exactly (a new reader or a stale entry fails), `--selftest`. Moved: the FR-DRS-33
  order/fill recon (`order-fill-recon.clickhouse.sql`), which on the history store works in snapshot mode and at
  any volume.
- FR-THT-06: The restore gate SHALL verify the history store per partition (row count and an order-sensitive hash
  over `(part, seq)`) against the members' booked sides, in place of the SQL fingerprint over `trades`.
  Implemented as `rig.sh` G3h, after G3: per partition count, max, wrapped sums of `trade_seq` and `trade_seq²`
  (ClickHouse's UInt64 arithmetic wraps 2^64 like the closed form) and duplicates, against the closed form over
  sides 1..t minus `rig-unprojected` (no base: the store is fed from cursor 0). Waits for every lane open with
  nothing pending. Opt-in while no `sts/clickhouse` exists; mandatory once FR-THT-02 renders the store. The sums are
  order-insensitive: the order is the stream's, held by FR-HST-03's cursor, so a set check is the exact one.
- FR-THT-08 (proposed 2026-11-06): A fresh epoch (ADR-101) SHALL archive the history tables with the read model's
  epoch tables (`arc_<label>_trades`, `_orders`, `_history_cursor`), because trade and order refs restart at 1 and
  G3h and the recon read one numbering. Until then a fresh epoch over a non-empty store is refused by G3h.
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

## Open questions

- Resolved 2026-11-06, by a real read: KDB-X Community includes object storage. A `par.txt` naming a `gs://`
  segment serves every row identically to the local copy (80,000 rows, 4 dates; a mutated object is detected).
  It needs `use`kx.objstor` and its `init[]` first, `GCLOUD_PROJECT_ID`, and gcloud credentials. Without `init` the
  `gs://` dates are absent with no error, so an HDB guard must count dates, never trust a clean load.
- `HISTORY_HOT_DAYS` and the GCS bucket's region and class (us-east1 Standard, same as the TAQ buckets).
