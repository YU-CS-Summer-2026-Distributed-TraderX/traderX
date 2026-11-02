# Trade history tiers specification

Status: proposed 2026-11-01, not implemented. Decision: [ADR-102](../../system/adr-102-trade-history-off-mariadb.md).
Dependencies: history-store FR-HST-01..07 (`history-feed`, ClickHouse 25.8), downstream-read-side (trade-processor,
FR-DRS-33/34/36), production rig FR-PRG-27 (restore gate), KDB-X Community (licence installed by yaakov only).

## Requirements

- FR-THT-01: MariaDB SHALL hold current state only (positions, open orders, accounts, reference data, EOD prices).
  trade-processor SHALL apply every trade side to `positions` exactly once and SHALL NOT insert it into SQL `trades`
  once FR-THT-05 holds.
- FR-THT-02: ClickHouse (`history-feed`) SHALL be the trade history's system of record on GKE: every trade side and
  order event exactly once (FR-HST-03), on the per-partition streams (FR-DRS-34), rendered into the production
  manifests and started by `rig.sh up`. Its health SHALL be part of the rig's readiness.
- FR-THT-03: ClickHouse SHALL keep the newest `HISTORY_HOT_DAYS` date partitions on local SSD and move older ones to
  a GCS-backed disk by a TTL storage policy; queries SHALL return the same rows before and after a move.
- FR-THT-04: A KDB-X analytics lane SHALL consume the same capture streams exactly once (the store is the cursor,
  FR-HST-03), keep today in memory, write each day down to a date-partitioned HDB sorted by account with `p#`, and
  serve read-only q over HTTP (the console Kdb tab's `POST /q`, FR-DA10). No query SHALL be able to hold the heap
  past the licence cap: results are paged and every call runs under a heap guard. It SHALL scale by adding HDB
  processes over date ranges behind one q gateway and by `par.txt` segments over several disks.
- FR-THT-05: Every reader of SQL `trades` SHALL read the history store instead before `trades` stops being written:
  blotter and trade history, recon (FR-DRS-33), the restore gate's fingerprint (FR-PRG-27), EOD, and every proof
  that counts SQL trades. The inventory SHALL be a test that fails while any reader remains.
- FR-THT-06: The restore gate SHALL verify the history store per partition (row count and an order-sensitive hash
  over `(part, seq)`) against the members' booked sides, in place of the SQL fingerprint over `trades`.
- FR-THT-07: Either store SHALL be rebuildable from the capture streams from its cursor 0, with the result equal to
  the store it replaces (count and hash, FR-THT-06).

## Success criteria

- SC-THT-01: At 400K orders/s for 10 minutes on GKE, MariaDB's write rate is positions and order state only, and
  ClickHouse holds every trade side exactly once (FR-HST check).
- SC-THT-02: 1.5 billion trade sides stored; one account's page of trades in under 1 s cold from ClickHouse and from
  KDB-X; a quarter's VWAP by symbol in KDB-X in under 1 s; no `'stop` (heap cap) during the query set.
- SC-THT-03: A date partition moved to GCS answers the same query with the same rows.
- SC-THT-04: The restore gate passes on a clean shutdown and refuses a store with one deleted row and one duplicated row.

## Open questions

- Whether KDB-X Community includes object storage (`gs://` HDB segments). If not, cold KDB-X dates stay on persistent disks.
- `HISTORY_HOT_DAYS` and the GCS bucket's region and class (us-east1 Standard, same as the TAQ buckets).
