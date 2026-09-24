# Components

| Component | Status | Source ownership |
|---|---|---|
| [EOD integration](eod-integration/README.md) | Implemented foundations; see tasks | State generation/runtime-overrides/eod-risk-bundles and explicit producer overrides |
| [Order types](order-types/README.md) | Planned | Future YU18-owned overrides; inspect inherited layer composition |
| [Risk service](risk-service/README.md) | Planned packaging; external spec draft exists | Engine repository; TraderX consumes its API |
| [Risk pipeline](risk-pipeline/README.md) | Planned extension over existing coordinator | Explicit YU18-owned orchestration components |
| [Recovery identity](recovery-identity/README.md) | Review needed: bounded receipt lineage | YU18 eod-risk-bundles; engine/SQL migration remains proposed |
| [Demo acceptance](demo-acceptance/README.md) | Review needed; local live proof 2026-09-24 | Root scripts `scripts/ri07`, kind rig scripts `scripts/yu15`, console status presentation |

| [Event recovery](event-recovery/README.md) | Implemented; local review pending | YU18 archive replay, trade-processor recovery service and additive SQL checkpoint |

Adding a directory does not activate generation. Shared files stay at the state parent. Follow [component rules](../../../docs/spec-kit/state-components.md).
