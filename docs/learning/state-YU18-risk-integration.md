---
title: "State YU18-risk-integration: Risk Integration"
---

# State YU18-risk-integration Learning Guide

YU lineage. Current integration behavior is described in the [state overview](/docs/engineering/whats-new); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [YU17-otc-rates](/docs/learning/state-YU17-otc-rates)
- Dotted-line parent(s): none
- Next state(s): none

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU18-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU18-risk-integration)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU17-otc-rates`: [code/generated-state-YU17-otc-rates...code/generated-state-YU18-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU17-otc-rates...code%2Fgenerated-state-YU18-risk-integration)

## Plain-English Code Delta

- Builds immutable risk bundles, validates dated market inputs and external results, and displays job status and coverage. Components also extend order lifecycle and projection recovery.
- **Evidence entrypoints:** test-state-YU18-risk-integration.sh; check-yu18-composition.py; component tests.
- **Boundary:** The accepted container path prices a closed synthetic Treasury-bill profile. Production risk remains unavailable.
- [All YU states and additions](/docs/engineering/whats-new).

## Run This State

```bash
python3 eod-risk-bundles/bundle.py --help
```

## Canonical Spec Links

- State spec pack: [/specs/YU18-risk-integration](/specs/YU18-risk-integration)
- Architecture: [/specs/YU18-risk-integration/system/architecture](/specs/YU18-risk-integration/system/architecture)
- Flows / topology: [/specs/YU18-risk-integration/system/runtime-topology](/specs/YU18-risk-integration/system/runtime-topology)
- Research: [link](/specs/YU18-risk-integration/research)
- Data model: [link](/specs/YU18-risk-integration/data-model)
- Quickstart: [link](/specs/YU18-risk-integration/quickstart)

