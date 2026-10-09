# Fresh-rig audit corrections

Status: implemented; source, generated and fresh local live checks passed; awaiting integration approval. Owner: codex-review36, 2026-10-09.

Fix six findings reproduced on source `4a1402a6179c33e3af88a0264558fda092f74cd0`: FIX workspace bypass, encoded admin-path bypass, legacy trade/order epoch mismatch, initial credential retry, partial EOD status and cluster scrape selection. See [task record](../../../../issues/risk-integration/tasks/live-audit-fixes-20261009.md), [spec](spec.md), [plan](plan.md), [tasks](tasks.md).

Runtime owners are the root console, YU18 MatchingEngineClusteredService override and maintained cluster observability scripts. No financial methods, managed recovery protocol, earlier-state publisher defaults or retained data migration change.
