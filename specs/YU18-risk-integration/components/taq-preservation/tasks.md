# TAQ preservation tasks

- [x] Inspect RI30 and YU07 lossy projection/filter/timestamp behavior.
- [x] Live 132-object metadata inventory, including generations, CRC32C and storage classes.
- [x] Implement all-field conversion, local verification and GCS commit/retry worker.
- [x] Run synthetic success/failure controls; 25 tests passed on 2026-10-11.
- [x] Convert bounded real prefixes: 89,031 quotes and 61,698 trades, each 14 columns.
- [x] Prepare bounded one-task Batch definition renderer and operator guide.
- [x] Build and exercise local Linux amd64 image; registry publication/admission still pending.
- [x] Provision Batch identity, API/quota/network and private destination; worker started on GCP.
- [ ] Full real archive/schema/row, suffix and overlap acceptance.
- [ ] Real GCS retry/interruption and nonempty output proof.
- [ ] Measure RSS/scratch/throughput/compression and estimate full-run cost.
- [ ] Review/integrate candidate with direct human approval.
- [ ] Full selected-corpus conversion and final dataset reconciliation.

No full-file, all-schema, financial, replay or cloud-run claim follows from bounded prefix tests.

Destination bucket created and independently described on 2026-10-11: US-EAST1, STANDARD, uniform access, public access prevention enforced, 7-day soft delete. No source bucket change.

Final local container: image manifest list sha256:157c2af3f2f759c986e7ddd09f74cc36da763e148a95fd75f928648d1322eb2e. Network-disabled amd64 synthetic conversion passed (2 rows, 4 source columns), and packaged preserve.py/worker.py SHA256 matched final source. Frontmatter, root SpecKit, readiness, spec coverage and state-component checks passed.

Cloud campaign launched 2026-10-11 (local date), job `ri30-march-pilot-20261012-013526`. Three separate archives and exact multiset comparison are in progress; no final containment verdict yet. Source suite now has 27 passing tests.

## Pilot passed and full run launched — 2026-10-12

Restored GCloud authentication. Monthly conversion completed 2,119,303,962 rows and
34,210,390,850 output bytes. March 3 (116,730,301 rows) and March 4 (128,766,807 rows)
exactly match their monthly date slices across every original column with duplicate multiplicity:
EXCEPT ALL both directions found zero missing and zero extra rows for both dates. All three
archives remain separate. Pilot commit identities and frozen full manifest verified before launch.
Submitted full job `ri30-full132-20261012-v2`, UID `ri30-full132-20261-110976dc-8ecd-402c0`, 132 tasks,
parallelism 16, one task per VM. Execution pending live running-state confirmation. Completion
and transient-resource cleanup monitor remains active. No integration/push/GKE mutation.

## Consumer publication follow-up

- [ ] Verify all132 preservation commits before compaction launch.
- [ ] Implement/test and admit a compactor image with larger bounded files and readable source/date paths.
- [ ] Pilot compacted quote/monthly-trades sources and independently verify exact all-field/provenance/duplicate preservation.
- [ ] Publish verified132-source compacted dataset, keeping overlapping sources separate.
- [ ] Publish JSON/Parquet metadata catalog and browse/search view.
- [ ] Exercise an actual private date/symbol/archive DuckDB query from the catalog.
- [ ] Verify publication/catalog reconciliation and terminal-job VM/disk cleanup.

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

Canonical source/corpus milestone done2026-10-14 at898f5150; broaderRI30replay/financial/connected-use remains separate. Historical pending statuses above retain their original dates.
