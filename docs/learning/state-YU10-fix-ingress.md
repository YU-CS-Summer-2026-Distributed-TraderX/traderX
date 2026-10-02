---
title: "State YU10-fix-ingress: FIX Order-Entry Ingress"
---

# State YU10-fix-ingress Learning Guide

YU lineage. Current integration behavior is described in the [state overview](/docs/engineering/whats-new); historical state branches may differ.

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

## Catalogued Branches

- Catalogued state branch: [code/generated-state-YU10-fix-ingress](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/code/generated-state-YU10-fix-ingress)
- Authoring branch (spec source): [traderX-risk-integration](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/tree/traderX-risk-integration)

## Code Comparison With Previous State

- Compare against `YU09-ops-hardening`: [code/generated-state-YU09-ops-hardening...code/generated-state-YU10-fix-ingress](https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/compare/code%2Fgenerated-state-YU09-ops-hardening...code%2Fgenerated-state-YU10-fix-ingress)

## Plain-English Code Delta

- Maps FIX 4.4 sessions, order entry, cancellation and status onto the sequenced trading path.
- **Evidence entrypoints:** FixSessionIntegrationTest; FixGatewayStatusTest.
- **Boundary:** Typed order fields and accepted units follow the current order-type contract.
- [All YU states and additions](/docs/engineering/whats-new).

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

