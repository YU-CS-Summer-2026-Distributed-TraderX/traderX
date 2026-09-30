---
title: "State YU11-aeron-replication: Aeron SBE BLP Replication"
---

# State YU11-aeron-replication Learning Guide

YU lineage. Current integration behavior is described in the [feature map](/docs/engineering/feature-map); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [YU10-fix-ingress](/docs/learning/state-YU10-fix-ingress)
- Dotted-line parent(s): none
- Next state(s): [YU12-aeron-cluster](/docs/learning/state-YU12-aeron-cluster)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU11-aeron-replication](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU11-aeron-replication)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU10-fix-ingress`: [code/generated-state-YU10-fix-ingress...code/generated-state-YU11-aeron-replication](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU10-fix-ingress...code%2Fgenerated-state-YU11-aeron-replication)

## Plain-English Code Delta

- Replicates encoded events using Aeron transport and SBE messages, with replay and epoch recovery.
- **Evidence entrypoints:** test-aeron-loss-replay.sh; test-state-YU11-aeron-replication.sh.
- **Boundary:** Replication alone does not provide the consensus model introduced by YU12.
- [Full feature and component map](/docs/engineering/feature-map).

## Run This State

```bash
./scripts/start-state-YU11-aeron-replication-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU11-aeron-replication](/specs/YU11-aeron-replication)
- Architecture: [/specs/YU11-aeron-replication/system/architecture](/specs/YU11-aeron-replication/system/architecture)
- Flows / topology: [/specs/YU11-aeron-replication/system/runtime-topology](/specs/YU11-aeron-replication/system/runtime-topology)
- Research: [link](/specs/YU11-aeron-replication/research)
- Data model: [link](/specs/YU11-aeron-replication/data-model)
- Quickstart: [link](/specs/YU11-aeron-replication/quickstart)

