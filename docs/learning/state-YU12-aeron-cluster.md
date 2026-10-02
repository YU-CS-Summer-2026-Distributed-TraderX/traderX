---
title: "State YU12-aeron-cluster: Aeron Cluster BLP Consensus"
---

# State YU12-aeron-cluster Learning Guide

YU lineage. Current integration behavior is described in the [state overview](/docs/engineering/whats-new); historical state branches may differ.

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

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU12-aeron-cluster](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU12-aeron-cluster)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU11-aeron-replication`: [code/generated-state-YU11-aeron-replication...code/generated-state-YU12-aeron-cluster](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU11-aeron-replication...code%2Fgenerated-state-YU12-aeron-cluster)

## Plain-English Code Delta

- Runs matching on a three-member Aeron Raft cluster, with leader election, snapshots and archive recovery.
- **Evidence entrypoints:** ThreeMemberClusterTest; SnapshotRoundTripTest.
- **Boundary:** A successful local recovery case is not an unbounded HA guarantee. Preserve compatible core versions and retained history.
- [All YU states and additions](/docs/engineering/whats-new).

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

