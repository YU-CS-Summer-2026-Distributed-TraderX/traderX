---
title: "State YU03-in-memory-risk-gateway: In-Memory Risk Gateway"
---

# State YU03-in-memory-risk-gateway Learning Guide

YU lineage. Current integration behavior is described in the [feature map](/docs/engineering/feature-map); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [YU02-lmax-kubernetes](/docs/learning/state-YU02-lmax-kubernetes)
- Dotted-line parent(s): none
- Next state(s): [YU04-durable-control-feeds](/docs/learning/state-YU04-durable-control-feeds)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU03-in-memory-risk-gateway](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU03-in-memory-risk-gateway)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU02-lmax-kubernetes`: [code/generated-state-YU02-lmax-kubernetes...code/generated-state-YU03-in-memory-risk-gateway](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU02-lmax-kubernetes...code%2Fgenerated-state-YU03-in-memory-risk-gateway)

## Plain-English Code Delta

- Checks credit, size, notional, restricted securities and price collars in memory before admission.
- **Evidence entrypoints:** BlpRiskStateTest; RiskControlControllerTest.
- **Boundary:** These controls implement specific admission rules, not regulatory certification.
- [Full feature and component map](/docs/engineering/feature-map).

## Run This State

```bash
./scripts/start-state-YU03-in-memory-risk-gateway-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU03-in-memory-risk-gateway](/specs/YU03-in-memory-risk-gateway)
- Architecture: [/specs/YU03-in-memory-risk-gateway/system/architecture](/specs/YU03-in-memory-risk-gateway/system/architecture)
- Flows / topology: [/specs/YU03-in-memory-risk-gateway/system/runtime-topology](/specs/YU03-in-memory-risk-gateway/system/runtime-topology)
- Research: [link](/specs/YU03-in-memory-risk-gateway/research)
- Data model: [link](/specs/YU03-in-memory-risk-gateway/data-model)
- Quickstart: [link](/specs/YU03-in-memory-risk-gateway/quickstart)

