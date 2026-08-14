---
title: "State YU16-cdm-instruments: CDM Instruments"
---

# State YU16-cdm-instruments Learning Guide

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

## Rendered Code

- Generated branch: [code/generated-state-YU16-cdm-instruments](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU16-cdm-instruments)
- Authoring branch (spec source): [YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU15-eod-risk-extract)

## Code Comparison With Previous State

- Compare against `YU15-eod-risk-extract`: [code/generated-state-YU15-eod-risk-extract...code/generated-state-YU16-cdm-instruments](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU15-eod-risk-extract...code%2Fgenerated-state-YU16-cdm-instruments)

## Plain-English Code Delta

- **Added:** A CDM-shaped instrument model on reference-data: `GET /instruments` and
- **Added:** Ten instruments in the seed universe: five ETFs (`SPY, QQQ, IWM, VTI, GLD` —
- **Added:** `/instruments/control-snapshot` — the general-name control snapshot, identical contract over
- **Added:** Treasury pricing in price-publisher: term-profiled correlated walk with mean reversion and a
- **Added:** Face-amount order validation for `UST-` keys at the cluster gateway REST boundary and in the
- **Added:** Treasury booking semantics in trade-processor: face-weighted average cost, `Rejected` trade
- **Added:** Extract enrichment by join: `instrumentType` gains `TREASURY`, Treasury rows carry `coupon`
- **Added:** Extract accrued interest by derivation (ADR-061): Treasury rows additionally carry

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

