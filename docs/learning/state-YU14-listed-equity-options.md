---
title: "State YU14-listed-equity-options: Listed Equity Options"
---

# State YU14-listed-equity-options Learning Guide

YU lineage. Current integration behavior is described in the [feature map](/docs/engineering/feature-map); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [YU13-limit-order-book](/docs/learning/state-YU13-limit-order-book)
- Dotted-line parent(s): none
- Next state(s): [YU15-eod-risk-extract](/docs/learning/state-YU15-eod-risk-extract)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU14-listed-equity-options](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU14-listed-equity-options)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU13-limit-order-book`: [code/generated-state-YU13-limit-order-book...code/generated-state-YU14-listed-equity-options](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU13-limit-order-book...code%2Fgenerated-state-YU14-listed-equity-options)

## Plain-English Code Delta

- Trades listed equity options through the same book and applies contract multipliers to exposure.
- **Evidence entrypoints:** test-state-YU14-listed-equity-options.sh; option persistence proof.
- **Boundary:** Trading support is distinct from option valuation, Greeks or exercise processing.
- [Full feature and component map](/docs/engineering/feature-map).

## Run This State

```bash
./scripts/start-state-YU14-listed-equity-options-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU14-listed-equity-options](/specs/YU14-listed-equity-options)
- Architecture: [/specs/YU14-listed-equity-options/system/architecture](/specs/YU14-listed-equity-options/system/architecture)
- Flows / topology: [/specs/YU14-listed-equity-options/system/runtime-topology](/specs/YU14-listed-equity-options/system/runtime-topology)
- Research: [link](/specs/YU14-listed-equity-options/research)
- Data model: [link](/specs/YU14-listed-equity-options/data-model)
- Quickstart: [link](/specs/YU14-listed-equity-options/quickstart)

