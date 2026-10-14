# RI30 — TAQ compaction and searchable catalog

Current closeout2026-10-14: bounded implementation/corpus milestone done; human-approved bec674a9 with preservation-tool dependencies integrated at898f5150. Dated approval holds and pending statuses below are historical, superseded by this closeout. Broader replay/financial/connected-use scope remains open.

Updated: 2026-10-14. Status: review needed; full private publication and acceptance complete.
Owner: RI30 task author; checkout traderX-ri30-catalog, branch codex/ri30-catalog.
Parent: [RI30](../30-lossless-taq-reingestion.md).
Component: [TAQ catalog](../../../specs/YU18-risk-integration/components/taq-catalog/README.md).
Dependency: [preservation task](ri30-taq-preservation.md), all 132 verified source completions.

## Scope and acceptance

Compact completed Parquet into larger files with readable type/date/archive/generation names.
Preserve every original column/value/type, duplicate and provenance field; overlapping archives
remain independent. Publish searchable JSON/Parquet metadata and a browse view plus exercised private
DuckDB row queries by date/symbol root/suffix/archive. Verify exact source/member/date population,
all 132 catalog identities, current object checksums, measured file sizes and transient cloud cleanup.

## Authorization and ownership

Direct human instruction on 2026-10-13 released the previous execution/date hold. After automatic
approval review required direct confirmation in this task chat for the private manifest uploads,
the human replied “Approve this private RI30 execution” to the exact upload, native admission,
two-source pilot and gated full publication scope, with at most 32 workers. This is current execution
authorization. No source mutation, vendor/IAM expansion, GKE changes, Git push or canonical integration.
Heartbeat stays paused.

Chat 01a135c0-06b9-7ee3-ba80-5c458191b705 owns the isolated author checkout on accepted base c2c028b0.
Coordinator 01a12b5c-4c93-7e62-b90a-3e56223dee78 owns canonical routing/index. Original author checkout
37d9e397 is read-only. Only scoped preservation dependencies were carried; no broad merge of its
older base. Runtime and generation remain YU18-parent-owned; the standalone tools have no descendant overrides.

## Source and pilot evidence

Candidate d38a0507; admitted private image digest
ff7d01b2db0771e22ab7f12d7068d254deaca2acf2a536a2bf0946edcf2169b8.
All 132 live preservation commit generations and hashes were frozen. Exact source/image/input
identity and contiguous member coverage were checked. Source and native amd64 suites have 54 tests.
The revised verifier decodes each original part once, retaining exact schema/value/provenance/record/
date-lineage comparison. Multi-date, boundary, corruption and invalid/empty DATE query controls pass.
The superseded v2 trial was stopped after snapshots; its exact resources were verified gone.

Both separate whole-row pilot audits passed: July 3 quotes, 808,937,080 rows and 54 files;
February monthly trades, 1,865,966,736 rows and 208 files over 19 dates. Total 2,674,903,816 rows.
Generation/SHA256-pinned receipts reconcile source, image, input, schema, values, provenance,
record/date populations and actual private queries. Separate catalog entrypoint queries with empty,
A and B suffixes passed; BRK/A and BRK/B original records remain distinct.

Every non-tail file is 128–256 MiB and every tail is at most 256 MiB. Quote/monthly median sizes
are 194.52/192.84 MiB, process peak RSS approximately 0.94/4.86 GiB, and retained input/output scratch
20.18/70.27 GB. Compressed bytes increased 17%/34% relative to preservation; fewer files do not imply
less storage. Actual billed total is unverified; measured usage and published rate assumptions remain
in private evidence. All admission, pilot and auditor VMs/groups/boot/scratch disks were verified gone.
Accepted private pilot JSON/Parquet/HTML/COMPLETE were published create-only under version c4997058.

## Full execution acceptance — 2026-10-14

Full job ri30-catalog-full-v3-20261013-195329, UID ri30-catalog-full-c3d69803-79ae-4fba00, succeeded
132/132, with a 32-worker limit. The executed image and frozen input remained unchanged. Exact source
identities, pinned source commits and current generation/size/CRC32C/SHA256 metadata accepted all
240,506,339,565 rows in 16,498 files. JSON/Parquet/HTML metadata and COMPLETE were published create-only
under the single full consumer version 697a7ffa. Three actual full-catalog queries (A/empty, BRK/A,
BRK/B) returned 20 rows each with all 17 original/provenance columns; BRK class sample records are distinct.

There are 250 source/member/date partitions, zero invalid-date rows and three explicit overlap groups.
Sources and duplicates remain separate. Median output size is 194.90 MiB, p95 198.02 MiB, maximum
200.31 MiB. All non-tail files meet 128–256 MiB; 173 of 250 tails are below 128 MiB. Output is
3,348,141,897,106 bytes, 20.7% more compressed bytes than preservation, with 30.8 times fewer files.
Maximum measured process RSS is 5.25 GiB; retained input/output data-file footprint is 125.93 GB,
which excludes boot/image/filesystem/query overhead and is not total VM memory or filesystem peak.

Final publication initially stopped on static CLI access-token expiry. Local recovery revision
4534cc74 uses renewable CLI credentials, bounded ordered source reads and paged current integrity
metadata; unreferenced attempts are excluded. All 58 source tests passed, including expiry renewal,
failed source, missing/current-generation mismatch and corrupt-object controls. These local finalizer
changes were not rebuilt into the already executed image; its native proof remains the 54-test admission.
No cloud job was resubmitted. The resumed full audit/publication/query/cleanup/measurement gates passed.
All seven admitted/pilot/auditor/full job resource cleanup receipts passed; exact full VM/group/boot/
scratch populations are empty. No source data, preservation data or attempt history was deleted.

Full workers used 267.42 summed source wall hours. Dated compute/disk rate estimates for observed
full-job allocation are approximately $46.17–$47.20, not billed totals; API, transfer, image, logging,
discounts and taxes are excluded. New full publication storage is estimated at $62.36 per 730 hours
under the saved October 13 rates, in addition to retained originals and earlier attempts.

Next: coordinator reviews the concrete author candidate and dependency order, then obtains separate
human approval for the canonical integration target. This task is review needed, not integrated/done.
Broader RI30, replay and financial validation remain open. Heartbeat remains paused.

Private evidence: parent coordination/eod-integration/review-evidence/ri30-catalog-20261013/.
RESUME.md identifies the current stage, pinned refs, exact job, and remaining steps. It also links
historical approval and superseded-trial evidence. Source data and attempt history remain immutable.

## Canonical integration and completion

Direct human approved bec674a9 and explicit preservation-tool dependencies into traderX-risk-integration. All35paths match exact candidate at898f5150. Canonical58tests, root/component gates and full private YU18generation pass. Coordinator independently verified132source/16498unique-file/240506339565rowmetadata, catalog hashes/Parquet records, three actual saved query outputs and liveprivateCOMPLETErecord. Fullworkerall-field equivalence/pilotrowaudits/jobcleanup reviewed from supplied evidence; coordinator didnot redownload3.35TB. Existing23unrelateddirty/privatefiles and staging preserved. No newcloudjob/push/data deletion/automation. Privatepublication3348141897106bytes(+20.7%) and immutableoriginals/pilots/attempts retained. Applicationtape/replay/financialuse notproven. Evidence: parent coordination/eod-integration/review-evidence/ri30-catalog-20261013/integration-bec674a9.
