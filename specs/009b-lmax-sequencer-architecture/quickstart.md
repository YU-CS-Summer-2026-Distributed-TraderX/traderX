# Quickstart: LMAX Sequencer Architecture (Trading Hot Path)

## 1) Generate This State

```bash
bash pipeline/generate-state.sh 009b-lmax-sequencer-architecture
```

## 2) Start Runtime (demo profile)

```bash
./scripts/start-state-009b-lmax-sequencer-architecture-generated.sh
./scripts/start-state-009b-lmax-sequencer-architecture-generated.sh --skip-build
```

The hot-path node warm-starts from the persisted read-model (orders re-indexed into the BLP at
boot); snapshot files, journal replay, and JIT warm-up land with T09B14.

## 3) Run Smoke Tests

```bash
./scripts/test-state-009b-lmax-sequencer-architecture.sh
./scripts/test-state-009b-lmax-sequencer-architecture.sh --skip-messaging
./scripts/test-messaging-009b-lmax-sequencer-architecture.sh
```

## 4) Run the No-GC and Parity Gates

```bash
# Epsilon-GC allocation gate: runs the order-matcher's Gradle `noGcTest` task under
# -XX:+UseEpsilonGC with a fixed pre-touched heap (heap exhaustion = failure)
bash pipeline/validate-no-gc-conformance.sh

# full module suite: penny parity (PxTest), functional/policy parity
# (LmaxHotPathParityTest), byte-exact allocation gate (AllocationGateTest), and the
# banned-API constant-pool scan (HotPathBannedApiTest)
(cd generated/code/target-generated/order-matcher && ./gradlew test)
```

Determinism-replay and NATS payload byte-parity smoke checks are deferred (T09B19/T09B21).

## 5) Stop Runtime

```bash
./scripts/stop-state-009b-lmax-sequencer-architecture-generated.sh
```

## 6) Inspect Hot-Path Observability

```bash
ORDER_MATCHER_PORT="${ORDER_MATCHER_PORT:-18110}"

# ring headroom, sequence lag, BLP latency, allocation rate (must stay ~0)
curl -s "http://localhost:${ORDER_MATCHER_PORT}/metrics" | rg "traderx_disruptor_input_remaining_capacity|traderx_input_seq_lag|traderx_blp_event_latency_seconds|traderx_hotpath_alloc_bytes_total"

# retained 009 order gauges (now sourced from in-memory BLP state)
curl -s "http://localhost:${ORDER_MATCHER_PORT}/metrics" | rg "traderx_orders_open_total|traderx_orders_unfilled_total"

# hot-path node health (ready only after replay + warm-up)
curl -s "http://localhost:${ORDER_MATCHER_PORT}/health"

# dashboard landing (hot-path dashboards incl. allocation alert + GC-pause panels)
open http://localhost:3001
```

## 7) Exercise Recovery and Decoupling

```bash
# snapshot + replay recovery: restart the node and watch traderx_blp_replay_seconds
./scripts/stop-state-009b-lmax-sequencer-architecture-generated.sh --only order-matcher
./scripts/start-state-009b-lmax-sequencer-architecture-generated.sh --only order-matcher --skip-build

# decoupling: stop the database, keep trading, restart it, watch traderx_projector_lag_seq drain
```

## Notes

- The `demo` profile (default in containers/CI) uses `BlockingWaitStrategy`, no core pinning, and a
  single replica. Latency budgets are validated on the `perf` profile (bare metal); see
  `requirements/no-gc-conformance.md` for the JVM flag matrix.
