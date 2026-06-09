# Tasks: 009b-lmax-sequencer-architecture

- [x] T09B01 Define functional deltas in `requirements/functional-delta.md`.
- [x] T09B02 Define non-functional deltas in `requirements/nonfunctional-delta.md`.
- [x] T09B03 Define the no-GC conformance profile in `requirements/no-gc-conformance.md`.
- [x] T09B04 Document research and constraints in `research.md`.
- [x] T09B05 Define data model impacts in `data-model.md`.
- [x] T09B06 Author run instructions in `quickstart.md`.
- [x] T09B07 Define contract deltas in `contracts/contract-delta.md`.
- [x] T09B08 Update `system/architecture.model.json` and regenerate architecture docs.
- [x] T09B09 Author ADRs 014–017 (input disruptor, single-thread BLP, async read-model, no-GC gate).
- [x] T09B09A Scaffold pipeline readiness: catalog entry (`status: draft`), generation hook,
      render stub, lifecycle delegate scripts to `009`, and letter-suffix state-id support in
      pipeline validators/installers.
- [ ] T09B10 Establish the P0 latency harness (HdrHistogram/JLBH/jHiccup) against generated `009`.
- [ ] T09B11 Implement the input disruptor (ring, sequencer, journaler, replicator, un-marshaller,
      sequence barrier) inside `order-matcher`; remove `@Scheduled` poll and `orderMutationLock`.
- [ ] T09B12 Implement `long` fixed-point price/qty, `int securityId` symbol table, and SBE codec
      generation (`generateSbe` Gradle task, `sbe/order-input.xml` + `sbe/order-output.xml`).
- [ ] T09B13 Implement the fused BLP (in-memory order books, positions, caches; typed output events;
      request/response cache-miss events; determinism contract).
- [ ] T09B14 Implement snapshot + journal replay recovery and JIT warm-up; nightly bounce hook.
- [ ] T09B15 Implement the output disruptor (Marshaller, NATS Publisher bridge preserving `009`
      subjects/payloads, batched Read-model Projector with checkpoint + rebuild).
- [ ] T09B16 Implement replication to a follower BLP with output suppression and promotion-based
      failover (loopback/stub mode for the `demo`/`C2` profile).
- [ ] T09B17 Implement run profiles (`demo`/`perf`/`noGcTest`) with documented JVM flags and wait
      strategies.
- [ ] T09B18 Implement the no-GC gate: `pipeline/validate-no-gc-conformance.sh`, Gradle `noGcTest`
      task under Epsilon GC, banned-API static check, JFR/async-profiler attribution.
- [ ] T09B19 Implement parity fixtures: penny parity, determinism replay, NATS subject/payload parity.
- [ ] T09B20 Add ring/BLP/egress/no-GC observability (metrics wiring, Prometheus targets, Grafana
      dashboards incl. allocation alert and GC-pause panel).
- [ ] T09B21 Implement smoke tests: `scripts/test-state-009b-lmax-sequencer-architecture.sh`.
- [ ] T09B22 Validate docs/spec gates, flip the catalog entry from `draft` to `implemented`, replace
      the `009`-delegating lifecycle scripts with state-native ones, and publish the generated
      snapshot branch.
- [ ] T09B23 Extend `C2` build/publish workflow + GHCR run bundle + `runtime/deploy/` bundle for this
      state; validate local dry-run and no embedded secrets.
