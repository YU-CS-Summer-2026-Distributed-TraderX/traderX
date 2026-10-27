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
snapshot_at DATETIME)`, primary key `(projection_scope, partition_id)`, created by `ri06.sql` and by both keys
(`001-initialSchema.sql`, `900-migrations.sql`) of the YU18 `kubernetes-runtime/manifests/base/database-init-configmap.yaml`. In `TRADE_PROJECTION_MODE=snapshot` it records where
each partition's last SQL snapshot sits in the JetStream capture stream. The snapshot writes it in the same
transaction as the changed positions and blotter windows. A restart replays from `stream_seq + 1`
([ADR-080](system/adr-080-downstream-read-side.md)).
