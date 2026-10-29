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
| [FIX fill reports](fix-fill-reports/README.md) | Code 2026-10-28 (`abc57a39`, `0326f733`); on production `traderx` since roll 4 (2026-10-28, `00b31d33`; rollout record §13) (ADR-084) |
| [Control feed republish](control-feed-republish/README.md) | Code 2026-10-27 (`3b10e7e2`); on production `traderx` since roll 4 (2026-10-28, `00b31d33`; rollout record §13) (ADR-085) |
| [Exactly-once client](exactly-once-client/README.md) | Venue contract and FIX restatement integrated 2026-10-28; binary client proven locally (ADR-091) |
| [FIX gateway memory](fix-gateway-memory/README.md) | Integrated 2026-10-28: slow-consumer limit, bounded inbound queue, streaming adoption, fill-feed backoff (ADR-092) |
| [FIX TLS entry](fix-tls-entry/README.md) | Built and proven on kind 2026-10-29: TLS-only socket for the external CompID, /32-allowlisted L4 Service, pinned client (ADR-095); not on production |
| [FIX session persistence](fix-session-persistence/README.md) | Integrated 2026-10-28; on production `traderx` since roll 4 (2026-10-28, `00b31d33`; rollout record §13) (ADR-086) |
| [Exactly-once dedupe](exactly-once-dedupe/README.md) | Proposed 2026-10-28 (ADR-090), awaiting approval; not implemented |
| [GKE member runtime](gke-member-runtime/README.md) | Integrated 2026-10-27: archive volume, heap, pause goal, SIGTERM, promtail (ADR-081); retained member claims and no negative DNS caching, on production since roll 4 (ADR-087) |

Future fixes, including feed reconnect, belong here. Update the parent RI issue, bounded task record and live-rig queue as appropriate. A directory is not a service or deployment; follow [state pack layout](../../../docs/spec-kit/state-components.md) and the project task lifecycle.
