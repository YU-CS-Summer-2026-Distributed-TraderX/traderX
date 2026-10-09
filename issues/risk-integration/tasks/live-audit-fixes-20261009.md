# LIVE-AUDIT-FIXES-20261009 — Six fresh-rig corrections

Updated: 2026-10-09. Status: reviewed locally; awaiting integration approval. Author: codex-review36.

Human request: “fix the issues” following the six-finding live audit. Scope is F1–F6 and focused local/live regression checks. Chat: 01a11e05-cee0-7f23-b49e-76a6c35b5547. Checkout `traderX-review36-integration`, branch `codex/review36-integration`, accepted base `4a1402a6179c33e3af88a0264558fda092f74cd0`. Canonical `traderX-risk-integration` remains read-only with user edits preserved. Integration target: traderX-risk-integration, candidate approval pending after review.

Source owners: root web-front-end-console; specs/YU18-risk-integration/generation/runtime-overrides/order-matcher; scripts/yu15 observability entrypoints/renderer. [Maintenance pack](../../../specs/YU18-risk-integration/maintenance/live-audit-fixes/README.md). Linked [LR05](../live-rig/lr-05-deployment-artifacts.md) provides the artifact identity boundary; this task does not close its wider deployment obligations.

Original observed findings and raw evidence: parent workspace `coordination/eod-integration/live-rig-evidence/20261009-review36/REPORT.md`. New fix evidence: `coordination/eod-integration/live-rig-evidence/20261009-review36-fixes/`. Own status: `coordination/eod-integration/status/codex-review36.txt`.

## Completion gates

Focused regression tests and rendered-owner parity; native images matching changed source; fresh synthetic rig checks for the exact bypasses, epoch joins, startup credential recovery, partial EOD display and selected metrics. Export before owned cleanup; preserve other containers, volumes and canonical edits. No cloud, pushes, historical-data repair, cross-version recovery, external pricing or broad RI/LR closure.

## Progress

All six corrections passed focused live regression checks on fresh synthetic cluster `traderx-auditfix-20261009-01a11e05`. 24 Node tests, four monitoring-render tests, 17 Java tests, root/spec/readiness/front-matter gates, full-ancestry generation and 19 native console-image checks passed. Runtime identity: 84 node-image comparisons, 28 pod images and ten critical payload hashes matched. Browser inspection showed amber partial PnL with ten rows and two halted accounts.

The first FIX regression attempt used a stale Service name and failed before submission; the corrected Service was used for the successful run. One workspace read ran while the intentional authentication outage was still recovering and returned 503; it passed after the recovery gate. Failed attempts remain in raw evidence. No ambiguous order was resubmitted.

Owned rig removed after export. Original fourteen containers and twenty-nine volumes were preserved. The raw baseline also included this task's temporary packaging-test container, which its own test removed; the cleanup reconciliation documents that overlap. Canonical HEAD/staging and twenty dirty/untracked files retain their prior hashes. Original audit evidence remains immutable.

The revised GKE entrypoint is source/render tested only; no cloud deployment occurred. No retained-data repair, managed Desk recovery, external pricing or broader RI/LR acceptance is claimed. Canonical integration remains pending user approval of this candidate. See fix evidence `REPORT.md` for exact controls and limits.
