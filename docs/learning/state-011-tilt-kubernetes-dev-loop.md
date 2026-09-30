---
title: "State 011: Tilt Local Dev on Kubernetes"
---

# State 011 Learning Guide

Upstream numbered lineage, separate from YU01–YU18.

## Position In Learning Graph

- Previous state(s): [010-kubernetes-runtime](/docs/learning/state-010-kubernetes-runtime)
- Dotted-line parent(s): none
- Next state(s): [012-platform-convergence-c3](/docs/learning/state-012-platform-convergence-c3)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `canonical`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-011-tilt-kubernetes-dev-loop](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-011-tilt-kubernetes-dev-loop)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `010-kubernetes-runtime`: [code/generated-state-010-kubernetes-runtime...code/generated-state-011-tilt-kubernetes-dev-loop](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-010-kubernetes-runtime...code%2Fgenerated-state-011-tilt-kubernetes-dev-loop)

## Plain-English Code Delta

- **Flow Impact:** No functional flow deltas. Baseline flows `F1`-`F6` and startup behavior remain compatible with state `010`, including inherited price snapshot+stream freshness behavior and push-based realtime blotter subscriptions.

## Run This State

```bash
./scripts/start-state-011-tilt-kubernetes-dev-loop-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/tilt-kubernetes-dev-loop](/specs/tilt-kubernetes-dev-loop)
- Architecture: [/specs/tilt-kubernetes-dev-loop/system/architecture](/specs/tilt-kubernetes-dev-loop/system/architecture)
- Flows / topology: [/specs/tilt-kubernetes-dev-loop/system/runtime-topology](/specs/tilt-kubernetes-dev-loop/system/runtime-topology)
- Research: [link](/specs/tilt-kubernetes-dev-loop/research)
- Data model: [link](/specs/tilt-kubernetes-dev-loop/data-model)
- Quickstart: [link](/specs/tilt-kubernetes-dev-loop/quickstart)

