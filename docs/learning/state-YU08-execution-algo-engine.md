---
title: "State YU08-execution-algo-engine: Execution Algo Engine"
---

# State YU08-execution-algo-engine Learning Guide

## Position In Learning Graph

- Previous state(s): [YU07-historical-tick-store](/docs/learning/state-YU07-historical-tick-store)
- Dotted-line parent(s): none
- Next state(s): [YU09-ops-hardening](/docs/learning/state-YU09-ops-hardening)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [code/generated-state-YU08-execution-algo-engine](https://github.com/finos/traderX/tree/code/generated-state-YU08-execution-algo-engine)
- Authoring branch (spec source): [main](https://github.com/finos/traderX/tree/main)

## Code Comparison With Previous State

- Compare against `YU07-historical-tick-store`: [code/generated-state-YU07-historical-tick-store...code/generated-state-YU08-execution-algo-engine](https://github.com/finos/traderX/compare/code%2Fgenerated-state-YU07-historical-tick-store...code%2Fgenerated-state-YU08-execution-algo-engine)

## Plain-English Code Delta

- **Added:** An `execution-algo-engine` service that accepts a parent order — account, security, side,
- **Added:** TWAP scheduling, which splits the parent quantity into equally sized time buckets and puts any
- **Added:** VWAP scheduling, which sizes each bucket by weights supplied by a pluggable volume-profile source
- **Added:** Two volume-profile sources: a synthetic U-shaped intraday curve that needs no market data, and a
- **Added:** Automatic fallback to the synthetic weights when the DuckDB source finds no matching history for a
- **Added:** Child-order submission through the matching engine's existing `POST /orders` endpoint with a
- **Added:** Fill tracking that subscribes to the existing `/accounts/*/orders` broadcast and correlates
- **Added:** Progress queries over `GET /algo/orders/{parentOrderId}` and `GET /algo/orders`, showing buckets

## Run This State

```bash
./scripts/start-state-YU08-execution-algo-engine-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU08-execution-algo-engine](/specs/YU08-execution-algo-engine)
- Architecture: [/specs/YU08-execution-algo-engine/system/architecture](/specs/YU08-execution-algo-engine/system/architecture)
- Flows / topology: [/specs/YU08-execution-algo-engine/system/runtime-topology](/specs/YU08-execution-algo-engine/system/runtime-topology)
- Research: [link](/specs/YU08-execution-algo-engine/research)
- Data model: [link](/specs/YU08-execution-algo-engine/data-model)
- Quickstart: [link](/specs/YU08-execution-algo-engine/quickstart)

