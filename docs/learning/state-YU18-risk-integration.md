---
title: "State YU18-risk-integration: Risk Integration"
---

# State YU18-risk-integration Learning Guide

## Position In Learning Graph

- Previous state(s): [YU17-otc-rates](/docs/learning/state-YU17-otc-rates)
- Dotted-line parent(s): none
- Next state(s): none

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [code/generated-state-YU18-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU18-risk-integration)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU17-otc-rates`: [code/generated-state-YU17-otc-rates...code/generated-state-YU18-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU17-otc-rates...code%2Fgenerated-state-YU18-risk-integration)

## Plain-English Code Delta

- **Delta:** FR-EB01: The state SHALL inherit YU17-otc-rates and preserve its two export schemas byte for byte.
- **Delta:** FR-EB02: The builder SHALL require matching consensus sequence, session date, price snapshot version and cut hash across artifacts.
- **Delta:** FR-EB03: The manifest SHALL include caller-supplied cluster epoch, offset-aware valuation time, input origin, file hashes, schemas and counts.
- **Delta:** FR-EB04: The bundle ID SHALL hash the canonical manifest body excluding bundleId.
- **Delta:** FR-EB05: Validation SHALL reject malformed headers, missing provenance, invalid numeric/date values, duplicate row identities and manifest disagreement.
- **Delta:** FR-EB06: Publication SHALL stage files privately and refuse existing output destinations.
- **Delta:** FR-EB07: The mock SHALL validate its input and echo every position/contract identity with null NPV, empty Greeks, NOT_PRICED and MOCK_ONLY.
- **Delta:** FR-EB08: The result SHALL identify its input bundle and explicitly declare synthetic=true, usableForRisk=false and priced coverage zero.

## Run This State

```bash
python3 eod-risk-bundles/bundle.py --help
```

## Canonical Spec Links

- State spec pack: [/specs/YU18-risk-integration](/specs/YU18-risk-integration)
- Architecture: [/specs/YU18-risk-integration/system/architecture](/specs/YU18-risk-integration/system/architecture)
- Flows / topology: [/specs/YU18-risk-integration/system/runtime-topology](/specs/YU18-risk-integration/system/runtime-topology)
- Research: [link](/specs/YU18-risk-integration/research)
- Data model: [link](/specs/YU18-risk-integration/data-model)
- Quickstart: [link](/specs/YU18-risk-integration/quickstart)

