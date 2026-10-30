# Data Model: EOD Risk Bundles

## Source records

Positions retain YU17 schema 3 at `(accountId, security)` grain. OTC records retain schema 2 at `contractId` grain. IDs become globally scoped through manifest clusterEpoch. The wrapper preserves net holdings and individual OTC bookings exactly as exported.

## Bundle

`manifest.json`, `positions.csv`, `contracts.csv` are the only bundle entries. The manifest fields are defined in `contracts/contract-delta.md`. A new EOD version or corrected file yields a different content identity. Origin is `synthetic` or `export`; it is caller-declared, not inferred from prices.

## Mock result

`results.json` contains the input bundle ID, epoch, valuation time and one item per input row. Position items carry account/security; OTC items carry account/contractId. Each item carries currency, source kind, NOT_PRICED, MOCK_ONLY, null NPV and empty Greeks. The result contains no portfolio valuation total.

## Coordinator state

Private state contains `jobs.sqlite3`, `inputs/<bundleId>/`, and
`results/<jobId>/<attemptId>/results.json`. Jobs retain canonical workload identity, manifest,
profile, lifecycle, error and accepted result path/hash. Attempts retain their own identity,
start/end times, status, error and result path. SQLite transactions commit RUNNING before worker
execution and commit acceptance after validation. Host-local advisory locking serializes commands.

Status derives selection from the largest numeric cut sequence/version per epoch and business date.
It retains all history and refuses to pick among conflicting bundles with equal cut order.

Completed-export receipts carry the existing readiness payload: individual CSV hashes and counts, schemas, cut metadata, file URIs and adjacent quiescence witness. They are stored independently of bundles and identify when the pair is ready for packaging; they do not supply epoch or market/reference-data versions.

## Projection cursor (downstream read side)

`projection_cursor(projection_scope VARCHAR(64), partition_id INTEGER, stream_seq BIGINT, consensus_seq BIGINT,
snapshot_at DATETIME, stream_created_ns BIGINT)`, primary key `(projection_scope, partition_id)`, created by `ri06.sql` and by both keys
(`001-initialSchema.sql`, `900-migrations.sql`) of the YU18 `kubernetes-runtime/manifests/base/database-init-configmap.yaml`. In `TRADE_PROJECTION_MODE=snapshot` it records where
each partition's last SQL snapshot sits in the JetStream capture stream. The snapshot writes it in the same
transaction as the changed positions and blotter windows. A restart replays from `stream_seq + 1`
([ADR-080](system/adr-080-downstream-read-side.md)). `stream_created_ns` is the creation time (epoch ns) of the stream the cursor was taken on, bound before the first
message is applied and NULL for a row written before FR-DRS-36. A restart refuses a stream with another creation
time (STREAM_RESET): a stream recreated under the same name restarts its sequences.
In row projection with one partition it also holds one row per cluster epoch, `projection_scope =
'trade-seq:<trade id prefix>'` (`e1-<epoch>-`, or empty for legacy ids), `partition_id = 0`, with the last
`tradeSeq` committed to SQL in `stream_seq`. Each trade batch writes it in its own transaction. A restart
counts the sides between it and the first `tradeSeq` it receives as missing (FR-DRS-02).
With the order view snapshot (FR-DRS-30) it also holds one row per order partition, `projection_scope =
'order-view:<scope>'`, with the stream sequence the partition's committed open orders are exact at.

`projection_accounting(projection_scope VARCHAR(64), partition_id INTEGER, sides BIGINT, max_seq BIGINT, sum_seq
BIGINT, sum_sq BIGINT, duplicates BIGINT, epoch VARCHAR(64))`, primary key `(projection_scope, partition_id)`, is the
trade partition's exactly-once accounting (FR-DRS-29). It is written in the snapshot's transaction, with the cursor.
The sums wrap at 2^64 (a signed BIGINT holding the low 64 bits).

`order_view_snapshot(projection_scope VARCHAR(64), partition_id INTEGER, orderid VARCHAR(50), accountid INTEGER,
status VARCHAR(32), payload TEXT)`, primary key `(projection_scope, partition_id, orderid)`, holds the memory-only
order view's open orders, each as the JSON update the view holds (FR-DRS-30). Both tables come from `ri06.sql` and
both init-configmap keys, like `projection_cursor`.
