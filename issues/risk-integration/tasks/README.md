# Active bounded task records

Updated: 2026-10-07. Maintainer: TraderX coordinator.

A task records an implementable scope, its chat, review, live acceptance and integration approval. RI files remain the broader workstreams. Use the [task lifecycle skill](/Users/yaakov/dev/lmax/.claude/skills/traderx-task-lifecycle/SKILL.md).

| Task | Parent | Current state |
|---|---|---|
| [Fresh-rig audit corrections](../resolved/live-audit-fixes-20261009.md) | Audit F1–F6 / LR05 identity | Done: approved implementation integrated; local live checks passed |
| [Risk-service container qualification](ri15-16-risk-container-upgrade.md) | RI15/16 | Reviewed; awaiting integration target/approval. LR03StageA passed; connected StageB separate |
| [Feed reconnect](ri23-feed-reconnect.md) | RI23 | Integrated; acceptance pending; LR02 established live proof outstanding |

At completion move only the completed task record to [resolved](../resolved/README.md), update this link and the parent/index/spec/live entries, and preserve historical evidence. The entire RI parent stays open if it has other work. READY and a successful live test alone do not make a task integrated or done.

## October 8 approved integration

The user approved console candidate `8f4623d26251292a629b2e6a1c40dd9632e428ad` (including `fcba84f4`) and archival proof candidate `055581fdef5b08bf24757384c8167723384b7d97` into `traderX-risk-integration`. The console packaging task is [done and archived](../resolved/lr05-console-packaging.md); the proof drivers remain historical fixtures for the closed October 8 lease. This entry supersedes earlier awaiting-approval/source-not-integrated statements for these two candidates only. Independent full-ancestry generation, source/render parity and scoped checks are recorded under parent `coordination/eod-integration/review-evidence/review36-20261008/integration/`. No rig/container was restarted. Existing native image/connected evidence applies to unchanged build-context bytes. Broader RI/LR release, authenticated Desk, revised amd64, cloud, retained recovery, financial and sizing obligations remain open.

Completed bounded slice: [console runtime packaging](../resolved/lr05-console-packaging.md). The broader live campaign record retains its independent LR limitations; only its archival proof source was integrated.
