---
title: "State YU13-limit-order-book: Crossing Limit-Order Book"
---

# State YU13-limit-order-book Learning Guide

## Position In Learning Graph

- Previous state(s): [YU12-aeron-cluster](/docs/learning/state-YU12-aeron-cluster)
- Dotted-line parent(s): none
- Next state(s): [YU14-listed-equity-options](/docs/learning/state-YU14-listed-equity-options)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [YU13-limit-order-book](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU13-limit-order-book)
- Authoring branch (spec source): [YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU15-eod-risk-extract)

## Code Comparison With Previous State

- Compare against `YU12-aeron-cluster`: [YU12-aeron-cluster...YU13-limit-order-book](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/YU12-aeron-cluster...YU13-limit-order-book)

## Plain-English Code Delta

- **Added:** A two-sided limit-order book per security; an accepted limit order that does not cross rests at
- **Added:** Price-time priority crossing: a marketable order fills against resting opposite-side orders
- **Added:** Each match step fills `min(aggressor remaining, resting remaining)`, so partial fills leave the
- **Added:** Both sides of every match receive an order update, a booked trade with its own trade sequence
- **Added:** The resting side's order update carries `FLAG_RESTING_UPDATE`, so gateway ack correlation can
- **Added:** Market orders (no limit price) execute immediately against available depth and cancel any
- **Added:** Risk validation of a market order prices it at the last trade price, falling back to the opposite
- **Added:** A leader-side `/orders` order-lifecycle bridge feeding the `orderbook` SQL projection and

## Run This State

```bash
inherits YU12-aeron-cluster runtime harness
```

## Canonical Spec Links

- State spec pack: [/specs/YU13-limit-order-book](/specs/YU13-limit-order-book)
- Architecture: [/specs/YU13-limit-order-book/system/architecture](/specs/YU13-limit-order-book/system/architecture)
- Flows / topology: [/specs/YU13-limit-order-book/system/runtime-topology](/specs/YU13-limit-order-book/system/runtime-topology)
- Research: [link](/specs/YU13-limit-order-book/research)
- Data model: [link](/specs/YU13-limit-order-book/data-model)
- Quickstart: [link](/specs/YU13-limit-order-book/quickstart)

