---
title: "State YU15-eod-risk-extract: EOD Risk Extract"
---

# State YU15-eod-risk-extract Learning Guide

## Position In Learning Graph

- Previous state(s): [YU14-listed-equity-options](/docs/learning/state-YU14-listed-equity-options)
- Dotted-line parent(s): none
- Next state(s): none

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [code/generated-state-YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU15-eod-risk-extract)
- Authoring branch (spec source): [YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU15-eod-risk-extract)

## Code Comparison With Previous State

- Compare against `YU14-listed-equity-options`: [code/generated-state-YU14-listed-equity-options...code/generated-state-YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU14-listed-equity-options...code%2Fgenerated-state-YU15-eod-risk-extract)

## Plain-English Code Delta

- **Added:** A risk-extract producer whose only trigger is the `eod.pnl.done` event, held on a durable
- **Added:** Idempotent creation of the `TRADERX_EOD` stream at both ends, so the producer need not start
- **Added:** A sequenced risk-extract marker, ordinary cluster ingress that mutates no state, at whose sequence
- **Added:** A malformed marker dropped without advancing any sequence, exactly as an unrecognised input event
- **Added:** Canonical cut rendering — rows ordered by `(accountId, securityId)`, fixed columns, integer ticks,
- **Added:** Leader-only publication of the cut as a single NATS message carrying its own row count, off the
- **Added:** Per-row marks taken from the published closing-price snapshot for the stamped
- **Added:** A snapshot row whose quality is `MISSING` or whose price is null counted as absent, falling

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

