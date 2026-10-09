# Resolved risk-integration issues and tasks

Updated: 2026-10-08. Maintainer: TraderX coordinator.

These bounded RI scopes and completed maintenance tasks are complete at their stated evidence level. Source/generated tests do not establish deployment, retained HA, current performance or general financial acceptance. RI10 publication is user-confirmed. The original staged RI10/RI12 additions remain preserved and pending their original commit workflow.

| ID | Completed scope | Remaining qualification elsewhere |
|---|---|---|
| [RI16 C8 upstream upgrade](ri16-upstream-c8-upgrade.md) | Reviewed corrected service e770f912 approved and incorporated in owned external-engine target | LR03B connected portfolio contract; original-engine adoption/readiness/fleet remain open |
| [Risk container qualification](ri15-16-risk-container-upgrade.md) | Approved owned container/service integration with exact corrected image80c885f8 applicability | Broader financial/production/native/GPU/connected acceptance remains separate |
| [RI-02](02-risk-containerization.md) | Original local risk-container packaging | Current service upgrade and connected portfolio contract: RI15/16 and LR03 |
| [RI-10](10-documentation-refresh.md) | Docusaurus refresh, delivered/published by its lane | Later feature changes need normal documentation maintenance |
| [RI-12](12-cross-account-self-match-prevention.md) | Local cross-account self-match protection | Production identity/isolation: RI11; compatible deployment remains separate |
| [RI-17](17-interrupted-proof-cleanup.md) | Interrupted proof cleanup and retained rollback refusal | Actual rig borrowing requires scoped authorization |
| [RI-18](18-stp-image-content-freshness.md) | Content-based intended image freshness | Deployment artifact qualification: RI20/LR05 |
| [RI-19](19-reconciliation-proof-subject.md) | Attributable reconciliation subjects and cleanup | Broader recovery acceptance: RI06/21/LR04 |
| [RI-22](22-audit-and-admission-observability.md) | Gauges, account readouts and sequenced OTC refusals | Deployment and financial acceptance are separate |
| [RI-28](28-gateway-ack-metric-semantics.md) | Bounded ACK classification/metric semantics | Gateway lifecycle live proof: RI23/LR01 |
| [Fresh-rig audit corrections](live-audit-fixes-20261009.md) | Six console/epoch/monitoring findings; approved integration and focused fresh local live regressions | Managed recovery, historical-data repair, financial and cloud qualification remain separate |
| [RI20 generation-derivation](ri20-generation-derivation.md) | Approved local build-input guard integrated from `ca9cb5dd`; current full ancestry/contexts verified | RI20/LR05 production artifact/deployment/retained compatibility remain open |
| [Console runtime packaging](lr05-console-packaging.md) | Missing runtime module and native-platform packaging; approved integration with unchanged measured context | RI11/RI20 and LR05 release/GKE/authenticated-flow work remain open |

The October7 evening integration `bf1d13e9` adds completed milestones inside RI09/23/24/25; those broad workstreams remain active. The updated risk-service candidate `e770f912` is now incorporated into its human-approved owned external-engine target. These two bounded records are resolved; historical077 acceptance is retained, and connected StageB/original Alex adoption remain open.

## Closeout rule

Use the [task lifecycle skill](/Users/yaakov/dev/lmax/.claude/skills/traderx-task-lifecycle/SKILL.md): review/corrections, live-rig disposition, report to the user, user integration approval, verified integration, then done/archive and index updates. Archive a bounded task record rather than closing its whole RI parent when unrelated work remains. Required acceptance inside a task stays open until passed or explicitly deferred by the user. Additional live/deployment work remains linked and honestly qualified.

See the [active queue](../README.md), [task records](../tasks/README.md), [live-rig queue](../live-rig/README.md) and [YU18 components](../../../specs/YU18-risk-integration/components/README.md). Preserve failed attempts and historical evidence; do not infer current live proof from an older revision.
