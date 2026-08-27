---
title: "State YU17-otc-rates: OTC Interest-Rate Swaps"
---

# State YU17-otc-rates Learning Guide

## Position In Learning Graph

- Previous state(s): [YU16-cdm-instruments](/docs/learning/state-YU16-cdm-instruments)
- Dotted-line parent(s): none
- Next state(s): none

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [code/generated-state-YU17-otc-rates](https://github.com/finos/traderX/tree/code/generated-state-YU17-otc-rates)
- Authoring branch (spec source): [main](https://github.com/finos/traderX/tree/main)

## Code Comparison With Previous State

- Compare against `YU16-cdm-instruments`: [code/generated-state-YU16-cdm-instruments...code/generated-state-YU17-otc-rates](https://github.com/finos/traderX/compare/code%2Fgenerated-state-YU16-cdm-instruments...code%2Fgenerated-state-YU17-otc-rates)

## Plain-English Code Delta

- **Added:** A **session phase machine** in consensus — `CLOSED`, `PRE_OPEN`, `OPEN` — sequenced and snapshotted.
- **Added:** A **price band that follows the market**, anchored on the reference rather than on a book's first order.
- **Added:** A **price-derived book grid**: an empty book takes its tick size from the reference price, by decade.
- **Added:** An **external reference replayed on a stateless clock**, resampled offline from a licensed historical tape.
- **Added:** **Replayed prints entering as order flow**, sampled to a target rate and matched as ordinary orders.
- **Added:** `GET /bbo` on each member: best bid, offer and mark derived from the book and served beside consensus.
- **Added:** `POST /swaps` on the cluster gateway: books a vanilla fixed-float OTC interest-rate swap.
- **Added:** `TYPE_SWAP_BOOK` (12) on the inherited `InputEventMessage` (SBE template 1): a sequenced consensus command.

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

