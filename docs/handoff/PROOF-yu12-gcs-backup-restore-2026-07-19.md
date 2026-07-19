# YU12 GCS snapshot backup + restore — proven (2026-07-19)

Closes the emptyDir durability gap: whole-cluster loss no longer means total data loss.

## Backup (automated, every 5 min)
- CronJob `yu12-snapshot-backup` (ns traderx, runs on std-pool) `kubectl exec`s member-0 and
  sparse-tars `/data/{cluster,archive}` (Aeron snapshots + log), 208 MB → ~8 MB gzip, uploads to
  `gs://traderx-501015-order-matcher-journal-archive/yu12-cluster-snapshots/`
  (`snap-<ts>.tgz` + `latest.tgz`, retention: newest 12).
- **Auth:** reuses the existing journal-archive **HMAC** keys (secret `order-matcher-journal-gcs-hmac`)
  via the S3-compatible API — no new IAM, no Workload Identity. Gotchas solved: node SA is
  storage-read-only; gsutil ranks metadata-SA above HMAC (unusable) → used **boto3 against the GCS
  S3 endpoint** with `signature_version=s3v4`, path addressing, and
  `AWS_REQUEST/RESPONSE_CHECKSUM_*=when_required` (recent botocore's auto-CRC32 breaks GCS).
- Hot-tar of the live append-only log exits 1 ("file changed as we read it") — tolerated
  (`--warning=no-file-changed`, accept exit ≤1); the snapshots are complete/immutable, the log tail
  is a bonus. A `recording.log`-present check gates the upload.

## Restore (`scripts/yu12/restore-from-gcs.sh`)
- StatefulSet init container `restore-from-gcs`: when `RESTORE_FROM_GCS=1`, **member-0 only** pulls
  `latest.tgz` and extracts into its (emptyDir) `/data` before the JVM boots; members 1 & 2 come up
  empty and rejoin via the proven catch-up. No-op on normal boot.
- Script: scale 0 → arm RESTORE_FROM_GCS=1 → scale 3 → wait for leader/convergence → disarm.

## Proof (live DR drill)
- Backed up at member-0 applied **1,672,240**, snapshots 91.
- Wiped all 3 (scale 0), restored: **member-0 came back at applied 1,673,369** (the backup, not
  empty), elected leader; members 1 & 2 rejoined and **all 3 converged (~1.68M), 1/1 Ready, trades
  booking, 0 ID reuse.** Consensus timeouts preserved (rung A 50/200/100/25).

## Known nuance
- A whole-cluster restore is a **new epoch**, so trading clients must fully **reconnect** (new
  session) — native-leader-following survives a leader change but not a full-cluster wipe (it waits
  for a NewLeaderEvent that never comes on a new epoch). The gateway's isClosed-reconnect handles
  this; the proof client needed a restart. Document for client operators.
- emptyDir + the disarm `set env` step trigger a rolling restart — harmless (catch-up gate).
