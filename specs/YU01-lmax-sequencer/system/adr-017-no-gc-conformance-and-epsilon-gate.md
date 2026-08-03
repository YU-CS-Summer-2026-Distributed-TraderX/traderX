# ADR-017: No-GC Conformance Profile Enforced by an Epsilon-GC Gate

## Status
Proposed

## Date
2026-06-09

## Context

State 009's matcher allocates on every tick and message: stream pipelines over JPA rows, `BigDecimal`
math, Jackson per-message JSON, `String.format` IDs, `Instant.now()` timestamps, boxed SLF4J varargs.
Under load this is continuous garbage -> minor GC pauses -> tail-latency spikes, which would defeat the
latency goals of this state regardless of architecture. The LMAX remedy is to write the hot path so it
allocates zero bytes in the steady state — and to prove it, not assume it.

## Decision

1. A shared no-GC conformance profile (`requirements/no-gc-conformance.md`, NGC-01..NGC-08) binds every
   hot-path stage: pre-allocated ring slots, pooled domain objects, SBE flyweights over off-heap
   buffers, `long` fixed-point, `int` security IDs, Agrona collections, no locks/atomics, event-carried
   time, async binary logging.
2. The primary enforcement is an Epsilon-GC allocation gate in CI
   (`pipeline/validate-no-gc-conformance.sh` + Gradle `noGcTest` task): the hot path runs a sustained
   synthetic load under `-XX:+UseEpsilonGC` with a small fixed heap; any steady-state allocation
   exhausts the heap and fails the build (SC-NGC-01).
3. A static banned-API check rejects `BigDecimal`, `Instant.now()`, `HashMap`/`ConcurrentHashMap`,
   stream pipelines, `String.format`, and parameterized SLF4J in hot-path packages (SC-NGC-04).
4. Three run profiles separate proof from production: `noGcTest` (Epsilon, CI), `perf` (ZGC/Shenandoah
   safety net, pinning, hugepages, bare metal), `demo`/`C2` (container-safe defaults, no privileged
   knobs — but still gated in CI).
5. Edges are exempt by design: the Gateway (JSON/decimal in) and output handlers (JSON/SQL out) may
   allocate; they sit off the latency-critical section.

## Consequences

- "Allocation-free" becomes a regression-tested property: a stray `new` on the hot path fails CI the
  same day it is introduced, instead of surfacing as a p99.9 spike weeks later.
- The demo container needs no host privileges yet retains the proven allocation property; perf claims
  are confined to documented bare-metal runs.
- Penny parity (SC-NGC-02) pins fixed-point rounding to 009's `BigDecimal` behavior, eliminating
  numeric drift as a migration risk.
- Cost: contributor discipline (reuse, don't allocate), slightly unusual test infrastructure (Epsilon
  heap-exhaustion semantics), and JFR/async-profiler workflows for attributing any gate failure.
