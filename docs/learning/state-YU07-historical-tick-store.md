---
title: "State YU07-historical-tick-store: Historical Tick Store"
---

# State YU07-historical-tick-store Learning Guide

YU lineage. Current integration behavior is described in the [feature map](/docs/engineering/feature-map); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [YU06-eod-price-production](/docs/learning/state-YU06-eod-price-production)
- Dotted-line parent(s): none
- Next state(s): [YU08-execution-algo-engine](/docs/learning/state-YU08-execution-algo-engine)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU07-historical-tick-store](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU07-historical-tick-store)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU06-eod-price-production`: [code/generated-state-YU06-eod-price-production...code/generated-state-YU07-historical-tick-store](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU06-eod-price-production...code%2Fgenerated-state-YU07-historical-tick-store)

## Plain-English Code Delta

- Stores historical ticks, queries symbol/time windows and supports replay. Later kdb+/q capture adds engine order and trade history.
- **Evidence entrypoints:** selfcheck.q; txselfcheck.q; test-state-YU07-historical-tick-store.sh.
- **Boundary:** Historical datasets and licensed raw data are not distributed with the documentation.
- [Full feature and component map](/docs/engineering/feature-map).

## Run This State

```bash
./scripts/start-state-YU07-historical-tick-store-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU07-historical-tick-store](/specs/YU07-historical-tick-store)
- Architecture: [/specs/YU07-historical-tick-store/system/architecture](/specs/YU07-historical-tick-store/system/architecture)
- Flows / topology: [/specs/YU07-historical-tick-store/system/runtime-topology](/specs/YU07-historical-tick-store/system/runtime-topology)
- Research: [link](/specs/YU07-historical-tick-store/research)
- Data model: [link](/specs/YU07-historical-tick-store/data-model)
- Quickstart: [link](/specs/YU07-historical-tick-store/quickstart)

