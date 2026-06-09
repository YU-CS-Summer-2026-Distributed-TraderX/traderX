# Implementation Plan: 009b-lmax-sequencer-architecture

## Scope

- Sibling branch transition from `009-order-management-matcher` to `009b-lmax-sequencer-architecture`.
- Track focus: `architecture` (behavior-preserving execution-model replacement, like `005`/`006`).
- Rebuild the trading hot path only: Gateway encode, sequencer, input disruptor, BLP, output disruptor,
  journal/replication, async read-model. `account-service`, `position-service`, `people-service`,
  `reference-data`, Angular UI, ingress, and the LGTM stack are unchanged.
- Define requirement deltas, internal contracts (SBE schemas, journal, projection checkpoint), the no-GC
  conformance gate, and generation/test hooks.

## Milestones (strangler phases, all within this state)

1. **P0 — Latency harness**: instrument the `009` path end-to-end (HdrHistogram, JLBH, jHiccup) so every
   later milestone is proven, not assumed.
2. **P1 — Input disruptor in `order-matcher`**: replace the `@Scheduled` poll + `ReentrantLock` with the
   input ring and an event-driven single-threaded handler; `long` fixed-point + `int securityId`;
   route price ticks through the ring.
3. **P2 — Fused BLP**: fuse matching + trade booking + position keeping into one in-memory thread;
   replace blocking REST validations with event-fed caches; typed output events; determinism contract.
4. **P3 — Sequencer + journal authoritative + output ring**: journaler/replicator/un-marshaller in
   parallel with sequence-barrier gating; snapshot + replay recovery; NATS bridge and async batched
   read-model projector with checkpointed rebuild.
5. **P4 — Replication + failover + no-GC gate**: follower BLP with output suppression and promotion;
   nightly bounce; Epsilon-GC allocation gate, banned-API static check, penny-parity fixture wired into
   CI.

## Deliverables

1. Requirement deltas in `requirements/` (functional, non-functional, no-GC conformance profile).
2. Contract deltas in `contracts/` (internal SBE/journal/checkpoint contracts; external surface frozen).
3. Supporting artifacts: `research.md`, `data-model.md`, `quickstart.md`.
4. Architecture and topology deltas in `system/`, including ADRs 014–017.
5. Observability contract for ring/BLP/egress/no-GC metrics and Grafana panel set.
6. Generation hook implementation in `pipeline/generate-state-009b-lmax-sequencer-architecture.sh`.
7. No-GC conformance gate in `pipeline/validate-no-gc-conformance.sh` plus a Gradle `noGcTest` profile.
8. Smoke test implementation in `scripts/test-state-009b-lmax-sequencer-architecture.sh`.
9. Parity fixtures: penny-parity (fixed-point vs `BigDecimal`), determinism replay, subject/payload
   byte-parity against `009`.

## Exit Criteria

- Spec and tasks are complete and reviewed.
- Generation hook produces expected artifacts.
- Smoke tests pass for this state, including parity, determinism, recovery, and decoupling checks.
- Allocation gate (Epsilon GC) and banned-API static check pass in CI on the demo/`C2` profile.
- Latency report meets NFR-09B01 budgets on the `perf` profile (documented bare-metal run).
- State can be published to `code/generated-state-009b-lmax-sequencer-architecture`.
