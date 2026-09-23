# Feature Specification: Risk Integration

**State**: `YU18-risk-integration`  
**Integration branch**: `traderX-risk-integration`  
**Parent**: `YU17-otc-rates`  
**Updated**: 2026-09-23  
**Status**: EOD foundations implemented; additional components planned

## State scope

YU18 groups related integration and trading capabilities as separately specified components. A component does not create a new state ID, lineage entry or permanent state branch. Component requirement IDs are stable and scoped; shared contracts and generation remain state-owned.

## Requirements

- FR-RI01: Preserve inherited YU17 behavior and the existing EOD component's financial and wire contracts.
- FR-RI02: Each component SHALL have README, spec, plan and tasks documents with status, source ownership, dependencies and acceptance criteria.
- FR-RI03: Shared quickstart, system architecture, contracts, data model and all generation inputs SHALL remain at this state root or its existing shared subdirectories.
- FR-RI04: Runtime composition SHALL remain explicit in state-level generation; adding component documentation SHALL NOT silently activate runtime code.
- FR-RI05: Component delivery SHALL record source/generated/live evidence separately; planned capabilities SHALL NOT be advertised as implemented.
- FR-RI06: Engine-owned financial code and container implementation SHALL remain in the engine repository; the packaging component here SHALL specify the consumer-facing integration and link the engine pack.

## Component specifications

- [EOD integration](components/eod-integration/spec.md): canonical FR-EB requirements, moved from the former root spec.
- [Order types](components/order-types/spec.md): new stop/stop-limit and staged order-lifecycle work.
- [Risk service packaging](components/risk-service/spec.md): local engine container and its integration boundary.
- [Risk pipeline](components/risk-pipeline/spec.md): service submission, recovery and later parallel portfolio execution.

## Acceptance

State generation includes component spec documents; byte-exact financial fixtures and source/generated behavior are preserved. Planned component scaffolds are validated structurally but do not count as implemented acceptance. See [component rules](../../docs/spec-kit/state-components.md).
