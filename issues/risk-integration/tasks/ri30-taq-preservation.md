# RI30 — TAQ preservation and pilot preparation

Updated: 2026-10-11. Status: in progress. Author: current user-assigned RI30 chat.
Parent: [RI30](../30-lossless-taq-reingestion.md).
Spec: [TAQ preservation](../../../specs/YU18-risk-integration/components/taq-preservation/README.md).

## Authorization and routing

Direct user: bypass busy coordinator for startup, keep all TAQ columns through DuckDB to GCS;
accepted proposed inventory/local converter/pilot-definition start and removed traderx from the
destination name. Destination `project-compsci-484414-taq-parquet`. No cross-chat message sent.
Chat identity/model not independently verified; no identity is inferred from a filesystem UUID.
Checkout `/Users/yaakov/dev/lmax/traderX-ri30-taq-parquet`, branch `codex/ri30-taq-parquet`,
base `67a32d6b007cfc7237968c4b4d0d533739a32e2c`.
Own paths: root scripts/ri30 and component pack, own checkout RI30/backlog/spec indexes.
No canonical checkout edits. Intended integration target traderX-risk-integration; approval pending.
Immutable passive board CLAIM: `20261012T011500Z-ri30-claim.txt` in parent coordination directory.

## Progress and review

Live metadata: 132 objects, 2,859,214,411,225 bytes, all NEARLINE, source region US-EAST1.
Contains February–July, 6 monthly trade archives and 2 daily March trade archives. Initial RI30
scope February–March needs explicit selection; overlapping archives remain separately identifiable.
Private metadata `/private/tmp/ri30-inventory.json`; bounded 1-MiB ZIP-prefix reads retained privately.
Local converter preserves strings and verifies every part using independent PyArrow readback.
25 source-level tests pass, including fake GCS retry/interruption/racing commit and corrupt output.
Real prefix checks: 89,031 quotes, 61,698 trades, 14 columns each. No suffix-bearing records in
these prefixes, so BRK/GOOG raw-source acceptance remains open. These are not complete ZIPs and
cannot establish archive CRC or full-file row reconciliation. No real data is tracked.

## Live-rig disposition

TraderX cluster proof is not required for standalone conversion tooling. Real GCS/Batch pilot,
Linux amd64 image execution, full archives, share-class/overlap checks and sizing/cost evidence
are required before claiming cloud/corpus acceptance. No replay mount or service rollout included.

## Integration and closeout

Candidate review and integration pending. No merge, push, cluster mutation or data deletion.
Existing coordinator dirty files/staging preserved by isolated checkout. Full RI30 remains open.

Destination bucket created and independently described on 2026-10-11: US-EAST1, STANDARD, uniform access, public access prevention enforced, 7-day soft delete. No source bucket change.

Final local container: image manifest list sha256:157c2af3f2f759c986e7ddd09f74cc36da763e148a95fd75f928648d1322eb2e. Network-disabled amd64 synthetic conversion passed (2 rows, 4 source columns), and packaged preserve.py/worker.py SHA256 matched final source. Frontmatter, root SpecKit, readiness, spec coverage and state-component checks passed.

## Authorized cloud continuation — 2026-10-11

Direct user authorized separate taq-processing registry, IAM and pilot execution, preserving all
three March archives separately and determining whether daily rows occur in the monthly file.
Repository/account created, Batch enabled, scoped worker IAM applied. Built v2 worker with a native
Arrow parser plus independent strict Python syntax/count pass and canonical native field digest;
26 tests pass, including exact duplicate-sensitive comparison. Local amd64 conversion and packaged
source hashes verified. Preparing one sequential three-archive campaign (4 vCPU,16 GiB,200 GB scratch,
8h max,no retries). Runtime results will be recorded after actual completion; integration remains pending.

Pilot submitted: `ri30-march-pilot-20261012-013526` in us-east1; initial state QUEUED.
Image digest `sha256:9ab57bfd37108d732bf98dc1fdf2ac22c4141a7ff442a7bc943381c8691521bd`.
Run ID `0f64034cf0a14f200da50f157352d439aa52e5d63f73398ab8914e8779843842`.
Frozen three-object input SHA256 `00eb022311446bde6b1670f5c74102a063e885dea1373216ef523b5b01c1fbba`.
27 tests passed; source tests include campaign plan limits. Private operational evidence lives in
parent coordination/eod-integration/review-evidence/ri30-pilot-20261012. No completion claim yet.

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

## Completion cleanup — 2026-10-12

User authorized completion-triggered worker cleanup after the overnight run. This is not a 4 a.m.
cutoff: active work continues until complete. Batch natively releases transient job resources; live
monthly VM boot/scratch disks have autoDelete=true, and completed daily worker resources are absent.
The existing heartbeat now verifies exact job-ID/UID-owned VMs, managed instance groups and
boot/scratch disks are gone, saves diagnostic evidence, and removes confirmed terminal-job leftovers.
Source/Parquet GCS objects, registry images and logs remain retained with their separate storage
charges. Cleanup verification precedes disabling the heartbeat. Full job allocation labels make
resource ownership explicit. Plan tests pass (3); image and preservation code remain unchanged.

## Pilot passed and full run launched — 2026-10-12

Restored GCloud authentication. Monthly conversion completed 2,119,303,962 rows and
34,210,390,850 output bytes. March 3 (116,730,301 rows) and March 4 (128,766,807 rows)
exactly match their monthly date slices across every original column with duplicate multiplicity:
EXCEPT ALL both directions found zero missing and zero extra rows for both dates. All three
archives remain separate. Pilot commit identities and frozen full manifest verified before launch.
Submitted full job `ri30-full132-20261012-v2`, UID `ri30-full132-20261-110976dc-8ecd-402c0`, 132 tasks,
parallelism 16, one task per VM. Execution pending live running-state confirmation. Completion
and transient-resource cleanup monitor remains active. No integration/push/GKE mutation.

## Authorized 32-worker replacement — 2026-10-12

User requested 32 workers. Region quota admits 128 vCPU. Original 16-worker full job was
intentionally deleted after its config/state were saved; no source or GCS output deletion.
Replacement `ri30-full132-32-20261012-v2` preserves the frozen 132-object manifest, pinned
image and full-run output identity. Completed commits are verified and reused; unfinished
attempts recompute. Config bounds: 32 concurrent tasks, one per VM, unchanged 4vCPU/16GiB,
100GB scratch,12h timeout,no retries. Monitor resumed ACTIVE and updated for replacement
and exact-ID/UID cleanup of both jobs. Launch/running confirmation pending. Plan tests:3 pass.

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

2026-10-13 user steering: consumer compaction/catalog extracted to [separate task](ri30-taq-catalog.md) and `components/taq-catalog/`. Execution authorization is on hold until direct approval on or after 2026-10-14 Eastern; heartbeat paused. Preservation continues.

## Final preservation delivery — 2026-10-13

Status: cloud preservation completed and independently reconciled; source integration review needed.
Batch `ri30-full132-32-20261012-v2` SUCCEEDED all132 tasks at2026-10-13T07:57:00Z
(03:57 Eastern); run duration18h40m54s. Frozen input2,859,214,411,225bytes, February–July.
Output240,506,339,565rows,508,831Parquet parts,2,773,681,563,719bytes; malformed_rows0.
Independent final audit checked132 exact source/image/manifest identities, nonzero member/part
counts, contiguous source record coverage, and current GCS generation,size,CRC32C,SHA256 metadata
for all508,831 committed parts. Worker independent field/provenance Parquet readback occurred
before each commit; final audit did not redownload/reparse the entire2.77TB output.
Pilot exact EXCEPT ALL comparisons prove March3/March4 daily trades identical to monthly date
slices, including multiplicity. Three sources remain separate. Final GCP query found no RI30
worker VMs,boot/scratch disks or managed groups. GCS data and registry images remain retained.
Evidence: parent review-evidence/ri30-pilot-20261012/preservation-final-verification.json,
preservation-final-cleanup.json,full32-final-check.json,march-daily-containment.json.
Compaction/catalog is a separate proposed component/task, not implemented; user approval hold
and paused automation remain. BroadRI30 and canonical source integration are not marked done.
