# TAQ preservation plan

1. Inventory private source object metadata and record exact generations/checksums; classify
February–March scope and monthly/daily overlap. Complete live metadata pass on 2026-10-11.
2. Implement standalone CSV/ZIP preservation through DuckDB with independent PyArrow readback,
synthetic failure controls and immutable GCS commit protocol. Local implementation completed.
3. Prepare a digest-pinned Batch pilot definition: one standard 4-vCPU/16-GiB worker, 100-GB
scratch, two-hour task deadline, no automatic retries. Provision private destination. Measure a
full archive before expanding; these are initial bounds, not validated production sizing.
4. Execute full-archive/container/cloud acceptance, settle suffix and overlap questions, calculate
retrieval/compute/storage estimate, then select full-run concurrency and inputs.
5. Submit candidate for integration review. Replay/tape mounting remains separate from preservation.

Runtime, service scaling, existing raw lifecycle and coordinator checkout remain unchanged.

Direct authorization expanded the pilot to March 3, March 4 and the monthly March trade archive, kept separately. The launched campaign uses one sequential worker, 200 GB scratch and an eight-hour task ceiling, then performs exact full-field comparison with duplicate multiplicity.

## Authorized 132-archive continuation — 2026-10-11

User authorized the full February–July inventory after pilot passes: 132 independent archive tasks,
16 maximum concurrent workers, all original columns, three overlapping March archives separate.
March 3 completed: 116,730,301 rows, 265 parts, 1,906,654,486 bytes in 858.81 seconds. Its original
sequential job was then stopped to avoid duplicating the separately launched March 4/month workers.
Full manifest is frozen and reverified; 132-task Batch config prepared, not submitted before gate.
Each worker: 4 vCPU, 16 GiB, 100 GB scratch, 12h timeout, no automatic retries. Source suite: 28 pass.
Heartbeat `ri30-pilot-and-16-worker-full-run` checks pilot, launches only after all three commits and
conclusive overlap comparison pass, and monitors full run. Private evidence/runbook: parent
coordination/eod-integration/review-evidence/ri30-pilot-20261012/FULL-RUN-CONTINUATION.md.
Full job reserved ID `ri30-full132-20261012-v2`; source identity and completion gates remain mandatory.
No source deletion, deduplication, GKE changes, push or integration included.

## Authorized consumer publication — 2026-10-12

User requested compaction and a searchable catalog after all132 preservation sources finish.
Preservation currently106 complete26 active; compaction has not started. Publish a new private
consumer dataset with readable type/date/archive/source-generation paths and initially target
128–256MiB compressed files, measured before claiming sizing. Preserve every original field,
text type, duplicate, and provenance column; keep overlapping daily/monthly archive identities
separate. Reconcile exact source/date populations and validate independent Parquet readback.
Publish immutable completion records and machine-readable JSON/Parquet catalog mapping
source names,dates,schema,counts,verified file generations/checksums and overlap relationships.
Deliver a browse/search view plus an exercised private DuckDB row query by date,symbol including
suffix,and explicitly selected archive. Metadata search alone does not fulfill data search.
Existing output retained. New compactor image and bounded cloud pilot/full run required after
132-source preservation gate. User authorized conditional follow-up; heartbeat reactivated for
this new scope, quiet on unchanged state. Detailed continuation plan is in parent evidence
COMPACTION-CATALOG-CONTINUATION.md. Completion includes source/output/catalog verification
and exact-job transient-resource cleanup. No integration,push,GKE or source deletion included.
