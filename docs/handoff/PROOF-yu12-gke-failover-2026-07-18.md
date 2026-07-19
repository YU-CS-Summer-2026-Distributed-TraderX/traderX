# YU12 Aeron Cluster — GKE failover measurement (2026-07-18)

Deployed the 3-member cluster + gateway to the real GKE cluster (`traderx-lmax`, `us-east1-b`) and
measured failover: is it off the k8s control plane, and how fast (client- and system-facing)?

## Deployment

- Image `us-east1-docker.pkg.dev/traderx-501015/traderx/cluster-node:yu12` (**linux/amd64** —
  the arm64 Mac build fails to pull on GKE; build with `YU12_PLATFORM=linux/amd64`).
- 3 members on **2** `c2-standard-4` blp-pool nodes (preferred anti-affinity packs 3-on-2). Node
  count is capped by the project's **C2_CPUS quota = 8** (3 nodes would need 12); a quota bump is
  required for one-member-per-node (true node-fault tolerance). Pod-kill failover is unaffected.
- Gotchas fixed live: blp-pool nodes carry a `workload=blp:NoSchedule` taint (needs a toleration);
  and the tainted-only topology left `kube-dns`/`konnectivity-agent` Pending for 26h — members
  crash-looped on peer DNS and logs/exec were blocked until `default-pool` was scaled to 1 for the
  system pods.
- `ClusterGatewayMain` deployed (Service `order-matcher-gw`, distinct from the existing YU11
  `order-matcher` Service); REST + FIX up and connected.

## Off the control plane?

**Yes, definitively.** Every failover elected a new leader among the surviving members with zero
Kubernetes involvement in the decision — Raft internal election, no Lease, no witness. k8s only
reschedules the killed pod afterward. Confirmed across ~8 leader kills.

## Failover speed — the consensus timeouts dominate, and they are the lever

The Aeron default `leaderHeartbeatTimeout` is **10 s**, so with defaults failover is ~10–12 s on
ANY hardware — confirmed on GKE (not a Docker-Desktop artifact):

| Config (heartbeat interval / timeout / election) | System-facing re-election | Client-observed outage |
|---|---|---|
| Aeron defaults (200ms / 10s / 1s) | **12.0 s** | 12.3 s |
| Tuned **100ms / 1s / 500ms** (fresh, settled) | **~2.0 s** (2.00, 2.02) | **202 ms** |
| Too-aggressive 50ms / 500ms / 300ms | 5.8 s (unstable) | 8.0 s |
| Tuned 1s config under config-thrash / rapid repeated kills | 12–29 s (churn, recovers) | — |

Zero order-ID reuse in every run.

### Readings

- **Client-facing failover is sub-second (~200 ms)** at the tuned 1 s config. The counterparty's
  order→ack stream barely hiccups: orders submitted during the election are held by the Aeron
  Cluster client and commit under the new leader, so acks resume in a smooth stream (max
  inter-ack gap 202 ms with a 200 ms submit cadence).
- **System-facing re-election is ~2 s** at the tuned config (≈1 s to detect the dead leader via
  the heartbeat timeout + ≈1 s election) — a 6× improvement over the 12 s default, reproducible on
  a fresh/settled cluster.
- **Sub-1s *system-facing* is not reliably delivered.** One lucky sample hit 1.04 s; the honest
  reproducible number is ~2 s. Pushing the heartbeat timeout to 500 ms made it WORSE (election
  instability / false-positive elections). Consistent sub-1s system-facing needs a dedicated
  stability investigation, not just tighter numbers.
- **Variance is real and setup-sensitive.** Rapid config changes + repeated kills churned the
  cluster to 12–29 s before it re-settled. The clean single-kill on a settled cluster is the fair
  measurement (~2 s).

## The tuning lever (shipped)

`ClusterNodeConfig` reads env (ms; 0 = Aeron default), so the sweet spot is tunable without a
rebuild — set on the StatefulSet:

- `CLUSTER_HEARTBEAT_INTERVAL_MS` (tried 100)
- `CLUSTER_HEARTBEAT_TIMEOUT_MS` (sweet spot ~1000; 500 destabilized)
- `CLUSTER_ELECTION_TIMEOUT_MS` (tried 500)
- `CLUSTER_STARTUP_CANVASS_TIMEOUT_MS`

## Bottom line

- Off the k8s plane: **yes**, proven.
- Client-facing (counterparty) failover: **~200 ms, effectively transparent** with tuning.
- System-facing re-election: **~2 s** with tuning (12 s default). Sub-1s system-facing is
  achievable-looking but not reliably delivered; it's a focused tuning/stability task.
- Correctness: **zero ID reuse** across every GKE failover.

## Open / next

- Find consistent sub-1s system-facing (heartbeat-timeout sweep between 600–1000 ms, understand
  the 500 ms instability, isolate PVC-reattach vs election-round variance).
- 3 nodes (quota bump) for true node-fault tolerance + the `run-gke-bench.sh aeron-cluster`
  throughput run against the 25,149 baseline (gateway already serves `/orders/batch` + `/metrics`).
