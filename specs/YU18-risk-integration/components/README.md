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

## Reference mapping

[Instrument reference mapping](instrument-reference-mapping/README.md) records TreasuryDirect/OCC research and synthetic support examples; financial conventions remain decisions.

Issue fixes and diagnostic specs are indexed in [maintenance](../maintenance/README.md). They keep their own contracts and task evidence without adding feature components.
