---
title: "State YU05-post-trade-compliance: Post-Trade Compliance Bundle"
---

# State YU05-post-trade-compliance Learning Guide

YU lineage. Current integration behavior is described in the [state overview](/docs/engineering/whats-new); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [YU04-durable-control-feeds](/docs/learning/state-YU04-durable-control-feeds)
- Dotted-line parent(s): none
- Next state(s): [YU06-eod-price-production](/docs/learning/state-YU06-eod-price-production)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU05-post-trade-compliance](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU05-post-trade-compliance)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU04-durable-control-feeds`: [code/generated-state-YU04-durable-control-feeds...code/generated-state-YU05-post-trade-compliance](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU04-durable-control-feeds...code%2Fgenerated-state-YU05-post-trade-compliance)

## Plain-English Code Delta

- Adds settlement states, journal-to-SQL reconciliation, regulatory export, transaction-cost analysis and scoped post-trade access.
- **Evidence entrypoints:** SettlementServiceTest; ReconciliationServiceTest; RegulatoryReportDeterminismTest.
- **Boundary:** Post-trade JWT checks do not establish production authentication for the new Desk.
- [All YU states and additions](/docs/engineering/whats-new).

## Run This State

```bash
./scripts/start-state-YU05-post-trade-compliance-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU05-post-trade-compliance](/specs/YU05-post-trade-compliance)
- Architecture: [/specs/YU05-post-trade-compliance/system/architecture](/specs/YU05-post-trade-compliance/system/architecture)
- Flows / topology: [/specs/YU05-post-trade-compliance/system/runtime-topology](/specs/YU05-post-trade-compliance/system/runtime-topology)
- Research: [link](/specs/YU05-post-trade-compliance/research)
- Data model: [link](/specs/YU05-post-trade-compliance/data-model)
- Quickstart: [link](/specs/YU05-post-trade-compliance/quickstart)

