# Components

| Component | Status | Source ownership |
|---|---|---|
| [EOD integration](eod-integration/README.md) | Implemented foundations; see tasks | State generation/runtime-overrides/eod-risk-bundles and explicit producer overrides |
| [Order types](order-types/README.md) | Implemented and locally exercised; deployment-profile performance open | YU18 order matcher, gateway and instrument/order entry UI |
| [Risk service](risk-service/README.md) | Local packaging/CI milestone implemented; external engine upgrade open | Engine repository packaging; TraderX API/container checks |
| [Risk pipeline](risk-pipeline/README.md) | Synthetic one-portfolio milestone implemented; service/worker upgrade open | YU18 coordinator/intake and external service boundary |
| [Recovery identity](recovery-identity/README.md) | Review needed: bounded receipt lineage | YU18 eod-risk-bundles; engine/SQL migration remains proposed |
| [Demo acceptance](demo-acceptance/README.md) | Review needed; local live proof 2026-09-24 | Root scripts `scripts/ri07`, kind rig scripts `scripts/yu15`, console status presentation |
| [Event recovery](event-recovery/README.md) | Integrated locally; retained/live acceptance open | YU18 archive replay, trade-processor recovery service and additive SQL checkpoint |
| [Automatic projection recovery](automatic-projection-recovery/README.md) | Integrated locally; retained/live acceptance open | YU18 member catch-up page, trade-processor fenced worker, additive SQL cursor; disposable profile only |
| [Managed-run UI](managed-run-ui/README.md) | Review needed; local fixture and real-transition proofs 2026-09-24 | `web-front-end-console` (standalone, not generated): blotter, Admin trade list, console proxies |

| [Combined trader workspace](trader-workspace-combined/README.md) | Connected local UI; production auth and remaining migration open | Standalone combined desk and existing console API/server |

| [Synthetic portfolio generator](portfolio-generator/README.md) | Integrated locally; large load campaign open | State-parent risk-portfolio-tools; external engine schema and optional proof |

Adding a directory does not activate generation. Shared files stay at the state parent. Follow [component rules](../../../docs/spec-kit/state-components.md).

## October 7 integration

These components are implemented and reviewed locally. Combined verification is recorded with the integration delivery; no deployment, HA, throughput or financial acceptance is implied.

| Component | Source ownership |
|---|---|
| [Proof cleanup](proof-cleanup/README.md) | Root proof runner, persistent journal and supervisor |
| [STP image provenance](stp-image-provenance/README.md) | Root image builder and proof admission |
| [Reconciliation proof](reconciliation-proof/README.md) | Root scoped SQL/live subject selection and cleanup |
| [Image admission](image-admission/README.md) | Root intended-artifact and per-node reference checks |
| [Replay anchors](replay-anchor-status/README.md) | Root target/storage evidence validator and callers |
| [Risk gauges](risk-capacity-metrics/README.md) | YU18 member HTTP metrics and current risk accessors |
| [Readiness diagnostics](readiness-diagnostics/README.md) | YU18 peer observation sampler and member HTTP |
| [Projector OOM exit](projector-oom-exit/README.md) | YU18 container JVM and deployment configuration |
| [OTC refusal audit](otc-refusal-audit/README.md) | YU18 shadow regulatory replay and optional decision tap |
| [Member accounts](member-account-readout/README.md) | YU18 admin HTTP readout of current member risk table |
| [Book memory accounting](book-memory-accounting/README.md) | Root bounded JVM instrumentation; direct authoritative units |
| [Tape status](tape-status/README.md) | YU16 producer health and console status renderer |
| [ACK metrics](ack-metrics/README.md) | YU18 gateway bounded classification history |
