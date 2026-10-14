# TAQ catalog and compacted publication

Status: full private publication, catalog queries and exact resource cleanup accepted 2026-10-14; ready for coordinator review.
Owner: RI30 task author, isolated branch `codex/ri30-catalog`; integration approval pending.
Dependency: [TAQ preservation](../taq-preservation/README.md), all 132 verified source commits.
Source ownership: `scripts/ri30/` compaction, catalog and private query tools.

[Specification](spec.md), [plan](plan.md), [tasks](tasks.md), [bounded task record](../../../../issues/risk-integration/tasks/ri30-taq-catalog.md).
Shared architecture and generation remain [state-parent-owned](../../README.md).

The tools produce larger all-column Parquet with readable date/archive paths, searchable metadata
and actual private data queries by date, symbol root, suffix and one explicitly selected archive.
Every original field and duplicate remains intact; overlapping daily and monthly archives stay separate.

The compactor, independent verifier, catalog, private query, separate pilot auditor and create-only
finalizer have 54 passing source and native tests. The real quote/monthly-trades pilot passed exact
worker readback and separate all-row audits for 2,674,903,816 rows in 262 files. Actual catalog queries
with empty, A and B suffixes passed. All preceding job resources were verified gone.
The full run accepted all 132 sources and 240,506,339,565 rows in 16,498 files across 250 source/member/date
partitions. Current generation, size, CRC32C and SHA256 metadata reconciled against exact source commits.
The private JSON/Parquet/HTML catalog and COMPLETE were published create-only; actual full-catalog
queries with empty, A and B suffixes passed. Exact job-owned VM/group/boot/scratch cleanup passed.
Median file size is 194.90 MiB, maximum 200.31 MiB; all non-tail files meet 128–256 MiB. Compressed
output is 3,348,141,897,106 bytes, 20.7% larger than preservation despite 30.8 times fewer files.
The admitted worker image retains 54 native tests; the local finalizer recovery revision 4534cc74 has
58 source tests, renewable CLI credentials and paged metadata reads. It did not replace the executed
cloud image. Worker all-row equivalence and separate whole-row pilot audits are distinct evidence.
Canonical integration, financial/replay validation and broader RI30 completion remain pending.
