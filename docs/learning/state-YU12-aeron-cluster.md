---
title: "State YU12-aeron-cluster: Aeron Cluster BLP Consensus"
---

# State YU12-aeron-cluster Learning Guide

## Position In Learning Graph

- Previous state(s): [YU11-aeron-replication](/docs/learning/state-YU11-aeron-replication)
- Dotted-line parent(s): none
- Next state(s): [YU13-limit-order-book](/docs/learning/state-YU13-limit-order-book)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [code/generated-state-YU12-aeron-cluster](https://github.com/finos/traderX/tree/code/generated-state-YU12-aeron-cluster)
- Authoring branch (spec source): [main](https://github.com/finos/traderX/tree/main)

## Code Comparison With Previous State

- Compare against `YU11-aeron-replication`: [code/generated-state-YU11-aeron-replication...code/generated-state-YU12-aeron-cluster](https://github.com/finos/traderX/compare/code%2Fgenerated-state-YU11-aeron-replication...code%2Fgenerated-state-YU12-aeron-cluster)

## Plain-English Code Delta

- **Added:** The inherited matching and risk core runs inside an Aeron Cluster `ClusteredService`, so a Raft
- **Added:** Three cluster members form an odd quorum, each running its Media Driver, Archive, Consensus
- **Added:** A partition minority is structurally unable to elect a leader, extend the committed log, or admit
- **Added:** Snapshots capture the complete deterministic state bound to the exact applied log position: book,
- **Added:** Recovery loads the newest valid snapshot and resumes strictly after its position, asserting every
- **Added:** A replacement member with an empty volume rejoins on its own through snapshot retrieval plus
- **Added:** A stateless-forward gateway tier terminates FIX and REST order entry and follows the cluster
- **Added:** Health and metrics expose cluster role, member ID, leadership term, commit, service and snapshot

## Run This State

```bash
./scripts/start-state-YU12-aeron-cluster-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU12-aeron-cluster](/specs/YU12-aeron-cluster)
- Architecture: [/specs/YU12-aeron-cluster/system/architecture](/specs/YU12-aeron-cluster/system/architecture)
- Flows / topology: [/specs/YU12-aeron-cluster/system/runtime-topology](/specs/YU12-aeron-cluster/system/runtime-topology)
- Research: [link](/specs/YU12-aeron-cluster/research)
- Data model: [link](/specs/YU12-aeron-cluster/data-model)
- Quickstart: [link](/specs/YU12-aeron-cluster/quickstart)

