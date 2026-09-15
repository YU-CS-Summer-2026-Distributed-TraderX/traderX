# Contract Delta: EOD Risk Bundles

## Local CLI

- `python3 bundle.py build --positions FILE --contracts FILE --epoch ID --valuation-time ISO8601 --origin synthetic|export --output DIRECTORY`
- `python3 bundle.py validate DIRECTORY`
- `python3 bundle.py mock DIRECTORY --output RESULT_DIRECTORY`

Success prints only `ok` and `bundleId` as JSON. Validation/publication failures exit nonzero. Each output destination is new; retries use a new destination and can compare deterministic bundle IDs.

## Manifest v1

| Field | Contract |
|---|---|
| schema | `traderx.eod-bundle.v1` |
| clusterEpoch | Nonblank caller-supplied deployment/run identity |
| valuationTime | ISO8601 timestamp with explicit UTC offset, normalized by Python datetime |
| inputOrigin | `synthetic` or `export` |
| cut | String-valued consensusSequence, sessionDate, priceSnapshotVersion, cutSha256 from both exports |
| marketInputs | Exactly `{"status":"NOT_SUPPLIED"}` |
| artifacts | positions/contracts entries: fixed local path, integer CSV schema, SHA-256 of file bytes, integer row count |
| bundleId | SHA-256 of manifest body without this field |

Canonical JSON encoding is Python json.dumps with sort_keys=True, indent=2, allow_nan=False and default ensure_ascii=True, followed by one LF and UTF-8 encoding. Source files retain their original bytes, including comments and line endings. Validator v1 requires the exact manifest field set and fixed filenames.

Both preambles must agree on every cut field. Position CSV headers match RiskExtractCsv schema 3 exactly, and OTC headers match SwapContractCsv schema 2 exactly. Source units are preserved: swap fixedRate is an annual decimal fraction; bond clean prices/accrual are fractions of par; coupon follows the source annual-percent convention; listed-option quantity counts contracts and multiplier is separate. Netting identifiers are metadata, not authorization to offset exposures.

Validation checks transport shape, finite numeric fields, selected date relationships and unique identities. It does not validate complete financial schedules, marks against markets, or OTC lifecycle state. A past-maturity contract remains a source record and is not silently removed.

## Mock result v1

Schema is `traderx.mock-result.v1`. Root fields: bundleId, clusterEpoch, valuationTime, engine=`transport-mock`, synthetic=true, usableForRisk=false, status=`MOCK_COMPLETE`, coverage={submitted:N, priced:0}, items. Row semantics are defined in data-model.md. No numeric risk result is fabricated. Mock results are a local transport contract, not an agreed external pricing response.

## Privacy and publication

CLI-created files use owner-only permissions in private staging directories. Inputs and outputs belong in a private directory outside the checkout. Only generated synthetic fixtures are versioned in the repository. Existing destinations are refused. A process crash can leave a sibling `.publish-lock`; inspect running processes and the destination before manual recovery. The CLI does not delete another writer's lock.

## Local coordinator v1

`python3 coordinator.py --state PRIVATE_DIRECTORY discover INBOX|run|status|retry JOB_ID`.
Use the corresponding subcommand arguments: `discover INBOX`, `run`, `status`, or `retry JOB_ID`.
The state directory must be outside a Git checkout, owned by the caller and mode 0700.
No daemon, scheduler, network transport or pricing service is started.

Discovery validates direct child bundle directories, skips hidden staging and reports incomplete
(non-manifest) directories separately. Manifest-bearing invalid bundles make the CLI exit nonzero.
Accepted CSV bytes are snapshotted in private state and revalidated before execution. Job identity
hashes `{bundleId, profile}` using the existing canonical encoding; the only profile is
`transport-mock-v1`, `transport-check`, `NOT_SUPPLIED`, `usableForRisk=false`.

SQLite records jobs and every attempt. States are QUEUED → RUNNING → MOCK_COMPLETE or FAILED;
interrupted attempts are preserved as INTERRUPTED and their jobs requeued. Ordinary failures require
explicit retry; retries retain earlier attempt IDs, errors and artifacts. An exclusive host-local
OS lock spans database access and execution. A live owner cannot be reclaimed by another command;
process exit releases the lock, then the next `run` recovers RUNNING jobs. If a result directory was
published before interruption, it is validated and ingested without recomputation; malformed results
fail. If none was published, a new attempt uses a new path. Old staging/lock remnants are retained.

Result ingestion requires the complete expected identity set, exact input bundle/epoch/time,
currencies, null NPVs, empty Greeks, NOT_PRICED/MOCK_ONLY and zero priced coverage. Published bytes
are hashed in SQLite and checked again by status. Invalid accepted artifacts are marked INVALID
and never selected. Integrity failure does not silently overwrite historical completion records.

Selection is scoped to cluster epoch and business date. Numeric consensus sequence then price
snapshot version orders discovered cuts; arrival/completion time does not. Equal-ranked distinct
bundles make selection ambiguous. A newer pending/failed cut prevents selection of an older completed
one. `selectedMockResult` never implies financial usability; root and row `usableForRisk` stay false.
The status view lists all jobs, attempts, failures and integrity findings. There is no global ranking
across epochs, currencies or unrelated portfolio runs, and no approved financial-result consumer.

The `proposed/` schemas are explicitly unapproved exchange drafts and do not change these contracts.
