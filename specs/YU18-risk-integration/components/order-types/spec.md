# Order types specification

Updated: 2026-09-23. Status: planning scaffold, not an implementation-ready contract.

## Scope

Stop and stop-limit orders, then separately planned iceberg, pegged and trailing-stop support. Audit existing market/limit and time-in-force behavior before changing it.

## Requirements to specify

Define trigger source, gap behavior, price/time priority, cancel/replace, partial fill and risk-reservation semantics; cover deterministic replay and snapshots. Decide true NBBO versus explicitly labeled local-book pegging before implementing pegged orders.

Use FR-OT, NFR-OT and SC-OT identifiers when expanding this document. Preserve inherited behavior and published contracts. [Existing reference](../../../YU13-limit-order-book/spec.md).

## Acceptance boundary

Before implementation, enumerate observable positive, negative and recovery cases with exact inputs and expected behavior. Completion requires source tests and generated/runtime checks appropriate to the change. No financial, distributed or live claim follows from a structural scaffold.
