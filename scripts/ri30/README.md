# RI30 TAQ preservation

Standalone local/cloud batch tooling; no TraderX service or generation override. Destination:
`project-compsci-484414-taq-parquet`. Status: local implementation verified; authorized three-archive cloud pilot running.

## Contract

`preserve.py` parses strict UTF-8 CSV (optional encoding BOM) without type inference. Every
source field is retained as a non-null Parquet string, including empty strings, leading/trailing
spaces, leading zeros, decimal scale, correction/condition codes and nanosecond timestamp text.
CSV quoting/line endings are syntax: original archives retain byte-exact evidence. No trimming,
symbol mapping, filtering, deduplication, sorting or numerical timestamp conversion occurs.

A strict Python CSV pass independently validates syntax and counts records, then a native Arrow
CSV pass supplies bounded string chunks and exact logical record numbers; DuckDB writes ZSTD Parquet. PyArrow independently reads each part and compares schema, all source
field values using canonical Arrow IPC SHA256, and contiguous source/member/record provenance.
ZIP members are streamed to EOF (CRC validation); all non-directory members must be supported
CSV, uniquely named, unencrypted and nonempty. Duplicate/case-colliding/reserved headers fail.
Malformed width, invalid UTF-8, CSV errors, oversized fields/records and truncated/corrupt ZIP
fail the task. A failure report counts observed malformed rows; processing stops at first error,
so it does not claim a total malformed-row census. No completion is published on failure.

Source columns stay inside each file. Source-object/member grouping replaces symbol/date
partitioning in this preservation layer: monthly archives may contain multiple dates. Dates
and types can be derived separately with an explicit validated schema and source selection.

## Local use

Create an isolated environment and install `requirements.txt` plus pytest. Use private scratch
outside Git for real inputs/outputs. Synthetic tests:

```sh
PYTHONPATH=scripts/ri30 python -m pytest -q scripts/ri30/tests
python scripts/ri30/preserve.py /private/path/input.zip /private/path/new-output
```

Local defaults: 100,000 records per part and 32 MiB parser blocks; cloud worker uses up to 500,000 records per part; 1 MiB maximum field;
2 DuckDB threads; 2 GB DuckDB memory limit locally, 4 GB in the worker. These are pilot
bounds, not measured production sizing. Total RSS includes Python/Arrow outside DuckDB's limit.
The output directory must not already exist. Source archive + bounded chunks occupy scratch.

## Frozen inventory and overlapping sources

Capture `gcloud storage ls --json 'gs://SOURCE/**'` privately. Supply a JSON array of exact
object names to `inventory.py INVENTORY SELECTION OUTPUT` to create a generation/checksum-pinned
manifest. Empty, missing, duplicate or checksum-less selections fail. The worker additionally
requires the manifest object's generation and SHA256.

The 2026-10-11 inventory includes February–July, monthly trade archives and two March daily
trade archives. RI30's recorded initial scope is February–March. Preserve overlapping source
archives independently if selected; never concatenate daily and monthly trades into an
analytical tape without establishing their relationship. The preservation worker does not
claim records from separate files are disjoint.

## Cloud preparation and publication

Build the Dockerfile context `scripts/ri30`; record the resulting immutable image digest.
The Python base is pinned by digest; admit the final worker by its own registry digest. The worker uses attached service-account ADC, not downloaded HMAC keys.
Grant source Object Viewer and destination Object Creator/Viewer, plus project Batch Agent Reporter (`roles/batch.agentReporter`), Logs Writer and repository Artifact Registry Reader permissions. Source mutation and delete rights are not needed.

Destination created and verified 2026-10-11: private uniform-access bucket in us-east1 with public access prevention.
Keep destination STANDARD during the pilot. Source inventory currently reports NEARLINE;
include retrieval fees and output storage in the estimate. No source lifecycle changes/deletes.

`batch_plan.py --image REGISTRY/IMAGE@sha256:... --manifest-uri gs://... --generation ...
--digest ... --service-account ...` renders (does not submit) a one-task, one-worker pilot:
4 vCPU, 16 GiB, 100 GB scratch, 2-hour task limit, zero automatic retries, STANDARD VM.
The manifest must contain the one explicitly selected pilot archive. These settings are
starting bounds; a task deadline is not a hard dollar budget. Verify Batch API/IAM/quota and
network reachability before submitting. Spot/parallel full-corpus runs wait for pilot evidence.

Each task downloads the exact source generation, checks size and CRC32C, computes SHA256,
then writes chunks under a unique attempt prefix. SDK uploads verify CRC32C and use
create-only preconditions. Each output includes generation/checksum/SHA256 metadata.
Run identity binds the frozen manifest and pinned worker image. A source completion manifest is published last with `if_generation_match=0`.
A retry verifies an existing completion and referenced object metadata and exits without
rewriting. Concurrent attempts can leave unreferenced objects but only one completion wins.
No remote attempt cleanup is automatic. Readers enumerate only `commits/*.json`, never glob
all attempt Parquet files. A crash after upload and before commit leaves no published dataset.

## Outstanding acceptance

Real full-archive/member/schema census; BRK.A/B and GOOG/GOOGL source checks; independent
full-archive row reconciliation; malformed/corrupt and retry proof against GCS; registry publication/admission; peak RSS/scratch/runtime/compression measurements; cost estimate; IAM/Batch provisioning;
full run and separately authorized replay integration. Bounded real prefix samples do not prove
archive CRC, full-file completeness, all schemas or whole-corpus share-class coverage.

## Authorized three-archive pilot — 2026-10-11

The user authorized repository creation, scoped IAM, image publication and cloud execution,
keeping March monthly and March 3/4 daily trade archives separately. Repository `taq-processing`
and account `taq-parquet-worker@project-compsci-484414.iam.gserviceaccount.com` are dedicated to this workload.
The Batch API is enabled. Source Object Viewer and destination Object Creator/Viewer are bucket-scoped;
Artifact Registry Reader is repository-scoped; Batch Agent Reporter/Logs Writer are project-scoped.

`campaign.py` runs three frozen source entries sequentially and then `compare.py`, on one
4-vCPU/16-GiB VM with 200 GB scratch, no retries and an eight-hour task ceiling. The monthly archive
requires a full scan; this is not the smaller one-archive/two-hour renderer default. Retained outputs
stay source-separated and are never deduplicated. Temporary local archives/parts are cleaned by the
worker after use; remote inputs/outputs remain intact.

Comparison uses exact original field values and `EXCEPT ALL` in both directions. It excludes only
added provenance columns and selects monthly rows by each daily DATE. A zero daily-minus-monthly
count proves containment including duplicate multiplicity. Zero in both directions proves identical
multisets; a nonzero daily-minus-monthly count refutes containment. Empty inputs, schema mismatch,
missing commits/checksums or incomplete scans are inconclusive and fail, never rounded to containment.
Only metadata, counts and verdicts are logged; no actual TAQ field values enter source control.

The native parser was admitted only after the independent strict pass caught its permissive behavior
on unterminated quoted fields. Synthetic malformed input still fails. Tests cover the case where
both datasets have equal total counts but different duplicate multiplicities.

## Full archive array

After the pilot passes, use `batch_plan.py --object-count 132 --parallelism 16` with the frozen
full manifest and admitted image. This queues one archive per task, at most 16 at once, one task
per VM. Tasks retain all source columns and separate source identities; no cross-archive merge
or deduplication occurs. Each task has a 12-hour ceiling and no automatic retries. The full
manifest has a distinct run identity, so it processes the three pilot sources again as part of
the complete corpus. Verify all 132 completion manifests before claiming corpus completion.

## Completion cleanup — 2026-10-12

User authorized completion-triggered worker cleanup after the overnight run. This is not a 4 a.m.
cutoff: active work continues until complete. Batch natively releases transient job resources; live
monthly VM boot/scratch disks have autoDelete=true, and completed daily worker resources are absent.
The existing heartbeat now verifies exact job-ID/UID-owned VMs, managed instance groups and
boot/scratch disks are gone, saves diagnostic evidence, and removes confirmed terminal-job leftovers.
Source/Parquet GCS objects, registry images and logs remain retained with their separate storage
charges. Cleanup verification precedes disabling the heartbeat. Full job allocation labels make
resource ownership explicit. Plan tests pass (3); image and preservation code remain unchanged.

## Authorized 32-worker replacement — 2026-10-12

User requested 32 workers. Region quota admits 128 vCPU. Original 16-worker full job was
intentionally deleted after its config/state were saved; no source or GCS output deletion.
Replacement `ri30-full132-32-20261012-v2` preserves the frozen 132-object manifest, pinned
image and full-run output identity. Completed commits are verified and reused; unfinished
attempts recompute. Config bounds: 32 concurrent tasks, one per VM, unchanged 4vCPU/16GiB,
100GB scratch,12h timeout,no retries. Monitor resumed ACTIVE and updated for replacement
and exact-ID/UID cleanup of both jobs. Launch/running confirmation pending. Plan tests:3 pass.

## Compacted catalog candidate (2026-10-13)

`compact.py` accumulates across preservation parts into source/member/date-separated ZSTD Parquet,
initially targeting 192 MiB compressed with bounded 250,000-row groups. A final tail may be smaller.
Original strings, empty values, duplicates and provenance remain unchanged; noncanonical/invalid dates
are retained under `date=invalid`. Each file records its source lineage and symbol-root bounds.

`verify_compact.py` independently rereads original and output Parquet and compares every column/value,
including record provenance and duplicate multiplicity. It derives date populations independently and
rejects missing/duplicated lineage. Counts and digests alone do not decide equality.

`catalog_cloud.py` admits only authoritative preservation commits pinned by GCS generation/SHA256,
checks part integrity on download, verifies all output before upload, and writes create-only source
publication commits last. Incomplete attempts stay invisible. `catalog_batch.py` renders <=32 workers
with 4 vCPU/16 GiB and 250 GB cloud scratch each. The Mac must not download the full corpus.

`catalog.py` builds JSON/Parquet catalogs and a private searchable metadata HTML view from verified
commits. Its DuckDB row query requires one exact archive/date/root/suffix (including explicit empty
suffix) and returns all original columns plus provenance, with hive virtual columns disabled:

```sh
python scripts/ri30/catalog.py PRIVATE_CATALOG_JSON \
  --archive taq_quotes_20250703_csv --date 2025-07-03 --root AAPL --suffix '' \
  --output PRIVATE_QUERY_RESULT.parquet
```

Query downloads only catalog-referenced candidate files, pins their generations, and checks SHA256.
Keep catalog/data/query results private; never copy real rows or credentials into Git. Root bounds
provide conservative candidate pruning, not a claim of optimal query performance. Monthly/daily
archive overlaps are reported and never unioned automatically. Cloud publication and actual corpus
acceptance are recorded separately from the source test result; canonical integration needs approval.

Validation: `PYTHONPATH=scripts/ri30 python -m pytest scripts/ri30/tests -q`.
Dependency scripts/preservation pack/task copied from original RI30 candidate `37d9e397`; catalog
candidate starts from accepted canonical `c2c028b0` and does not merge the older author base.

`catalog_accept.py` is a separate native pilot-audit entrypoint: re-download every original and
published pilot part, rerun exact verification and actual-row query, then write immutable acceptance.
`catalog_finish.py` verifies current committed-file integrity, requires both exact pilot acceptance
receipts, and publishes catalogs/completion last. Identical retries preserve original generations;
conflicting create-only metadata/completion bytes fail instead of replacing a publication.
