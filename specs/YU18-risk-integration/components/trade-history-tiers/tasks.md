# Trade history tiers tasks

- [x] Render `history-feed` + ClickHouse for GKE; `rig.sh up`/`down` and readiness include them (FR-THT-02).
  2026-11-07, local and kind (`kind-history-store.sh` PASS on both renders, G3h controls refused). Left: the GCS
  bucket + HMAC key, a production build and admission with history-feed, and the GKE bring-up (yaakov's yes).
- [x] ClickHouse storage policy: hot SSD days, older partitions on a GCS disk; same rows before and after (FR-THT-03, SC-THT-03).
  Local 2026-11-06 (`HistoryTierIT`, S3-protocol disk). GKE: a GCS bucket + HMAC key and the manifests, with FR-THT-02.
- [ ] Production run writing both SQL `trades` and ClickHouse; per-partition count and hash equal.
- [x] Inventory test of every SQL `trades` reader (FR-THT-05): `sql-trades-readers.sh`, 58 recorded 2026-11-06.
- [x] Move the FR-DRS-33 recon to the history store (`order-fill-recon.clickhouse.sql`, real-ClickHouse test).
- [x] Port the recon proofs to the ledger recon (yaakov, 2026-11-06): `recon-ledger-subject.py` plants a field
  mismatch on the capture stream (trade-processor at 0) and an orphan as one window row, each restored in `finally`;
  `yu05-recon.sh` PASS on kind traderx-tht, both controls named, both negative mutants refused. The SQL-row helper
  `recon-proof-subject.py` (managed epoch-v1 runs only) and its offline tests removed.
- [~] Verify the WINDOW readers on kind (2026-11-06, traderx-tht, production's read-model shape): PASS
  yu18-projection-effect-end, yu05-settlement, yu05-auth-entitlements, yu13-clordid-suppression (negative: snapshots
  off, it fails at the window read). FR-THT-01 at the effect end: TRADE_PROJECTION_MODE unset starts snapshot, `rows`
  refuses to start. Left: 18 WINDOW readers whose rig needs services the kind cluster tier lacks (inventory says
  which). Fixed on the way: yu05-common/yu13/yu16 hard-coded the dev DB password; they use the container's own.
- [x] Cut gate distinguishes the window from history (FR-THT-05): `KEEP:` inventory lines (window writer, blotter,
  epoch-namespace guard, schema) stay; `sql-trades-readers.sh` passes once only they remain. The 50 others are tagged
  (2026-11-06): WINDOW 16 (own fresh trades; verify on kind), ACCOUNTING 2 (high-water guard -> `projection_accounting`),
  RI06 16 (projection recovery / managed runs), ROWS 16 (rows-mode writer and features, deleted with it).
- [x] Restore gate checks the history store per partition (FR-THT-06, SC-THT-04 with delete/duplicate mutations): G3h,
  real-ClickHouse check script + offline gate tests. Mandatory since FR-THT-02 (2026-11-07).
- [x] Retire rows mode (FR-THT-01, 2026-11-06): trade-processor's trade feed is jetstream + snapshot only (the
  default; anything else refuses to start, FeedBarrierTest), the core and jetstream-rows feed paths, run transitions
  and RI06 recovery are gone, `POST /tradeservice/order` answers 410, the rig's rows G3 and `snapshot-base` are gone
  (an absent base is 0). Kept: settlement on the window (YU05's sweep, current state) and the unreachable row writer
  for YU05/YU16's inherited tests (`ponytail:` note in TradeService). Inventory 40: KEEP 13, WINDOW 22, ROWS-PROOF 4,
  ROWS 1.
- [x] Production findings 2026-11-07 (coordinator): every render names `TRADE_PROJECTION_MODE=snapshot`
  (`test_prepare`, refused when absent); a fresh epoch's `up` deletes its empty capture streams and refuses a
  non-empty one (`test_rig`, A/B; `feed_streams`/`nats_api` exercised on a real NATS 2.14). FR-PRG-29, ADR-101.
  The smoke reads its cancel from trade-processor's order read model, not SQL `orderbook` (FR-PRG-04, `test_rig`:
  CANCELED passes; still NEW, another order or absent refuses; no orderbook query; the bearer stays off argv).
- [ ] KDB-X feed lane: exactly-once write-down, account-sorted `p#` HDB, heap-guarded paged q (FR-THT-04).
- [x] Check KDB-X Community object storage on `gs://`; record the answer in ADR-102 (yes, with `objstor` init).
- [ ] q gateway over several HDB processes; `par.txt` over several disks.
- [ ] 1.5B trade sides scale proof (SC-THT-01, SC-THT-02); rebuild-from-cursor-0 proof (FR-THT-07).
- [ ] FR-THT-08: the fresh-epoch archive moves the history tables too.
