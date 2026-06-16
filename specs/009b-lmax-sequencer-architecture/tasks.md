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
      pipeline validators/installers. (2026-06-10: added the `009b` runtime-harness case,
      pubsub-inspector gate, ingress observability route injection in the 009b render, and
      capture excludes for top-level-only installer outputs; overlay patchset captured and
      consumer-verified — see `generation/implementation-status.md`.)
- [ ] T09B10 Establish the P0 latency harness (HdrHistogram/JLBH/jHiccup) against generated `009`.
- [x] T09B11 Implement the input disruptor (ring, sequencer, journaler, replicator, sequence barrier)
      inside `order-matcher`; remove `@Scheduled` poll and `orderMutationLock`.
      (Implemented in `generation/runtime-overrides/order-matcher/.../lmax/`; HdrHistogram latency
      telemetry included. SBE un-marshaller deferred — see `generation/implementation-status.md`.)
- [~] T09B12 `long` fixed-point price/qty and `int securityId` symbol table implemented (`Px`,
      `SymbolTable`); SBE codec generation (`generateSbe`, `sbe/*.xml`) and Agrona structures
      deferred (`generation/implementation-status.md`).
- [x] T09B13 Implement the BLP (single-threaded in-memory order book, event-carried time, typed
      output events, gateway request/response acks). Booking + position-keeping are fused into the BLP
      (FR-09B08/B10/B15, 2026-06-15): fills and `TYPE_TRADE_NEW` market trades update the in-memory
      `PositionBook` (single writer) and emit `TradeBooked` + `PositionUpdated`; the projector writes
      `TRADES`/`POSITIONS` (deterministic trade ids) and the NATS bridge publishes
      `/accounts/{id}/trades` and `/accounts/{id}/positions`. trade-service forwards market trades to
      the gateway; the old `TradeSubmitHandler` round-trip is removed and `trade-processor` is idle on
      this path (still deployed for read endpoints + smoke parity).
- [~] T09B14 Recovery: persisted read-model warm-start + input-event journal implemented; snapshot
      files, journal replay tooling, JIT warm-up, and nightly bounce deferred.
- [x] T09B15 Implement the output disruptor (Marshaller/read-model, NATS bridge preserving `009`
      subjects/payloads, TradeBooked bridge, batched Read-model Projector).
- [~] T09B16 Replication seam implemented as loopback stub gating the BLP (demo profile); real
      follower BLP + promotion failover deferred to the perf profile.
- [~] T09B17 Wait-strategy/ring-size/journal config keys implemented with demo-safe defaults
      (`blocking`); `perf`/`noGcTest` JVM launch profiles deferred.
- [~] T09B18 No-GC gate implemented (2026-06-11): `pipeline/validate-no-gc-conformance.sh` runs the
      order-matcher Gradle `noGcTest` task — `AllocationGateTest` under Epsilon GC on a fixed
      pre-touched 256m heap (steady-state allocation exhausts the heap and fails), with the same
      test asserting byte-exact zero `ThreadMXBean` allocation deltas for the producer, journaler,
      and BLP threads in the regular `test` task; banned-API constant-pool scan
      (`HotPathBannedApiTest`, SC-09B13/SC-NGC-04). JFR/async-profiler attribution tooling
      deferred.
- [~] T09B19 Parity fixtures: penny parity (`PxTest`) and functional-policy parity
      (`LmaxHotPathParityTest`) implemented and passing; determinism replay + NATS payload
      byte-parity smoke deferred.
- [ ] T09B20 Add ring/BLP/egress/no-GC observability (metrics wiring, Prometheus targets, Grafana
      dashboards incl. allocation alert and GC-pause panel).
- [ ] T09B21 Implement smoke tests: `scripts/test-state-009b-lmax-sequencer-architecture.sh`.
- [ ] T09B22 Validate docs/spec gates, flip the catalog entry from `draft` to `implemented`, replace
      the `009`-delegating lifecycle scripts with state-native ones, and publish the generated
      snapshot branch.
- [~] T09B23 CI slice landed (2026-06-12): the generated tree ships
      `.github/workflows/no-gc-gate.yml` running the Epsilon allocation gate (`noGcTest`) and
      the hot-path conformance tests on `order-matcher/**` changes (NGC-02-in-CI per
      SC-09B14/SC-NGC-06). GHCR publish workflow extension, run bundle, and
      `runtime/deploy/` bundle remain.
