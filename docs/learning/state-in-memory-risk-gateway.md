---
title: "State in-memory-risk-gateway: In-Memory Risk Gateway"
---

# State in-memory-risk-gateway Learning Guide

## Position In Learning Graph

- Previous state(s): [009b-lmax-sequencer-architecture](/docs/learning/state-009b-lmax-sequencer-architecture)
- Dotted-line parent(s): none
- Next state(s): none

## Convergence Metadata

- Convergence state: `no`
- Convergence level: `none`
- Lineage role: `optional`
- Nearest previous convergence: `none`
- Nearest next convergence: `none`

## Rendered Code

- Generated branch: [code/generated-state-in-memory-risk-gateway](https://github.com/finos/traderX/tree/code/generated-state-in-memory-risk-gateway)
- Authoring branch (spec source): [main](https://github.com/finos/traderX/tree/main)

## Code Comparison With Previous State

- Compare against `009b-lmax-sequencer-architecture`: [code/generated-state-009b-lmax-sequencer-architecture...code/generated-state-in-memory-risk-gateway](https://github.com/finos/traderX/compare/code%2Fgenerated-state-009b-lmax-sequencer-architecture...code%2Fgenerated-state-in-memory-risk-gateway)

## Plain-English Code Delta

- **Added:** Versioned Gateway replicas for security identity/status, account status, principal entitlements,
- **Added:** Gap-free bootstrap protocol: subscribe and buffer deltas, fetch a complete snapshot at watermark
- **Added:** Local Gateway screening for entitlement, security status, restrictions, kill switches, price
- **Added:** Sequenced control events for account, entitlement, security, restriction, policy, and kill-switch
- **Added:** Authoritative BLP aggregate-risk checks against exact positions, open-order reservations, and policy
- **Added:** Required `clientOrderId`, bounded idempotency state, and original-decision replay on retry
- **Added:** Stable decision contract carrying decision/reason, command sequence, policy version, and control
- **Added:** Exposure reservation lifecycle: reserve on acceptance, convert on fill, release on cancel/reject/

## Run This State

```bash
./scripts/start-state-in-memory-risk-gateway-generated.sh
```

## Canonical Spec Links

- State spec pack: [/specs/in-memory-risk-gateway](/specs/in-memory-risk-gateway)
- Architecture: [/specs/in-memory-risk-gateway/system/architecture](/specs/in-memory-risk-gateway/system/architecture)
- Flows / topology: [/specs/in-memory-risk-gateway/system/runtime-topology](/specs/in-memory-risk-gateway/system/runtime-topology)
- Research: [link](/specs/in-memory-risk-gateway/research)
- Data model: [link](/specs/in-memory-risk-gateway/data-model)
- Quickstart: [link](/specs/in-memory-risk-gateway/quickstart)

