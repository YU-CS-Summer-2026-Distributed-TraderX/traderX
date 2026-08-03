---
title: "State YU10-fix-ingress: FIX Order-Entry Ingress"
---

# State YU10-fix-ingress Learning Guide

## Position In Learning Graph

- Previous state(s): [YU09-ops-hardening](/docs/learning/state-YU09-ops-hardening)
- Dotted-line parent(s): none
- Next state(s): [YU11-aeron-replication](/docs/learning/state-YU11-aeron-replication)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [code/generated-state-YU10-fix-ingress](https://github.com/finos/traderX/tree/code/generated-state-YU10-fix-ingress)
- Authoring branch (spec source): [main](https://github.com/finos/traderX/tree/main)

## Code Comparison With Previous State

- Compare against `YU09-ops-hardening`: [code/generated-state-YU09-ops-hardening...code/generated-state-YU10-fix-ingress](https://github.com/finos/traderX/compare/code%2Fgenerated-state-YU09-ops-hardening...code%2Fgenerated-state-YU10-fix-ingress)

## Plain-English Code Delta

- **Added:** **FIX 4.4 acceptor** (in-process, port 18130): sessions authenticated at logon
- **Added:** **Order entry over FIX**: `NewOrderSingle` and `OrderCancelRequest` translate to the exact
- **Added:** **Order state over FIX**: `OrderStatusRequest` answered from the in-memory read model.
- **Added:** **Asynchronous ExecutionReports**: a dedicated output-disruptor handler translates lifecycle
- **Added:** **Durable correlation ledger** binding (session, ClOrdID) ↔ (inputSeq, orderRef): duplicate
- **Added:** **Deterministic outcome semantics**: the four-outcome admission model; ambiguous post-publish

## Run This State

```bash
./scripts/start-state-YU10-fix-ingress-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU10-fix-ingress](/specs/YU10-fix-ingress)
- Architecture: [/specs/YU10-fix-ingress/system/architecture](/specs/YU10-fix-ingress/system/architecture)
- Flows / topology: [/specs/YU10-fix-ingress/system/runtime-topology](/specs/YU10-fix-ingress/system/runtime-topology)
- Research: [link](/specs/YU10-fix-ingress/research)
- Data model: [link](/specs/YU10-fix-ingress/data-model)
- Quickstart: [link](/specs/YU10-fix-ingress/quickstart)

