---
title: "State YU04-durable-control-feeds: Durable Control Feeds"
---

# State YU04-durable-control-feeds Learning Guide

YU lineage. Current integration behavior is described in the [state overview](/docs/engineering/whats-new); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [YU03-in-memory-risk-gateway](/docs/learning/state-YU03-in-memory-risk-gateway)
- Dotted-line parent(s): none
- Next state(s): [YU05-post-trade-compliance](/docs/learning/state-YU05-post-trade-compliance)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU04-durable-control-feeds](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU04-durable-control-feeds)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU03-in-memory-risk-gateway`: [code/generated-state-YU03-in-memory-risk-gateway...code/generated-state-YU04-durable-control-feeds](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU03-in-memory-risk-gateway...code%2Fgenerated-state-YU04-durable-control-feeds)

## Plain-English Code Delta

- Publishes account, limit and security changes through transactional outboxes and versioned control feeds.
- **Evidence entrypoints:** AccountOutboxAtomicityIT; ControlFeedSubscriberTest.
- **Boundary:** Persistence, publication and engine acknowledgement are separate events.
- [All YU states and additions](/docs/engineering/whats-new).

## Run This State

```bash
./scripts/start-state-YU04-durable-control-feeds-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU04-durable-control-feeds](/specs/YU04-durable-control-feeds)
- Architecture: [/specs/YU04-durable-control-feeds/system/architecture](/specs/YU04-durable-control-feeds/system/architecture)
- Flows / topology: [/specs/YU04-durable-control-feeds/system/runtime-topology](/specs/YU04-durable-control-feeds/system/runtime-topology)
- Research: [link](/specs/YU04-durable-control-feeds/research)
- Data model: [link](/specs/YU04-durable-control-feeds/data-model)
- Quickstart: [link](/specs/YU04-durable-control-feeds/quickstart)

