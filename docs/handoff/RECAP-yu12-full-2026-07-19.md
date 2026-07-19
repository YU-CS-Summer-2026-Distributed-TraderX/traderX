# YU12 Aeron Cluster — full recap (2026-07-17 → 2026-07-19)

One session, scaffold to proven: the BLP's hand-built YU11 HA machinery (journal + snapshots +
recovery bundles + witness) replaced by **Aeron Cluster (Raft)** — consensus-replicated matching
engine, 3 members, off the Kubernetes control plane, with the failover and throughput numbers to
show for it. Worktree `traderX-YU12-aeron-cluster`, branch `YU12-aeron-cluster` (parented on
YU11 at `84d0d01`). ~24 commits, nothing pushed.

## The arc

| Phase | Commit(s) | What was proven |
|---|---|---|
| Scaffold + spec pack | `756f52a` | House-style specs (FR-AC/NFR-AC/SC-AC), ADR-044..047, pipeline wiring |
| Phase 1 spike | `62a06e8` | Engine runs unchanged as a ClusteredService; orderRef generator is replicated state → YU11's ID-reuse bug class structurally gone |
| WS2 snapshot completeness | `5979929` | Risk state lives in the cluster; every future-output generator snapshotted; fail-closed load; audit matrix (F1 fixed, F2/F3 tracked) |
| WS3a in-process 3-member | `4e788c1` | Election, leader kill, **empty-disk rejoin to identical state** — falsifiable gate #1 answered: what YU11 built in 5 slices is a Cluster primitive |
| WS5 symbol identity | `236aceb` | Ticker registration as sequenced ingress (SBE template 7) — closes audit F2; clusterAllocationGateTest exact-zero |
| kind egress campaign | `2f2c6a0`..`8b056e2` | Root cause: egress channel without `term-length` → 64MB term → didn't fit container `/dev/shm`. Fixed with `term-length=64k` + Memory /dev/shm |
| kind HA proof | `01fa6f3` | Live 3-member cluster: **0 ID reuse across 2 failovers + empty-disk rejoin**, refs strictly increasing 1005..7457 |
| WS4 gateway | `d1110eb`,`d8c7c8b` | REST+FIX on one owner-thread cluster client; live failover transparency (leader killed, next REST POST served first try); FIX session survives leader blip |
| GKE deploy + failover | `193d8da`..`7dedb91` | Below — the headline numbers |
| GKE bench + hosting hardening | `388db93`,`586c354`, in flight | Below — throughput + three real bugs found by flood-testing |

## Failover: where we started, where we landed

| Config / era | System-facing | Client-facing |
|---|---|---|
| YU11 (k8s-native: probes + Lease witness) | ~10-30 s, on the k8s plane | same |
| YU12 kind (Docker Desktop, default timeouts) | ~15-17 s | 813 ms best, ~17 s typical |
| YU12 GKE, Aeron defaults (10 s heartbeat timeout) | **12.0 s** | 12.3 s |
| YU12 GKE, tuned 100ms/1s/500ms | ~2.0 s | 192 ms best, 2.1-3.1 s typical |
| **YU12 GKE, final 100ms/400ms/200ms** | **653-716 ms across 5 kills** | 838-1657 ms (bimodal; client artifact) |

- **Consistent sub-1s system-facing achieved** (653/662/665/668/716 ms), measured honestly:
  members log `ROLE-CHANGE atMs=`, the harness `kubectl exec`s into the leader to print a
  node-clock ms timestamp and `kill -9` the JVM in one shot (JVM runs under an `sh` wrapper
  because PID 1 is unsignalable from inside the namespace). No pod-delete API latency, no poll
  latency, NTP-synced node clocks.
- **Off the k8s control plane: proven across ~35 leader kills** (Raft-internal election, k8s only
  reschedules the corpse). **Zero order-ID reuse in every run.**
- The earlier "consistent sub-1s impossible / 500ms destabilizes" readings were measurement
  artifacts, root-caused one by one: API-proxy read skew in the settle check, quorum-loss from
  killing too fast, the proof client's own reconnect churn (stall clock set before the blocking
  connect), and PVC detach/reattach minutes (fixed by emptyDir — Raft quorum IS the durability).
- Client-facing floor is now the test client's endpoint-cycling reconnect (burns its 1 s connect
  timeout when it tries the dead endpoint first); native AeronCluster `newLeaderEvent` tracking
  is the identified fix if a sub-1s client floor is ever needed. Best observed: ~200 ms.

## Throughput: where we started, where we landed

Baselines from prior states (GKE, booked orders/s through the full committed path):

| State | booked/s |
|---|---|
| YU02 NATS-sequenced HA | ~10.1k (raw NATS sequencing measured 10.5k/s ceiling) |
| YU09/YU11 single-BLP parity baseline | **25,149** (cleanest run; NFR-AC02 bar) |
| YU11 Aeron replication (hand-built HA) | 25,149-parity, +149% over NATS HA |
| YU12 cluster, single-order gateway path | ~800-1,100 sustained (per-order committed-ack wait ~1.2ms = the ceiling) |
| YU12 engine burst through Raft (observed) | 18,612 booked/s |
| **YU12 pipelined gateway (first flood)** | **28,896 booked/s, 24,037 submit/s** — past the bar |

- The consensus/apply path was never the bottleneck: 3-member Raft replication sustained an
  18.6k/s cascade and replayed a 740k-event log in seconds. The ceiling was the gateway's
  correctness-first one-order-at-a-time submit — same lesson as every prior state: **per-order
  ingress is the ceiling, not the BLP**.
- Pipelining the gateway owner thread (offer the whole batch into the log, count acks as they
  stream back FIFO) took one 200-order batch from ~250 ms to **38 ms**, and the full flood past
  25k. Hardening of the gateway under that flood (HTTP pool, heap) is the current in-flight step;
  a clean 3-run confirmation is pending.

## Three real production-grade bugs the flood found (all fixed)

1. **Egress emission throttled the state machine** — an undeliverable ack retried 1000× with
   backoff (~1 s per ack); one non-draining client collapsed apply to ~1 event/s. Now 20
   attempts, sub-ms; slow clients get drops, never the state machine's time.
2. **Output-ring self-deadlock** — the service thread is both producer (engine emits during
   apply) and consumer (drain after apply); a price tick mass-executing a ~20k-order resting
   book overflowed the 4096-slot ring and parked `RingBuffer.next()` forever *inside apply* on
   all three members (consensus up, applied frozen). Proven by SIGQUIT thread dump. Ring now
   env-tunable: default 1<<16, GKE pins 1<<18. Sizing invariant documented.
3. **Gateway self-eviction under load** — all 8 HTTP threads parked on batch futures, the
   readiness probe starved, k8s pulled the gateway out of the Service mid-bench. Pool now 64
   threads + heap 1g + CPU limit 2.

Plus operational findings: fail-closed risk means benches must seed control state and keep
prices fresh (`/seed` gateway endpoint + 10 s price refresher; `risk.price.max-age-ms` = 30 s),
and booked/s must be read from the engine's authoritative `trades` counter in member health —
gateway fill counters under-count when egress drops (which is by design).

## Documents in docs/handoff/

| Doc | What it holds |
|---|---|
| `HANDOFF-aeron-cluster-migration.md` | The YU11→YU12 migration brief this state executed (committed on the YU11 branch, `84d0d01`) |
| `ISSUES-yu12-kind-egress-2026-07-17.md` | The kind egress mystery: symptoms, ruled-out causes, root cause (`term-length` vs /dev/shm), fix |
| `PROOF-yu12-kind-ha-2026-07-18.md` | kind live HA proof: 0 ID reuse across failovers + empty-disk rejoin |
| `PROOF-yu12-gke-failover-2026-07-18.md` | The GKE campaign: off-plane proof, timeout tuning table, consistency investigation, sub-1s achievement, emptyDir-vs-PVC, bench results + the three bugs |
| `GKE-yu12-deploy-bench.md` | Ordered GKE deploy + bench hand-over commands (image, overlay, gateway, bench label) |
| (YU11 context) `RECAP-yu11-full-2026-07-17.md`, `PROOF/FINDINGS-yu11-cross-epoch-recovery-2026-07-17.md`, `ISSUES-yu11-e2e-2026-07-17.md` | The parent state's story this one built on |

## Verification state

- Suite: 206 tests, 0 failures; `noGcTest` + all four allocation gates green, including the
  cluster service-thread exact-zero gate (NFR-AC01).
- Live: GKE `traderx-lmax` (blp-pool=2 c2-standard-4, default-pool=1) running the 3-member
  cluster (emptyDir, 400/200 timeouts), gateway, proof client. kind cluster `traderx-yu12-cluster`
  idle locally.
- Constraints held: nothing pushed; `risk.entitlement.enforced` stays false.

## Open items

- Confirm the pipelined-gateway bench with a clean 3-run pass (hardened gateway rolling out).
- Exact per-order batch correlation (client key echoed in egress) — FIFO counting is exact only
  for fully-marketable flow; fine for the bench, needed for production batch semantics.
- C2_CPUS quota bump (8 → 12) for one-member-per-node = true node-fault HA.
- Feed adapter live against NATS pricing (stack currently scaled to 0); config identity (audit F3).
- Periodic snapshots to durable storage if whole-cluster-restart durability is wanted on top of
  emptyDir (Raft quorum covers member loss, not all-three-at-once loss).
