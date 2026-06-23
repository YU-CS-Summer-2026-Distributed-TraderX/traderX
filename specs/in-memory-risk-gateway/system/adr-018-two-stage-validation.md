# ADR-018: Two-Stage Validation with BLP Authority

**Status:** Accepted for specification  
**Date:** 2026-06-22  
**State:** `in-memory-risk-gateway`

## Context

`009b` removes blocking work from the BLP but leaves producer validation incomplete: market trades
perform remote account/reference calls, while order submission performs only structural checks. Local
Gateway replicas can remove those calls, but multiple Gateways may consume control/position updates at
different times. Two Gateways can therefore observe the same remaining credit headroom and both admit
commands that jointly exceed the limit.

The existing BLP already serializes trading state on one thread and owns exact positions/order state.
It can check and reserve aggregate exposure without a distributed lock or new network hop.

## Decision

Use two validation stages:

1. Gateway replicas perform local preliminary screening for malformed/unauthorized/unknown/stale/
   restricted/obviously over-limit commands and protect admission readiness.
2. The globally sequenced BLP repeats mutable and aggregate-dependent checks, atomically reserves
   exposure, and emits the authoritative acceptance or rejection.

A Gateway pass is never represented as final acceptance. A submitted command is journaled for audit
but becomes executable only after the BLP accepts and reserves it.

## Alternatives Considered

- **Gateway-only authority:** rejected because replicas can lag and concurrent Gateways can overshoot.
- **Remote synchronous risk service:** rejected because it adds latency, timeout, and availability
  coupling to the command path.
- **BLP-only checks:** correct for aggregate state but wastes ring/journal capacity on trivial failures
  and does not provide replica readiness/early rejection at the edge.
- **Distributed reservation across Gateways:** rejected as unnecessary coordination that conflicts with
  the existing single-writer architecture.

## Consequences

Positive:

- exact aggregate correctness under concurrent Gateways;
- no lock or blocking service hop;
- cheap invalid traffic rejected early;
- authoritative decisions replay in global order;
- Gateway/BLP disagreement becomes measurable rather than silently unsafe.

Costs:

- some validation logic exists in both Gateway and BLP and must share generated policy semantics;
- synchronous APIs wait for the BLP decision rather than returning after transport submission;
- decision outputs need correlation/idempotency fields;
- Gateway preliminary results may disagree with newer BLP state, requiring stable handling/telemetry.

## Validation

- Multi-Gateway concurrency test against one remaining limit.
- Deliberately stale Gateway policy/position fixture proving BLP rejection wins.
- Property tests that any BLP acceptance satisfies all authoritative invariants.
- Latency/no-GC gates for both stages.

