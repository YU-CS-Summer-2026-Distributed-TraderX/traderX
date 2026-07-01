---
title: "State 009b: LMAX Sequencer Architecture (Trading Hot Path)"
---

# State 009b Learning Guide

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

## Rendered Code

- Generated branch: [code/generated-state-009b-lmax-sequencer-architecture](https://github.com/finos/traderX/tree/code/generated-state-009b-lmax-sequencer-architecture)
- Authoring branch (spec source): [main](https://github.com/finos/traderX/tree/main)

## Code Comparison With Previous State

- Compare against `009-order-management-matcher`: [code/generated-state-009-order-management-matcher...code/generated-state-009b-lmax-sequencer-architecture](https://github.com/finos/traderX/compare/code%2Fgenerated-state-009-order-management-matcher...code%2Fgenerated-state-009b-lmax-sequencer-architecture)

## Plain-English Code Delta

- **Added:** Single sequenced input stream: all state-mutating inputs (order create/cancel/force-fill, price
- **Added:** Parallel input handlers — Journaler (durable append), Replicator (replica/DR stream), Un-marshaller
- **Added:** Single-threaded, in-memory, event-sourced Business Logic Processor fusing matching + trade booking +
- **Added:** Typed output events (`OrderAccepted|Rejected|PartiallyFilled|Filled|Canceled`, `TradeBooked`,
- **Added:** Asynchronous request/response event pattern for BLP cache misses (e.g.
- **Added:** Event sourcing operability: periodic full-state snapshot (`snapshot.dat`) + bounded journal-tail replay
- **Added:** Replication and warm-standby failover: follower BLPs consume the identical replicated input stream in
- **Added:** Optional batch ingress: `POST /orders/batch` accepts an array of new orders and sequences the whole

## Run This State

```bash
./scripts/start-state-009b-lmax-sequencer-architecture-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/lmax-sequencer-architecture](/specs/lmax-sequencer-architecture)
- Architecture: [/specs/lmax-sequencer-architecture/system/architecture](/specs/lmax-sequencer-architecture/system/architecture)
- Flows / topology: [/specs/lmax-sequencer-architecture/system/runtime-topology](/specs/lmax-sequencer-architecture/system/runtime-topology)
- Research: [link](/specs/lmax-sequencer-architecture/research)
- Data model: [link](/specs/lmax-sequencer-architecture/data-model)
- Quickstart: [link](/specs/lmax-sequencer-architecture/quickstart)

