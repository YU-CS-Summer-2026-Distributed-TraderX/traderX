---
title: "State YU13-limit-order-book: Crossing Limit-Order Book"
---

# State YU13-limit-order-book Learning Guide

YU lineage. Current integration behavior is described in the [state overview](/docs/engineering/whats-new); historical state branches may differ.

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

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU13-limit-order-book](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU13-limit-order-book)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU12-aeron-cluster`: [code/generated-state-YU12-aeron-cluster...code/generated-state-YU13-limit-order-book](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU12-aeron-cluster...code%2Fgenerated-state-YU13-limit-order-book)

## Plain-English Code Delta

- Matches by price and time, fills at resting prices, supports cancel and atomic replace, and snapshots the resting book.
- **Evidence entrypoints:** LimitOrderBookTest; ClOrdIdLedgerTest; OrderTraceTest.
- **Boundary:** Current YU18 adds typed orders and cross-account self-match groups. Tracing drops observations rather than blocking trading.
- [All YU states and additions](/docs/engineering/whats-new).

## Run This State

```bash
./scripts/start-state-YU13-limit-order-book-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU13-limit-order-book](/specs/YU13-limit-order-book)
- Architecture: [/specs/YU13-limit-order-book/system/architecture](/specs/YU13-limit-order-book/system/architecture)
- Flows / topology: [/specs/YU13-limit-order-book/system/runtime-topology](/specs/YU13-limit-order-book/system/runtime-topology)
- Research: [link](/specs/YU13-limit-order-book/research)
- Data model: [link](/specs/YU13-limit-order-book/data-model)
- Quickstart: [link](/specs/YU13-limit-order-book/quickstart)

