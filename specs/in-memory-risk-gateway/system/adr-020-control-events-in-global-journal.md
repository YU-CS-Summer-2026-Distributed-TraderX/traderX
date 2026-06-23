# ADR-020: Decision-Relevant Control Events Enter the Global Journal

**Status:** Accepted for specification  
**Date:** 2026-06-22  
**State:** `in-memory-risk-gateway`

## Context

An order decision depends on external account/security/risk values. Replaying a historical order while
querying today's external state can produce a different result, breaking `009b` determinism. Gateway
replicas alone also do not establish one total order between control changes, price ticks, and commands.

## Decision

Every control value that can alter an authoritative BLP decision enters the inherited global input
sequence as a complete versioned event. This includes account status, entitlement, security status,
restrictions, risk policies/limits, and kill switches.

The BLP applies those events in sequence with prices and submitted commands. Its snapshot includes the
resulting control state and source watermarks. BLP recovery uses only its snapshot plus global journal;
it does not query or restore from Gateway replica state.

Gateway replicas may consume the durable source stream directly for early screening. Differences in
apply timing are acceptable because the BLP remains authoritative and records versions used.

## Alternatives Considered

- **Query external controls during replay:** rejected because current values change historical results.
- **Snapshot controls only:** rejected because changes between snapshots and commands are lost.
- **Independent asynchronous BLP cache feed outside the sequencer:** rejected because relative ordering
  with commands/prices is nondeterministic.
- **Record only policy id and query policy body:** rejected because replay still depends on mutable
  external data.

## Consequences

Positive:

- decisions, reasons, reservations, and final state are deterministic and auditable;
- production incidents can be reproduced from captured journal/snapshot;
- warm standby BLPs apply identical controls in identical order;
- policy version used for a decision is explicit.

Costs:

- input schema and BLP snapshot grow;
- source deltas require validation/adaptation before sequencing;
- policy evolution needs schema compatibility/upcasting rules;
- sensitive control data requires journal/snapshot protection and retention governance.

## Validation

- Replay mixed control/price/command journal and compare byte-equivalent decision outputs.
- Move a policy update one sequence before/after a command and assert the expected decision difference.
- Recover from snapshot plus tail and compare control/reservation/idempotency state.
- Confirm replay performs no account/reference/risk network call.

