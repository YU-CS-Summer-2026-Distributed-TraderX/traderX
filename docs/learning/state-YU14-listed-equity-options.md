---
title: "State YU14-listed-equity-options: Listed Equity Options"
---

# State YU14-listed-equity-options Learning Guide

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

## Rendered Code

- Generated branch: [code/generated-state-YU14-listed-equity-options](https://github.com/finos/traderX/tree/code/generated-state-YU14-listed-equity-options)
- Authoring branch (spec source): [main](https://github.com/finos/traderX/tree/main)

## Code Comparison With Previous State

- Compare against `YU13-limit-order-book`: [code/generated-state-YU13-limit-order-book...code/generated-state-YU14-listed-equity-options](https://github.com/finos/traderX/compare/code%2Fgenerated-state-YU13-limit-order-book...code%2Fgenerated-state-YU14-listed-equity-options)

## Plain-English Code Delta

- **Added:** FR-LEO01 — option contracts are securities identified by unpadded OCC symbols; they trade
- **Added:** FR-LEO02 — deterministic multiplier derivation at symbol registration (option → 100,
- **Added:** FR-LEO03 — all risk-gate notional math (reserve, market trade, executed exposure,
- **Added:** FR-LEO04 — the multiplier is cluster state: format-3 snapshot security records carry it, and
- **Added:** FR-LEO05 — strike/expiry/call-put/underlying and counterparty/netting-set never enter the
- **Added:** FR-LEO06 — instrument currency (USD) and derived position notional
- **Added:** FR-LEO07 — the SBE symbol-registration ticker field carries at least 19 ASCII characters.
- **Changed:** Snapshot format identifier 2 → 3: the security record gains the multiplier column; a format-2

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

