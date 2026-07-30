---
title: "State YU11-aeron-replication: Aeron SBE BLP Replication"
---

# State YU11-aeron-replication Learning Guide

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

## Rendered Code

- Generated branch: [YU11-aeron-replication](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU11-aeron-replication)
- Authoring branch (spec source): [YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU15-eod-risk-extract)

## Code Comparison With Previous State

- Compare against `YU10-fix-ingress`: [YU10-fix-ingress...YU11-aeron-replication](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/YU10-fix-ingress...YU11-aeron-replication)

## Plain-English Code Delta

- **Added:** A second replication transport chosen at startup with `BLP_REPLICATION_TRANSPORT=nats|aeron`, so a
- **Added:** Aeron reliable unicast UDP replication through an Archiving Media Driver sidecar in each
- **Added:** A NetworkPolicy permitting the Aeron data, ACK, control, and replay UDP ports only between
- **Added:** Archive storage on the order-matcher persistent volume, with a documented capacity-expansion and
- **Added:** A fixed 64-byte SBE input record encoded directly into an Aeron `tryClaim` buffer, so the primary's
- **Added:** Follower validation that rejects an unknown schema, a required unknown flag, a stale leader epoch,
- **Added:** Decoding of each accepted record straight into a claimed input-ring slot, published exactly once,
- **Added:** A fixed-capacity SPSC map from the follower's local ring sequence to the primary

## Run This State

```bash
inherits YU10-fix-ingress runtime harness
```

## Canonical Spec Links

- State spec pack: [/specs/YU11-aeron-replication](/specs/YU11-aeron-replication)
- Architecture: [/specs/YU11-aeron-replication/system/architecture](/specs/YU11-aeron-replication/system/architecture)
- Flows / topology: [/specs/YU11-aeron-replication/system/runtime-topology](/specs/YU11-aeron-replication/system/runtime-topology)
- Research: [link](/specs/YU11-aeron-replication/research)
- Data model: [link](/specs/YU11-aeron-replication/data-model)
- Quickstart: [link](/specs/YU11-aeron-replication/quickstart)

