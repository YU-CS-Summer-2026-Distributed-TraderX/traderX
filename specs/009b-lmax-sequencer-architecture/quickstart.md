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

The hot-path node warm-starts from the persisted read-model (orders + net positions re-indexed into
the BLP at boot) and, on the demo profile, periodically checkpoints full state to `snapshot.dat`
(`SNAPSHOT_INTERVAL_MS`, default 30 s); on restart it loads the latest snapshot and replays only the
journal tail after it. JIT warm-up still lands with T09B14. See §7 to switch `recovery.source` to the
DB-less `journal` mode.

Since 2026-07-03 the stack runs an **active/passive matcher pair** behind an haproxy VIP:

| Host port | What it is |
| --- | --- |
| `18110` | **VIP** — the stable matcher address every client (and this doc's `curl`s) uses; routes commands to the live node, falls back to the warm follower for reads during a failover |
| `18111` | primary matcher directly |
| `18112` | standby matcher directly (warm follower: reads OK, writes 503 until promoted) |
| `18404` | haproxy stats UI |

`curl localhost:1811{1,2}/admin/role` shows each node's role, leader-lock, journal-writing state and
follower lag. See §8 to exercise a failover.

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

# hot-path node health (ready after recovery: snapshot load + journal-tail replay)
curl -s "http://localhost:${ORDER_MATCHER_PORT}/health"

# dashboard landing (hot-path dashboards incl. allocation alert + GC-pause panels)
open http://localhost:3001
```

## 7) Exercise Recovery and Decoupling

> Failover interaction (2026-07-03): restarting `order-matcher` while the standby is running triggers
> the standby's watchdog to PROMOTE (~3 s of failed probes), and the restarted primary then rejoins as
> a follower — so the leader-boot recovery log lines below won't appear on it. To exercise the
> single-node recovery paths as written, stop the standby first
> (`docker compose stop order-matcher-standby`), or start the stack with
> `FAILOVER_PROBE_FAILURES=999999`. §8 exercises the failover itself.

```bash
ORDER_MATCHER_PORT="${ORDER_MATCHER_PORT:-18110}"

# Snapshot + journal-tail recovery (default recovery.source=db): restart the node, then read the
# recovery log — "JOURNAL-REPLAY VERIFY: PASS" proves the journal alone rebuilds the warm-start state.
./scripts/stop-state-009b-lmax-sequencer-architecture-generated.sh --only order-matcher
./scripts/start-state-009b-lmax-sequencer-architecture-generated.sh --only order-matcher --skip-build
docker logs "$(docker ps -qf name=order-matcher)" 2>&1 | grep -E "JOURNAL-REPLAY VERIFY|LIVE RECOVERY"

# DB-less cutover (recovery.source=journal): the matcher recovers from snapshot+journal and serves
# /orders + /positions from memory with the database STOPPED.
RECOVERY_SOURCE=journal OUTPUT_PROJECTOR_DB_ENABLED=false \
  SPRING_JPA_DDL_AUTO=none HIKARI_INIT_FAIL_TIMEOUT=-1 MANAGEMENT_HEALTH_DB_ENABLED=false \
  ./scripts/start-state-009b-lmax-sequencer-architecture-generated.sh --only order-matcher --skip-build

# Decoupling (db mode): stop the database, keep trading, restart it, watch the projector queue drain
curl -s "http://localhost:${ORDER_MATCHER_PORT}/metrics" | rg "traderx_projector_queue_depth|traderx_projector_enqueue_blocks_total"
```

## 8) Exercise Warm-Standby Failover (FR-09B30..B32)

```bash
# The full scripted proof (16 checks: warm replication, SIGKILL promotion ~5-6s, state continuity,
# no order-id collision, lock-fenced failback-as-follower). Stack must be up.
./scripts/verify-009b-failover.sh

# Or by hand — watch the roles…
curl -s http://localhost:18111/admin/role   # {"role":"primary","live":true,"leaderLockHeld":true,...}
curl -s http://localhost:18112/admin/role   # {"role":"standby","live":false,"followerLagBytes":0,...}

# …kill the leader with no goodbye…
docker kill -s KILL traderx-state-009-order-matcher-1

# …and within a few seconds the standby wins leader.lock, drains the journal tail, and goes live;
# the VIP (18110) resumes routing orders to it. All acked state survives (acks sit behind the journal).
curl -s http://localhost:18112/admin/role   # {"role":"promoted","live":true,"journalWriting":true,...}
curl -s http://localhost:18110/orders | head -c 300

# Restart the old primary: it finds leader.lock held and REJOINS AS A FOLLOWER (no split-brain).
docker start traderx-state-009-order-matcher-1
```

Prometheus (1 s scrape on both nodes) charts the timeline: `traderx_blp_role`, `traderx_blp_live`,
`traderx_follower_lag_bytes`, `traderx_failover_promotions_total`. haproxy's view: http://localhost:18404.

## Notes

- The `demo` profile (default in containers/CI) uses `BlockingWaitStrategy` and no core pinning on the
  standby; the primary defaults to a pinned busy-spin BLP (`BLP_PIN_CPU`,
  `DISRUPTOR_INPUT_WAIT_STRATEGY`). Latency budgets are validated on the `perf` profile (bare metal);
  see `requirements/no-gc-conformance.md` for the JVM flag matrix.
- Benchmarking: target the primary directly (`MATCHER_URL=http://localhost:18111`) and bring the stack
  up with `FAILOVER_PROBE_FAILURES=999999` — the bench driver's per-run matcher restarts would
  otherwise trigger the standby to promote mid-run. A live follower costs ~20% sustained throughput at
  saturation (host CPU contention, not hot-path gating); stop `order-matcher-standby` for ceiling runs.
