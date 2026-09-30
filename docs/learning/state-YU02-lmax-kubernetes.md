---
title: "State YU02-lmax-kubernetes: LMAX Kubernetes"
---

# State YU02-lmax-kubernetes Learning Guide

YU lineage. Current integration behavior is described in the [feature map](/docs/engineering/feature-map); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [014-fdc3-intent-interoperability](/docs/learning/state-014-fdc3-intent-interoperability)
- Dotted-line parent(s): none
- Next state(s): [YU03-in-memory-risk-gateway](/docs/learning/state-YU03-in-memory-risk-gateway)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU02-lmax-kubernetes](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU02-lmax-kubernetes)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `014-fdc3-intent-interoperability`: [code/generated-state-014-fdc3-intent-interoperability...code/generated-state-YU02-lmax-kubernetes](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-014-fdc3-intent-interoperability...code%2Fgenerated-state-YU02-lmax-kubernetes)

## Plain-English Code Delta

- Packages the sequencer for Kubernetes, with snapshots, startup replay and readiness gates.
- **Evidence entrypoints:** Snapshot tests; test-state-YU02-lmax-kubernetes.sh.
- **Boundary:** The historical single-member tier is retained for study; current cluster operation follows YU12.
- [Full feature and component map](/docs/engineering/feature-map).

## Run This State

```bash
./scripts/start-state-YU02-lmax-kubernetes-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU02-lmax-kubernetes](/specs/YU02-lmax-kubernetes)
- Architecture: [/specs/YU02-lmax-kubernetes/system/architecture](/specs/YU02-lmax-kubernetes/system/architecture)
- Flows / topology: [/specs/YU02-lmax-kubernetes/system/runtime-topology](/specs/YU02-lmax-kubernetes/system/runtime-topology)
- Research: [link](/specs/YU02-lmax-kubernetes/research)
- Data model: [link](/specs/YU02-lmax-kubernetes/data-model)
- Quickstart: [link](/specs/YU02-lmax-kubernetes/quickstart)

