---
title: "State YU04-durable-control-feeds: Durable Control Feeds"
---

# State YU04-durable-control-feeds Learning Guide

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

## Rendered Code

- Generated branch: [code/generated-state-YU04-durable-control-feeds](https://github.com/finos/traderX/tree/code/generated-state-YU04-durable-control-feeds)
- Authoring branch (spec source): [main](https://github.com/finos/traderX/tree/main)

## Code Comparison With Previous State

- Compare against `YU03-in-memory-risk-gateway`: [code/generated-state-YU03-in-memory-risk-gateway...code/generated-state-YU04-durable-control-feeds](https://github.com/finos/traderX/compare/code%2Fgenerated-state-YU03-in-memory-risk-gateway...code%2Fgenerated-state-YU04-durable-control-feeds)

## Plain-English Code Delta

- **Added:** Transactional outbox in `account-service` and `reference-data`: each control change is written in the
- **Added:** Two durable NATS JetStream streams of versioned control deltas, `TRADERX_CONTROL_ACCOUNT` and
- **Added:** Retention and replay on those streams, so a control change made while a replica is briefly offline is
- **Added:** Watermarked snapshot endpoints `GET /account/control-snapshot` and `GET /stocks/control-snapshot`,
- **Added:** A five-step bootstrap per source in `order-matcher`: subscribe and buffer, fetch the snapshot, verify
- **Added:** Real per-source epoch and monotonic version on every control record, so the replica can tell a gap, a
- **Added:** Quarantine and automatic re-bootstrap: a gap, regression, epoch change or failed checksum stops
- **Added:** Per-source observability — `traderx_replica_source_watermark` and `traderx_replica_quarantine_total`,

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

