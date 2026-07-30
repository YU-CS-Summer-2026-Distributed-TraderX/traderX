# Quickstart: LMAX Sequencer Architecture (Trading Hot Path)

## 1) Generate This State

```bash
bash pipeline/generate-state.sh YU01-lmax-sequencer
```

## 2) Start Runtime (demo profile)

```bash
./scripts/start-state-YU01-lmax-sequencer-generated.sh
./scripts/start-state-YU01-lmax-sequencer-generated.sh --skip-build
```

The hot-path node warm-starts from the persisted read-model (orders + net positions re-indexed into
the BLP at boot) and, on the demo profile, periodically checkpoints full state to `snapshot.dat`
(`SNAPSHOT_INTERVAL_MS`, default 60 s); on restart it loads the latest snapshot and replays only the
journal tail after it. JIT warm-up still lands with T09B14. See §7 to switch `recovery.source` to the
DB-less `journal` mode.

## 3) Run Smoke Tests

```bash
./scripts/test-state-YU01-lmax-sequencer.sh
./scripts/test-state-YU01-lmax-sequencer.sh --skip-messaging
./scripts/test-messaging-YU01-lmax-sequencer.sh
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
./scripts/stop-state-YU01-lmax-sequencer-generated.sh
```

## 6) Inspect Hot-Path Observability

```bash
ORDER_MATCHER_PORT="${ORDER_MATCHER_PORT:-18110}"

# ring headroom, sequence lag, BLP latency, allocation rate (must stay ~0)
curl -s "http://localhost:${ORDER_MATCHER_PORT}/metrics" | rg "traderx_disruptor_input_remaining_capacity|traderx_input_seq_lag|traderx_blp_event_latency_seconds|traderx_hotpath_alloc_bytes_total"

# retained 009 order gauges (now sourced from in-memory BLP state)
curl -s "http://localhost:${ORDER_MATCHER_PORT}/metrics" | rg "traderx_orders_open_total|traderx_orders_unfilled_total"

# hot-path node health (ready after recovery: snapshot load + journal-tail replay)
curl -s "http://localhost:${ORDER_MATCHER_PORT}/health"

# dashboard landing (hot-path dashboards incl. allocation alert + GC-pause panels)
open http://localhost:3001
```

## 7) Exercise Recovery and Decoupling

```bash
ORDER_MATCHER_PORT="${ORDER_MATCHER_PORT:-18110}"

# Snapshot + journal-tail recovery (default recovery.source=db): restart the node, then read the
# recovery log — "JOURNAL-REPLAY VERIFY: PASS" proves the journal alone rebuilds the warm-start state.
./scripts/stop-state-YU01-lmax-sequencer-generated.sh --only order-matcher
./scripts/start-state-YU01-lmax-sequencer-generated.sh --only order-matcher --skip-build
docker logs "$(docker ps -qf name=order-matcher)" 2>&1 | grep -E "JOURNAL-REPLAY VERIFY|LIVE RECOVERY"

# DB-less cutover (recovery.source=journal): the matcher recovers from snapshot+journal and serves
# /orders + /positions from memory with the database STOPPED.
RECOVERY_SOURCE=journal OUTPUT_PROJECTOR_DB_ENABLED=false \
  SPRING_JPA_DDL_AUTO=none HIKARI_INIT_FAIL_TIMEOUT=-1 MANAGEMENT_HEALTH_DB_ENABLED=false \
  ./scripts/start-state-YU01-lmax-sequencer-generated.sh --only order-matcher --skip-build

# Decoupling (db mode): stop the database, keep trading, restart it, watch the projector queue drain
curl -s "http://localhost:${ORDER_MATCHER_PORT}/metrics" | rg "traderx_projector_queue_depth|traderx_projector_enqueue_blocks_total"
```

## Notes

- The `demo` profile (default in containers/CI) uses `BlockingWaitStrategy`, no core pinning, and a
  single replica. Latency budgets are validated on the `perf` profile (bare metal); see
  `requirements/no-gc-conformance.md` for the JVM flag matrix.
