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

The hot-path node reports unhealthy until snapshot load + journal replay + JIT warm-up complete.

## 3) Run Smoke Tests

```bash
./scripts/test-state-009b-lmax-sequencer-architecture.sh
./scripts/test-state-009b-lmax-sequencer-architecture.sh --skip-messaging
./scripts/test-messaging-009b-lmax-sequencer-architecture.sh
```

## 4) Run the No-GC and Parity Gates

```bash
# Epsilon-GC allocation gate (also runs in CI)
bash pipeline/validate-no-gc-conformance.sh

# penny-parity fixture (long fixed-point vs 009 BigDecimal) and determinism replay
./gradlew :order-matcher:noGcTest
./gradlew :order-matcher:test --tests "*PennyParity*" --tests "*DeterminismReplay*"
```

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
