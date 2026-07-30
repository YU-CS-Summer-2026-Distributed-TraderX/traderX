---
title: "State YU03-in-memory-risk-gateway: In-Memory Risk Gateway"
---

# State YU03-in-memory-risk-gateway Learning Guide

## Position In Learning Graph

- Previous state(s): [YU02-lmax-kubernetes](/docs/learning/state-YU02-lmax-kubernetes)
- Dotted-line parent(s): none
- Next state(s): [YU04-durable-control-feeds](/docs/learning/state-YU04-durable-control-feeds)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [YU03-in-memory-risk-gateway](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU03-in-memory-risk-gateway)
- Authoring branch (spec source): [YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU15-eod-risk-extract)

## Code Comparison With Previous State

- Compare against `YU02-lmax-kubernetes`: [YU02-lmax-kubernetes-blp-ha...YU03-in-memory-risk-gateway](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/YU02-lmax-kubernetes-blp-ha...YU03-in-memory-risk-gateway)

## Plain-English Code Delta

- **Added:** An in-process Gateway replica that screens every order, batch, and market trade against account
- **Added:** An authoritative decision in the single-writer BLP that repeats every mutable and aggregate check
- **Added:** A fixed decision precedence — kill switch, account, security, restriction, quantity, price
- **Added:** Checking and reserving as one single-threaded BLP operation before book entry, so aggregate
- **Added:** Exposure reservation on accept: an order reserves `quantity × limitPx` against its account,
- **Added:** Versioned control events for accounts, securities, policy, and restrictions travelling the same
- **Added:** Losing the control plane leaves the command path running on installed local state — no fallback
- **Added:** Replay from snapshot plus journal reproducing every past acceptance and rejection identically,

## Run This State

```bash
inherits YU02-lmax-kubernetes runtime harness
```

## Canonical Spec Links

- State spec pack: [/specs/YU03-in-memory-risk-gateway](/specs/YU03-in-memory-risk-gateway)
- Architecture: [/specs/YU03-in-memory-risk-gateway/system/architecture](/specs/YU03-in-memory-risk-gateway/system/architecture)
- Flows / topology: [/specs/YU03-in-memory-risk-gateway/system/system-context](/specs/YU03-in-memory-risk-gateway/system/system-context)
- Research: [link](/specs/YU03-in-memory-risk-gateway/research)
- Data model: `n/a`
- Quickstart: `n/a`

