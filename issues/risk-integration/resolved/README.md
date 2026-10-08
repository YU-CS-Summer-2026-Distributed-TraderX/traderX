# Resolved risk-integration issues and tasks

Updated: 2026-10-07. Maintainer: TraderX coordinator.

These eight bounded RI scopes are complete at their stated evidence level. Source/generated tests do not establish deployment, retained HA, current performance or general financial acceptance. RI10 publication is user-confirmed. The original staged RI10/RI12 additions remain preserved and pending their original commit workflow.

| ID | Completed scope | Remaining qualification elsewhere |
|---|---|---|
| [RI-02](02-risk-containerization.md) | Original local risk-container packaging | Current service upgrade and connected portfolio contract: RI15/16 and LR03 |
| [RI-10](10-documentation-refresh.md) | Docusaurus refresh, delivered/published by its lane | Later feature changes need normal documentation maintenance |
| [RI-12](12-cross-account-self-match-prevention.md) | Local cross-account self-match protection | Production identity/isolation: RI11; compatible deployment remains separate |
| [RI-17](17-interrupted-proof-cleanup.md) | Interrupted proof cleanup and retained rollback refusal | Actual rig borrowing requires scoped authorization |
| [RI-18](18-stp-image-content-freshness.md) | Content-based intended image freshness | Deployment artifact qualification: RI20/LR05 |
| [RI-19](19-reconciliation-proof-subject.md) | Attributable reconciliation subjects and cleanup | Broader recovery acceptance: RI06/21/LR04 |
| [RI-22](22-audit-and-admission-observability.md) | Gauges, account readouts and sequenced OTC refusals | Deployment and financial acceptance are separate |
| [RI-28](28-gateway-ack-metric-semantics.md) | Bounded ACK classification/metric semantics | Gateway lifecycle live proof: RI23/LR01 |

The October7 evening integration `bf1d13e9` adds completed milestones inside RI09/23/24/25; those broad workstreams remain active. The reviewed risk-service container candidate `077ffac` has passed LR03StageA for its exact image/profile, but is not incorporated into an approved target. It is not a resolved integrated task; connected StageB remains open.

## Closeout rule

Use the [task lifecycle skill](/Users/yaakov/dev/lmax/.claude/skills/traderx-task-lifecycle/SKILL.md): review/corrections, live-rig disposition, report to the user, user integration approval, verified integration, then done/archive and index updates. Archive a bounded task record rather than closing its whole RI parent when unrelated work remains. Required acceptance inside a task stays open until passed or explicitly deferred by the user. Additional live/deployment work remains linked and honestly qualified.

See the [active queue](../README.md), [task records](../tasks/README.md), [live-rig queue](../live-rig/README.md) and [YU18 components](../../../specs/YU18-risk-integration/components/README.md). Preserve failed attempts and historical evidence; do not infer current live proof from an older revision.
