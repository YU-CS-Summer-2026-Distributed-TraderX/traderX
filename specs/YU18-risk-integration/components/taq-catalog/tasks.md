# TAQ catalog tasks

Status: implementation and private execution accepted 2026-10-14; coordinator review and separate integration approval pending.

- [x] Record bounded task, component requirements and approval hold.
- [x] Receive direct approval on 2026-10-13; prior execution/date hold superseded.
- [x] Validate all 132 preservation commits and freeze compaction inputs. (FR-TC01)
- [x] Implement larger source/date/member-separated files and readable paths. (FR-TC02–03)
- [x] Test exact verification, retry/interruption and negative controls. (FR-TC04–05, SC-TC01)
- [x] Build metadata catalogs, browse/search view and actual private data query. (FR-TC06–08)
- [x] Admit pinned image; run quote/monthly pilot and measure sizing/cost. (NFR-TC01–02, SC-TC02)
- [x] Publish 132 verified compacted sources and reconcile catalog/query results. (SC-TC03)
- [x] Verify terminal-job resource cleanup and record completion evidence. (NFR-TC03, SC-TC04)
- [ ] Review candidate and obtain separate integration approval for target checkout.

## Accepted execution and proof — 2026-10-14

Candidate d38a0507 and image ff7d01b2 passed 54 native amd64 tests and exact packaged-source hashes.
Independent verification decodes each original part once and compares every original value, schema,
provenance record, duplicate and date lineage through bounded output streams. Invalid and empty DATE
values remain unchanged and are queryable through the explicit invalid partition.

Both real pilot sources passed exact worker readback and separate native all-row audits:
July 3 quotes: 808,937,080 rows, 54 files; February monthly trades: 1,865,966,736 rows, 208 files,
19 dates. Non-tail files are 128–256 MiB; tails are at most 256 MiB. Median sizes are 194.52 and
192.84 MiB. Actual catalog queries by date/root/empty suffix and BRK/A versus BRK/B passed.
All admission, pilot and auditor VMs, groups, boot and scratch disks were verified gone.
The superseded v2 trial was stopped after snapshots; all private output attempts and sources remain intact.

The accepted private pilot JSON/Parquet/HTML catalog and COMPLETE were published create-only.
Full job ri30-catalog-full-v3-20261013-195329 was submitted once at 19:53:30 UTC with 132 tasks
and at most 32 workers. All 132 sources succeeded. Full source/current-object reconciliation accepted
240,506,339,565 rows and 16,498 files; final catalog, three actual queries and exact resource cleanup
passed. Median file size is 194.90 MiB; no non-tail size violations or files above 256 MiB.
Local finalizer recovery 4534cc74 passed 58 source tests; the executed image remains unchanged.
Actual billed costs are unverified; measured usage and dated rate estimates remain private.
Broader RI30, replay/financial validation and canonical integration remain open.
