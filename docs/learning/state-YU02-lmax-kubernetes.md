---
title: "State YU02-lmax-kubernetes: LMAX Kubernetes"
---

# State YU02-lmax-kubernetes Learning Guide

## Position In Learning Graph

- Previous state(s): [014-fdc3-intent-interoperability](/docs/learning/state-014-fdc3-intent-interoperability)
- Dotted-line parent(s): none
- Next state(s): [YU03-in-memory-risk-gateway](/docs/learning/state-YU03-in-memory-risk-gateway)

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [code/generated-state-YU02-lmax-kubernetes](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU02-lmax-kubernetes)
- Authoring branch (spec source): [YU15-eod-risk-extract](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/YU15-eod-risk-extract)

## Code Comparison With Previous State

- Compare against `014-fdc3-intent-interoperability`: [code/generated-state-014-fdc3-intent-interoperability...code/generated-state-YU02-lmax-kubernetes](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-014-fdc3-intent-interoperability...code%2Fgenerated-state-YU02-lmax-kubernetes)

## Plain-English Code Delta

- **Added:** FR-LK06 — startup performs snapshot load, journal replay and warm-up replay, and readiness is
- **Added:** FR-LK07 — durable Kubernetes storage and lifecycle rules for journal, snapshot and checkpoint
- **Added:** FR-LK09 — a port matrix and implementation-status document are produced before any claim of
- **Changed:** FR-LK03 — inherited matcher-path internals are replaced by the sequencer and single-writer hot
- **Changed:** FR-LK04 — `order-matcher` becomes the LMAX hot-path node under Kubernetes while keeping its
- **Changed:** FR-LK05 — `trade-service` takes the gateway/receptionist role in front of the hot path.
- **Changed:** FR-LK02 — the Kubernetes, C3 and FDC3 runtime contracts inherited from `014` are preserved; a
- **Removed:** The synchronous per-order database round trip on the admission path, which was the throughput and

## Run This State

```bash
./scripts/start-state-YU02-lmax-kubernetes-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/YU02-lmax-kubernetes](/specs/YU02-lmax-kubernetes)
- Architecture: [/specs/YU02-lmax-kubernetes/system/architecture](/specs/YU02-lmax-kubernetes/system/architecture)
- Flows / topology: [/specs/YU02-lmax-kubernetes/system/runtime-topology](/specs/YU02-lmax-kubernetes/system/runtime-topology)
- Research: [link](/specs/YU02-lmax-kubernetes/research)
- Data model: [link](/specs/YU02-lmax-kubernetes/data-model)
- Quickstart: [link](/specs/YU02-lmax-kubernetes/quickstart)

