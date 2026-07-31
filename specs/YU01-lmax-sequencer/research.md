# Research: LMAX Sequencer Architecture (Trading Hot Path)

## Objective

Define a sibling branch state on top of `009` that replaces the trading hot path's execution model
(poll + lock + blocking REST + inline JPA) with the LMAX architecture (sequenced event-sourced input,
single-threaded in-memory BLP, disruptor rings, no-GC discipline) while preserving all external
contracts and observability obligations.

## Inputs Reviewed

- `LMAX-SEQUENCER-ARCHITECTURE.md`, `LMAX-INPUT-DISRUPTOR.md`, `LMAX-BLP.md`,
  `LMAX-OUTPUT-DISRUPTOR.md`, `LMAX-NO-GC-JAVA.md` (repo root design proposals)
- Martin Fowler, *The LMAX Architecture* — https://martinfowler.com/articles/lmax.html
- `specs/009-order-management-matcher/` (full pack: spec, deltas, contracts, system docs, ADR-013)
- `catalog/state-catalog.json` (state lineage and `C2` conventions)
- state `007` observability stack and dashboard/probe patterns

## Key Decisions

1. **Single combined state pack.** The LMAX docs staged the work as three future states
   (input disruptor → fused BLP → output disruptor) plus a cross-cutting no-GC profile. Those state
   IDs (`010`–`012`) are already taken by the canonical lineage, and the requested shape is one `009b`
   branch; the staging survives as plan milestones P0–P4 inside this pack.
2. **Sibling branch, not canonical lineage.** `009b` branches off `009`; the canonical `010+` lineage
   (Kubernetes/Tilt/C3/FDC3) is unaffected. The state is registered in `catalog/state-catalog.json`
   (`status: draft`, `primaryLineageRole: optional`) because the generation pipeline's post-install
   steps resolve state metadata from the catalog; pipeline scaffolding (generation hook, render stub,
   lifecycle delegates to `009`) keeps `pipeline/generate-state.sh YU01-lmax-sequencer`
   runnable end-to-end, producing `009`-parity output until the overlay patchset lands. Letter-suffixed
   state ids are supported by the pipeline by treating `009b` as numeric base `009` for lineage
   thresholds while using the full prefix for script-name resolution.
3. **Requirement ID namespace `09B` + `NGC`.** The LMAX docs' illustrative `FR-014xx` block collides
   with `014-fdc3-intent-interoperability`, so this pack uses `FR-09Bxx`/`NFR-09Bxx`/`SC-09Bxx`. The
   cross-cutting no-GC profile keeps the `NGC-xx` namespace proposed in `LMAX-NO-GC-JAVA.md` so hot-path
   NFRs can reference it.
4. **Track `architecture`.** This is a behavior-preserving execution-model replacement, the same genre
   as `005` (Postgres) and `006` (NATS). The LMAX docs labeled their staged states `functional`, but no
   functional behavior changes in this state — parity with `009` is a hard gate.
5. **Journal authoritative (input doc "Option B").** The combined pack includes the output disruptor and
   projector, so the coherent source-of-truth choice is the journal, with Postgres/H2 as an async,
   rebuildable read-model — matching the headline design decision in `LMAX-SEQUENCER-ARCHITECTURE.md` §1.
6. **Replication/failover in scope, demo-right-sized.** Follower BLPs with output suppression and
   promotion are specified (FR-09B30..32); the `demo`/`C2` profile runs a single replica with the
   replication contract exercised in loopback/stub mode, full failover validated on the `perf` profile.
7. **Market trades enter the sequenced stream** (`TRADE_NEW` events). Derived from the sequencer doc's
   scope ("sequencer + matching BLP + trade ingest/booking are redesigned") and component mapping
   (trade-processor booking/position keeping fused into the BLP): keeping market trades on the old
   NATS-to-trade-processor path would leave two writers of position state and break the single-writer
   principle. `trade-processor` remains deployed only as a non-hot-path consumer until fully retired by
   a later state; its booking role on the trading path moves into the BLP.
8. **Library lineage.** LMAX Disruptor (rings), Agrona (off-heap buffers + primitive collections), SBE
   (one binary format for wire, ring, and journal), Chronicle Queue for the journal in the demo profile
   with Aeron Archive/Cluster as the perf-profile replication/consensus realization, OpenHFT Affinity
   for pinning, HdrHistogram/JMH/JLBH/jHiccup for honest measurement. Versions pinned CVE-clean per the
   repo dependency gate.
9. **Profiles split the latency dial.** `demo`/`C2` defaults to `BlockingWaitStrategy`, no pinning, no
   hugepages (container-safe, CI-friendly); `perf` uses busy-spin + pinned isolated cores on bare metal.
   The allocation gate applies to both; latency budgets bind only `perf`.
10. **Fixed-point scale ×1,000,000 globally** (`nogc.px.scale`), conversions centralized at the edges,
    with a penny-parity fixture against `009`'s `BigDecimal` behavior as a release gate.
11. **Spring stays at the edges.** Java 21 + Spring Boot as in `009` for lifecycle/wiring/actuator; the
    per-event path is plain Java and never touches Spring, JPA, or Jackson.

## Risks and Mitigations

- Risk: behavior drift from `009` (the migration must be invisible at the edge).
  - Mitigation: parity gates — REST/WS/NATS subject + payload parity, penny-parity fixture, smoke
    journeys identical to `009` (SC-09B03/04/09).
- Risk: hidden non-determinism (wall clock, `HashMap` iteration order, RNG) breaks replay.
  - Mitigation: determinism contract (FR-09B14) + journal replay assertion (SC-09B06) + banned-API
    static check (SC-09B13).
- Risk: accidental allocation creeps onto the hot path and reintroduces GC tails.
  - Mitigation: Epsilon-GC allocation gate in CI on every change (SC-09B05), JFR/async-profiler
    attribution, allocation-rate metric alerting at `> 0`.
- Risk: ring undersized; backpressure stalls producers under burst.
  - Mitigation: sizing math in `data-model.md` (worst burst × slowest-handler stall × safety factor),
    remaining-capacity gauge + alert.
- Risk: slow DB/NATS stalls matching.
  - Mitigation: independent output handlers on a bounded ring; projector lags and catches up; journal
    remains authoritative (FR-09B24, SC-09B10).
- Risk: busy-spin/pinning/hugepages unavailable in `C2` containers.
  - Mitigation: demo profile runs without them and still passes the allocation gate (NFR-09B06,
    SC-09B14).
- Risk: operational complexity (sequencer, journal, replicas, snapshots, replay).
  - Mitigation: adopt Chronicle/Aeron rather than hand-rolling; phase via P0–P4; single-replica demo
    deployment.
- Risk: programming-model shift ("no external calls from the BLP") slows contributors.
  - Mitigation: codified request/response event patterns with examples in the BLP module; ADR-015.
- Risk: over-engineering for a reference app.
  - Mitigation: explicit teaching intent; demo profile right-sizes deployment; perf claims confined to
    documented bare-metal runs.
