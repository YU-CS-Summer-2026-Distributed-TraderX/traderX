---
title: "State YU16-cdm-instruments: CDM Instruments"
---

# State YU16-cdm-instruments Learning Guide

YU lineage. Current integration behavior is described in the [feature map](/docs/engineering/feature-map); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [YU15-eod-risk-extract](/docs/learning/state-YU15-eod-risk-extract)
- Dotted-line parent(s): none
- Next state(s): [YU17-otc-rates](/docs/learning/state-YU17-otc-rates)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU16-cdm-instruments](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU16-cdm-instruments)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU15-eod-risk-extract`: [code/generated-state-YU15-eod-risk-extract...code/generated-state-YU16-cdm-instruments](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU15-eod-risk-extract...code%2Fgenerated-state-YU16-cdm-instruments)

## Plain-English Code Delta

- Adds CDM instrument representation and Treasury/corporate-bond terms and pricing inputs.
- **Evidence entrypoints:** test-state-YU16-cdm-instruments.sh; price-publisher tests.
- **Boundary:** Instrument-level failures do not justify substituting invented marks; per-instrument support differs.
- [Full feature and component map](/docs/engineering/feature-map).

## Run This State

```bash
./scripts/start-state-YU16-cdm-instruments-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU16-cdm-instruments](/specs/YU16-cdm-instruments)
- Architecture: [/specs/YU16-cdm-instruments/system/architecture](/specs/YU16-cdm-instruments/system/architecture)
- Flows / topology: [/specs/YU16-cdm-instruments/system/runtime-topology](/specs/YU16-cdm-instruments/system/runtime-topology)
- Research: [link](/specs/YU16-cdm-instruments/research)
- Data model: [link](/specs/YU16-cdm-instruments/data-model)
- Quickstart: [link](/specs/YU16-cdm-instruments/quickstart)

