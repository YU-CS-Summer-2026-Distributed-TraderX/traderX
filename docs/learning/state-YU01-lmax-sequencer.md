---
title: "State YU01-lmax-sequencer: LMAX Sequencer (Trading Hot Path)"
---

# State YU01-lmax-sequencer Learning Guide

YU lineage. Current integration behavior is described in the [state overview](/docs/engineering/whats-new); historical state branches may differ.

## Position In Learning Graph

- Previous state(s): [009-order-management-matcher](/docs/learning/state-009-order-management-matcher)
- Dotted-line parent(s): none
- Next state(s): none

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU01-lmax-sequencer](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU01-lmax-sequencer)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `009-order-management-matcher`: [code/generated-state-009-order-management-matcher...code/generated-state-YU01-lmax-sequencer](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-009-order-management-matcher...code%2Fgenerated-state-YU01-lmax-sequencer)

## Plain-English Code Delta

- Sequences orders on one in-memory thread with a Disruptor ring buffer and a replayable journal. SQL is a read model.
- **Evidence entrypoints:** Journal and replay tests; test-state-YU01-lmax-sequencer.sh.
- **Boundary:** Local journal recovery is distinct from later replicated consensus.
- [All YU states and additions](/docs/engineering/whats-new).

## Run This State

```bash
./scripts/start-state-YU01-lmax-sequencer-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU01-lmax-sequencer](/specs/YU01-lmax-sequencer)
- Architecture: [/specs/YU01-lmax-sequencer/system/architecture](/specs/YU01-lmax-sequencer/system/architecture)
- Flows / topology: [/specs/YU01-lmax-sequencer/system/runtime-topology](/specs/YU01-lmax-sequencer/system/runtime-topology)
- Research: [link](/specs/YU01-lmax-sequencer/research)
- Data model: [link](/specs/YU01-lmax-sequencer/data-model)
- Quickstart: [link](/specs/YU01-lmax-sequencer/quickstart)

