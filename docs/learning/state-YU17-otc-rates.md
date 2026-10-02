---
title: "State YU17-otc-rates: OTC Interest-Rate Swaps"
---

# State YU17-otc-rates Learning Guide

YU lineage. Current integration behavior is described in the [state overview](/docs/engineering/whats-new); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [YU16-cdm-instruments](/docs/learning/state-YU16-cdm-instruments)
- Dotted-line parent(s): none
- Next state(s): [YU18-risk-integration](/docs/learning/state-YU18-risk-integration)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU17-otc-rates](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU17-otc-rates)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU16-cdm-instruments`: [code/generated-state-YU16-cdm-instruments...code/generated-state-YU17-otc-rates](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU16-cdm-instruments...code%2Fgenerated-state-YU17-otc-rates)

## Plain-English Code Delta

- Books OTC swaps and swaptions beside the matching book in the same consensus log. Adds reference-anchored price bands, a price-derived grid, sequenced CLOSED/PRE_OPEN/OPEN phases, historical tape replay and operator-scoped counters.
- **Evidence entrypoints:** test-state-YU17-otc-rates.sh; OTC contract tests.
- **Boundary:** Contracts export terms without valuation. Historical replay is not live data or a backtest; tick-rule sides are inferred. Active configuration may instead use an explicitly synthetic offline feed.
- [All YU states and additions](/docs/engineering/whats-new).

## Run This State

```bash
./scripts/start-state-YU17-otc-rates-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU17-otc-rates](/specs/YU17-otc-rates)
- Architecture: [/specs/YU17-otc-rates/system/architecture](/specs/YU17-otc-rates/system/architecture)
- Flows / topology: [/specs/YU17-otc-rates/system/runtime-topology](/specs/YU17-otc-rates/system/runtime-topology)
- Research: [link](/specs/YU17-otc-rates/research)
- Data model: [link](/specs/YU17-otc-rates/data-model)
- Quickstart: [link](/specs/YU17-otc-rates/quickstart)

