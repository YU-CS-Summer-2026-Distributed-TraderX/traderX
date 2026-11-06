# Trade history tiers tasks

- [ ] Render `history-feed` + ClickHouse for GKE; `rig.sh up`/`down` and readiness include them (FR-THT-02).
- [x] ClickHouse storage policy: hot SSD days, older partitions on a GCS disk; same rows before and after (FR-THT-03, SC-THT-03).
  Local 2026-11-06 (`HistoryTierIT`, S3-protocol disk). GKE: a GCS bucket + HMAC key and the manifests, with FR-THT-02.
- [ ] Production run writing both SQL `trades` and ClickHouse; per-partition count and hash equal.
- [x] Inventory test of every SQL `trades` reader (FR-THT-05): `sql-trades-readers.sh`, 58 recorded 2026-11-06.
- [x] Move the FR-DRS-33 recon to the history store (`order-fill-recon.clickhouse.sql`, real-ClickHouse test).
- [ ] Move blotter/history (position-service `TradeRepository`), the managed-run proofs and the SQL-counting proofs;
  the blotter stays on the window (FR-THT-01 option A, 2026-11-06); the proofs need a history store on kind.
- [x] Cut gate distinguishes the window from history (FR-THT-05): `KEEP:` inventory lines (window writer, blotter,
  epoch-namespace guard, schema) stay; `sql-trades-readers.sh` passes once only they remain. 50 history readers left.
- [x] Restore gate checks the history store per partition (FR-THT-06, SC-THT-04 with delete/duplicate mutations): G3h,
  real-ClickHouse check script + offline gate tests. Opt-in until FR-THT-02.
- [ ] Retire rows mode (FR-THT-01): snapshot + jetstream the only trade-processor path and the kind/base default;
  delete the row writer, the core/jetstream-rows feed paths and the rows settlement sweep.
- [ ] KDB-X feed lane: exactly-once write-down, account-sorted `p#` HDB, heap-guarded paged q (FR-THT-04).
- [x] Check KDB-X Community object storage on `gs://`; record the answer in ADR-102 (yes, with `objstor` init).
- [ ] q gateway over several HDB processes; `par.txt` over several disks.
- [ ] 1.5B trade sides scale proof (SC-THT-01, SC-THT-02); rebuild-from-cursor-0 proof (FR-THT-07).
- [ ] FR-THT-08: the fresh-epoch archive moves the history tables too.
