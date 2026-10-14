# TAQ catalog plan

Status: implementation and private execution accepted 2026-10-14; coordinator review and separate integration approval pending.

1. Direct approval received 2026-10-13. Check preservation status and all 132 committed sources.
2. Implement source-preserving streaming compaction with readable type/date/archive/generation paths,
   accumulate across existing small chunks, and keep original/member/date populations separate.
3. Implement independent exact verification, immutable publication and retry reconciliation.
4. Build JSON/Parquet metadata catalog, browse/search view, candidate-file selection and private DuckDB
   query by date/root/suffix/archive. Require explicit selection when sources overlap.
5. Run synthetic failure controls, admit a separate pinned image, then bounded quote/monthly pilot.
6. Measure output size distribution, RSS/scratch, throughput, query/request overhead and incremental cost.
7. Publish all verified sources, reconcile catalog/data queries, then verify exact-job resource cleanup.

Private evidence and earlier detailed design: parent coordination/eod-integration/review-evidence/
ri30-pilot-20261012/COMPACTION-CATALOG-CONTINUATION.md. The current direct 2026-10-13 start instruction supersedes its old hold. Heartbeat stays paused.
Shared runtime/generation/architecture stay at the YU18 parent. No new service rollout is implied.
