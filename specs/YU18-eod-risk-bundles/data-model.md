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
