# ADR-020: Decision-Relevant Control Events Enter the Global Journal

**Status:** Accepted for specification (implemented in YU03 slice 1)
**Date:** 2026-06-22 (forward-ported to YU03 2026-07-06)
**State:** `YU03-in-memory-risk-gateway` (parent `YU02-lmax-kubernetes`)

## Context

An order decision depends on external account/security/risk values. Replaying a historical order
while querying today's external state can produce a different result, breaking the inherited
`YU01`/`YU02` determinism. Gateway replicas alone also do not establish one total order between
control changes, price ticks, and commands.

## Decision

Every control value that can alter an authoritative BLP decision enters the inherited global
input sequence as a complete versioned event. This includes account status, entitlement, security
status, restrictions, risk policies/limits, and kill switches.

The BLP applies those events in sequence with prices and submitted commands. Its snapshot includes
the resulting control state and source watermarks. BLP recovery uses only its snapshot plus global
journal; it does not query or restore from Gateway replica state.

Gateway replicas may consume the durable source stream directly for early screening. Differences
in apply timing are acceptable because the BLP remains authoritative and records versions used.

## Alternatives Considered

- **Query external controls during replay:** rejected because current values change historical results.
- **Snapshot controls only:** rejected because changes between snapshots and commands are lost.
- **Independent asynchronous BLP cache feed outside the sequencer:** rejected because relative
  ordering with commands/prices is nondeterministic.
- **Record only policy id and query policy body:** rejected because replay still depends on mutable
  external data.

## Consequences

Positive: decisions, reasons, reservations, and final state are deterministic and auditable;
production incidents reproduce from captured journal/snapshot; warm standby BLPs apply identical
controls in identical order; the policy version used for a decision is explicit.

Costs: input schema and BLP snapshot grow; source deltas require validation before sequencing;
policy evolution needs schema compatibility rules; sensitive control data requires journal/snapshot
protection and retention governance.

## Status in YU03 slice 1

- **Implemented.** `InputEvent.TYPE_{ACCOUNT,SECURITY,POLICY,RESTRICTION}_CONTROL` (ids 7–10) carry
  versioned control state through the journaled input ring and the NATS replication stream.
  `MatchingEngine.onAccountControl/onSecurityControl/onPolicyControl/onRestrictionControl` apply
  them on the BLP thread; snapshot format v3 (`SnapshotStore`) persists the resulting control
  state, and `RiskReplayDeterminismTest` proves replay + snapshot-v3-tail reproduce identical
  decisions and reservations.
- **Forward-port note:** to avoid orphaning existing journals and the snapshot byte-offset
  recovery, no wire-format change was made — control fields ride payload slots unused by each
  event type (see `InputEvent`'s documented type-discriminated contract), and control ids start at
  7 because `YU02` had already journaled `TYPE_SNAPSHOT = 6` (the stale branch used 6).
- Control ingestion is the journaled `/risk/control` API + startup `ReplicaBootstrap`; the durable
  account-service/reference-data outbox source streams are deferred (see ADR-019).

## Validation

- Replay mixed control/price/command journal, compare byte-equivalent decision outputs —
  `RiskReplayDeterminismTest.identicalEventSequenceReproducesIdenticalRiskAndBookState`.
- Move a policy update one sequence before/after a command, assert the decision difference —
  covered by the mid-stream kill-switch in the replay script.
- Recover from snapshot + tail, compare control/reservation/idempotency state —
  `snapshotV3PlusTailRestoresIdenticalState`.
- Confirm replay performs no account/reference/risk network call — the BLP decision path is
  memory-only by construction (`BlpRiskState` has no I/O).
