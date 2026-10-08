# Maintenance specs

Issue fixes, reliability hardening, observability corrections and diagnostic acceptance packs live here. Product/service feature specs remain in [components](../components/README.md). Each maintenance pack keeps README/spec/plan/tasks, requirement IDs, ownership and evidence; runtime overrides and shared generation stay at the state parent. Moving a pack changes its location, not its implementation or acceptance level.

| Pack | Status |
|---|---|
| [ACK classification metrics](ack-metrics/README.md) | See pack tasks and linked RI/LR evidence |
| [Book and engine memory accounting](book-memory-accounting/README.md) | See pack tasks and linked RI/LR evidence |
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

Future fixes, including feed reconnect, belong here. Update the parent RI issue, bounded task record and live-rig queue as appropriate. A directory is not a service or deployment; follow [state pack layout](../../../docs/spec-kit/state-components.md) and the project task lifecycle.
