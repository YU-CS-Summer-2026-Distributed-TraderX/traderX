# No-GC Conformance Delta: YU03

Extends the inherited `YU01`/`YU02` no-GC contract (NGC-01/NGC-02) across the new validation and
risk-decision code (NFR-IMRG02). The rule is unchanged: after warm-up, the hot path allocates zero
bytes per event and touches no banned API on the BLP, journaler, or producer threads.

## In-scope additions (must be allocation-free after warm-up)

- **Gateway screening** — `GatewayReplicaStore.screen()`. Reads `ConcurrentHashMap` replicas and
  does integer/`BigDecimal`-at-edge math. (Edge thread, not the BLP thread; held to the same
  zero-steady-state intent but is an edge handler, which NGC-01 already exempts from the strict BLP
  gate. The `BigDecimal` limit-price conversion is an edge operation, matching the inherited edge
  convention.)
- **BLP decision + reservation** — `BlpRiskState.decideAndReserve` / `decideMarketTrade` / `consume`
  / `release` and `MatchingEngine` control-event handlers. Preallocated primitive arrays,
  open-addressing probes, `Math.multiplyExact`/`addExact`, no boxing, no per-event array/object.
- **Control-event decode/apply** — rides the existing `InputEvent` slots (no new allocation).
- **Output decision emit** — `OutputPublisher.emitTradeDecision` writes into the pooled output slot
  (no allocation), same as the inherited emitters.

## Banned on the BLP decision path (NGC-02, inherited + reaffirmed)

Locks, blocking calls, shared-state atomics, wall-clock reads (`System.currentTimeMillis`/`Instant.now`),
random identifiers, unordered iteration, autoboxing, per-event collection/stream/lambda allocation,
`BigDecimal` math (integer Px ticks only inside the BLP). Decision time is event-carried
(`eventTimeMillis`); ids derive from the order reference.

## Snapshot-restore and bootstrap are cold-path (exempt)

`BlpRiskState.captureImage`-style tuple builders, `SnapshotStore` (v3), and `ReplicaBootstrap`
allocate freely — they run at startup/interval, not per event.

## Gates

- The inherited `AllocationGateTest` drives the real ring topology and asserts zero steady-state
  allocation on producer/journaler/BLP threads. **Extending it to a risk-gated workload** (orders
  that decide+reserve, control events, fills that consume) is part of this state's test work.
- The Gradle `noGcTest` re-runs the gate under Epsilon-GC. A perf-profile rerun over the risk path
  is **deferred** to the perf-acceptance commit (NFR-IMRG13); slice 1 asserts the code-discipline
  layer (no banned APIs, preallocated state) via the unit/parity suites.

> Note: on the current dev machine the inherited `AllocationGateTest` intermittently reports a
> constant ~72-byte producer allocation on *pristine parent code too* (a JIT/measurement artifact,
> not introduced by YU03). Treat the Epsilon-GC `noGcTest` in CI as the authoritative gate.
