---
title: "State YU06-eod-price-production: EOD Price Production + Overnight Batch Chain"
---

# State YU06-eod-price-production Learning Guide

YU lineage. Current integration behavior is described in the [feature map](/docs/engineering/feature-map); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [YU05-post-trade-compliance](/docs/learning/state-YU05-post-trade-compliance)
- Dotted-line parent(s): none
- Next state(s): [YU07-historical-tick-store](/docs/learning/state-YU07-historical-tick-store)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU06-eod-price-production](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU06-eod-price-production)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU05-post-trade-compliance`: [code/generated-state-YU05-post-trade-compliance...code/generated-state-YU06-eod-price-production](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU05-post-trade-compliance...code%2Fgenerated-state-YU06-eod-price-production)

## Plain-English Code Delta

- Closes a versioned EOD price snapshot, checks mark quality and publishes an overnight P&L chain.
- **Evidence entrypoints:** EodStreamRepairIT; EodSnapshotAndPnlIT.
- **Boundary:** Stale or missing marks require an explicit decision; arrival freshness is not market observation time.
- [Full feature and component map](/docs/engineering/feature-map).

## Run This State

```bash
./scripts/start-state-YU06-eod-price-production-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU06-eod-price-production](/specs/YU06-eod-price-production)
- Architecture: [/specs/YU06-eod-price-production/system/architecture](/specs/YU06-eod-price-production/system/architecture)
- Flows / topology: [/specs/YU06-eod-price-production/system/runtime-topology](/specs/YU06-eod-price-production/system/runtime-topology)
- Research: [link](/specs/YU06-eod-price-production/research)
- Data model: [link](/specs/YU06-eod-price-production/data-model)
- Quickstart: [link](/specs/YU06-eod-price-production/quickstart)

