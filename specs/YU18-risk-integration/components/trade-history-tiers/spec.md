# Trade history tiers specification

Status: proposed 2026-11-01; first slice implemented 2026-11-06, local and kind only (FR-THT-03 policy, FR-THT-05
inventory and the recon move, FR-THT-06 gate check, the FR-THT-04 object-storage question); FR-THT-02's render and
rig steps 2026-11-07, local and kind only. Nothing in production. Decision: [ADR-102](../../system/adr-102-trade-history-off-mariadb.md).
Dependencies: history-store FR-HST-01..07 (`history-feed`, ClickHouse 25.8), downstream-read-side (trade-processor,
FR-DRS-33/34/36), production rig FR-PRG-27 (restore gate), KDB-X Community (licence installed by yaakov only).

## Requirements

- FR-THT-01: MariaDB SHALL hold current state only (positions, open orders, accounts, reference data, EOD prices,
  and each account's blotter window with the settlement counts, ADR-097's snapshot store). trade-processor SHALL run
  the snapshot read model only: every trade side applied to `positions` exactly once, no SQL `trades` row per side
  (rows mode retired: implemented 2026-11-06; the trade feed refuses any other mode). Nothing SHALL read SQL `trades` as history (decided 2026-11-06,
  option A: the window is current state; moving it to ClickHouse would put history-feed lag and availability on the
  blotter for no growth bound the window does not already have).
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
  nothing pending. Mandatory since FR-THT-02 renders the store (2026-11-07): no `sts/clickhouse` is a refusal. The sums are
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
