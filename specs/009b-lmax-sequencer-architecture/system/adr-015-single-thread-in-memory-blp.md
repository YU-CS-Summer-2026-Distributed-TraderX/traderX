# ADR-015: Single-Threaded In-Memory Business Logic Processor

## Status
Proposed

## Date
2026-06-09

## Context

In state 009 the trading path spans processes: trade-service validates with three blocking REST calls,
publishes to NATS; trade-processor books trades and writes the DB; order-matcher matches, persists via
JPA, and POSTs fills back to trade-service. Every boundary adds serialization, allocation, blocking
I/O, and failure modes. The LMAX result is that a single thread processing business logic entirely in
memory, fed by a sequenced input stream, removes locks, queues, and GC from the critical path
("there is no database or other persistent store" on the hot path).

## Decision

1. Matching, trade booking, and position keeping are fused into one single-threaded, in-memory,
   event-sourced BLP inside the matcher node (FR-09B10).
2. The BLP makes no blocking external calls — no REST, no JPA, no NATS. Validation data
   (accounts, reference data, last prices) is held in event-fed in-memory caches; misses are resolved
   via asynchronous request/response event pairs (FR-09B11, FR-09B12).
3. The BLP is deterministic: no wall-clock reads (time is event-carried `ingressNanos`), no
   unordered-collection iteration, no RNG/UUID; order IDs derive from the global sequence (FR-09B14).
4. The 009 auto-fill policy and lifecycle are preserved exactly, evaluated in `long` fixed-point
   integer math (FR-09B13, penny-parity gate SC-09B04).
5. The BLP's only side-effect channel is emitting typed output events to the output ring (FR-09B15);
   state is recoverable via snapshot + journal replay with JIT warm-up (FR-09B16).

## Consequences

- The multi-hop "validate -> publish -> book -> match -> POST back" dance becomes in-memory method
  calls between consuming event N and N+1; the lock, atomics, and per-tick JPA disappear.
- Determinism makes the journal a complete diagnostic artifact: any production incident replays
  bit-identically in a dev box, and replicas stay in lock-step for failover.
- The single-thread ceiling (LMAX reference ~6M events/s) is orders of magnitude above TraderX needs;
  sharding by instrument remains a future option, not a present requirement.
- Cost: a programming-model shift ("no external calls") that contributors must learn; codified
  request/response patterns and the banned-API static check (SC-09B13) keep the discipline enforced.
