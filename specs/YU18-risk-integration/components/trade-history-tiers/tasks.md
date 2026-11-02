# Trade history tiers tasks

- [ ] Render `history-feed` + ClickHouse for GKE; `rig.sh up`/`down` and readiness include them (FR-THT-02).
- [ ] ClickHouse storage policy: hot SSD days, older partitions on a GCS disk; same rows before and after (FR-THT-03, SC-THT-03).
- [ ] Production run writing both SQL `trades` and ClickHouse; per-partition count and hash equal.
- [ ] Inventory test of every SQL `trades` reader (FR-THT-05); move blotter/history, recon, EOD and proofs.
- [ ] Restore gate checks the history store per partition (FR-THT-06, SC-THT-04 with delete/duplicate mutations).
- [ ] trade-processor writes positions only; archive SQL `trades` (FR-THT-01).
- [ ] KDB-X feed lane: exactly-once write-down, account-sorted `p#` HDB, heap-guarded paged q (FR-THT-04).
- [ ] Check KDB-X Community object storage on `gs://`; record the answer in ADR-102.
- [ ] q gateway over several HDB processes; `par.txt` over several disks.
- [ ] 1.5B trade sides scale proof (SC-THT-01, SC-THT-02); rebuild-from-cursor-0 proof (FR-THT-07).
