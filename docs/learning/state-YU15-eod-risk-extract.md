---
title: "State YU15-eod-risk-extract: EOD Risk Extract"
---

# State YU15-eod-risk-extract Learning Guide

YU lineage. Current integration behavior is described in the [feature map](/docs/engineering/feature-map); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [YU14-listed-equity-options](/docs/learning/state-YU14-listed-equity-options)
- Dotted-line parent(s): none
- Next state(s): [YU16-cdm-instruments](/docs/learning/state-YU16-cdm-instruments)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU15-eod-risk-extract)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU14-listed-equity-options`: [code/generated-state-YU14-listed-equity-options...code/generated-state-YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU14-listed-equity-options...code%2Fgenerated-state-YU15-eod-risk-extract)

## Plain-English Code Delta

- Exports un-netted positions and counterparties at one consensus cut with reproducible bytes and receipt identity.
- **Evidence entrypoints:** RiskExtractTest; RiskReplayDeterminismTest; SharedEodExamplesTest.
- **Boundary:** An extract is an input to a risk engine, not a computed portfolio-risk result.
- [Full feature and component map](/docs/engineering/feature-map).

## Run This State

```bash
./scripts/start-state-YU15-eod-risk-extract-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU15-eod-risk-extract](/specs/YU15-eod-risk-extract)
- Architecture: [/specs/YU15-eod-risk-extract/system/architecture](/specs/YU15-eod-risk-extract/system/architecture)
- Flows / topology: [/specs/YU15-eod-risk-extract/system/runtime-topology](/specs/YU15-eod-risk-extract/system/runtime-topology)
- Research: [link](/specs/YU15-eod-risk-extract/research)
- Data model: [link](/specs/YU15-eod-risk-extract/data-model)
- Quickstart: [link](/specs/YU15-eod-risk-extract/quickstart)

