# Maintenance specs

Issue fixes, reliability hardening, observability corrections and diagnostic acceptance packs live here. Product/service feature specs remain in [components](../components/README.md). Each maintenance pack keeps README/spec/plan/tasks, requirement IDs, ownership and evidence; runtime overrides and shared generation stay at the state parent. Moving a pack changes its location, not its implementation or acceptance level.

| Pack | Status |
|---|---|
| [GitHub push CI repairs](github-push-ci/README.md) | Integrated at `4f823668`; user push and hosted proof pending |
| [Test-suite profiles](test-suite-profiles/README.md) | RI31 in progress: executable guard checks and compatible synthetic fixtures; full-suite delivery pending |
| [CompSci cold-demo admission](compsci-gke-admission/README.md) | Done for the approved initial pack and hosted repairs through1132510f; broader performance/retained-state qualification remains separate |
| [Generation performance](generation-performance/README.md) | Done: approved local batching/current-input parity; warmed offline profile only |
| [Fresh-rig audit corrections](live-audit-fixes/README.md) | Done: approved integration and local live checks passed |
| [ACK classification metrics](ack-metrics/README.md) | See pack tasks and linked RI/LR evidence |
| [Book and engine memory accounting](book-memory-accounting/README.md) | See pack tasks and linked RI/LR evidence |
| [Console runtime packaging](console-runtime-packaging/README.md) | Bounded correction integrated; broader LR05 qualification remains open |
| [October 8 live-rig proof record](live-rig-acceptance/README.md) | Integrated historical closed-lease fixtures; do not execute without a new scope |
| [Generated build-input derivation](generation-derivation/README.md) | Done: approved local guard integration; LR05 remains separate |
| [Image admission](image-admission/README.md) | See pack tasks and linked RI/LR evidence |
| [Member account readouts](member-account-readout/README.md) | See pack tasks and linked RI/LR evidence |
| [OTC refusal audit](otc-refusal-audit/README.md) | See pack tasks and linked RI/LR evidence |
| [Queued-task lifecycle](owner-task-deadline/README.md) | See pack tasks and linked RI/LR evidence |
| [Projector OOM exit](projector-oom-exit/README.md) | See pack tasks and linked RI/LR evidence |
| [Interrupted proof cleanup](proof-cleanup/README.md) | See pack tasks and linked RI/LR evidence |
| [Readiness observations](readiness-diagnostics/README.md) | See pack tasks and linked RI/LR evidence |
| [Reconciliation memory/result retention](reconciliation-memory/README.md) | See pack tasks and linked RI/LR evidence |
| [Scoped reconciliation proof](reconciliation-proof/README.md) | See pack tasks and linked RI/LR evidence |
| [Replay anchor validation](replay-anchor-status/README.md) | See pack tasks and linked RI/LR evidence |
| [Risk capacity gauges](risk-capacity-metrics/README.md) | See pack tasks and linked RI/LR evidence |
| [STP image provenance](stp-image-provenance/README.md) | See pack tasks and linked RI/LR evidence |
| [Tape status, universe and identity diagnostics](tape-status/README.md) | See pack tasks and linked RI/LR evidence |

| [Bounded settlement](settlement-memory/README.md) | Source and isolated retained-data proof accepted; original delivery acceptance remains separate |
| [Engine-review hardening](engine-review-hardening/README.md) | Integrated 2026-10-27: S1–S6 (ADR-082), F1–F11 |
| [Console sessions](console-sessions/README.md) | Integrated 2026-10-26 |
| [FIX fill reports](fix-fill-reports/README.md) | Code 2026-10-28 (`abc57a39`, `0326f733`), live on `traderx-fixf`, not deployed (ADR-084) |
| [Control feed republish](control-feed-republish/README.md) | Code 2026-10-27 (`3b10e7e2`), not deployed; production repaired by hand (ADR-085) |
| [FIX session persistence](fix-session-persistence/README.md) | Integrated 2026-10-28, proven on a GKE side namespace, not deployed (ADR-086) |
| [GKE member runtime](gke-member-runtime/README.md) | Integrated 2026-10-27: archive volume, heap, pause goal, SIGTERM, promtail (ADR-081); retained member claims and no negative DNS caching, code only (ADR-087) |

Future fixes, including feed reconnect, belong here. Update the parent RI issue, bounded task record and live-rig queue as appropriate. A directory is not a service or deployment; follow [state pack layout](../../../docs/spec-kit/state-components.md) and the project task lifecycle.
