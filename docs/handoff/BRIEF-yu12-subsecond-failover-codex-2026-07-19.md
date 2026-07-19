# Brief for codeX: YU12 sub-second failover ideation (independent)

You are asked to BRAINSTORM — not implement. Deliverable: write your proposal to
`docs/handoff/PROPOSAL-yu12-subsecond-failover-codex-2026-07-19.md` in this worktree
(`traderX-YU12-aeron-cluster`, branch `YU12-aeron-cluster`). Commit it; never push.
A sibling proposal by the fable lane exists at
`PROPOSAL-yu12-subsecond-failover-fable-2026-07-19.md` — **do NOT read it until your own
proposal is committed** (the two will be cross-critiqued afterwards; independence is the point).

## The ask

Design how to reach, on the live GKE deployment:
- **System-facing failover** (leader process dead -> new leader serving committed orders)
  **< 200 ms, consistently** (current proven: 653-716 ms idle, 724/778 ms under ~30k/s flood).
- **Client-facing failover** (a trading client's last pre-kill ack -> first post-kill ack)
  **< 500 ms, consistently** (current: 838-1657 ms, bimodal).
Treat the numbers as hard targets; if you conclude one is physically unreachable, say why and
give the best reachable bound with your design.

## System under discussion

- 3-member **Aeron Cluster 1.51.0** (Raft): each member runs ClusteredMediaDriver
  (MediaDriver+Archive+ConsensusModule) + ClusteredServiceContainer hosting the deterministic
  MatchingEngine + risk state. The consensus log is the ONLY input (ADR-045); orders, price
  ticks, control, and symbol registration are all sequenced ingress. Egress acks are
  best-effort (bounded offer, drops allowed; committed log is truth).
- GKE `traderx-lmax`, ns `traderx`: StatefulSet `order-matcher-cluster` (3 members packed on
  2 c2-standard-4 nodes, emptyDir disks — Raft quorum is the durability), REST/FIX gateway
  `cluster-gateway` (one AeronCluster client on a single owner thread), test client
  `cluster-proof-client` (submits 20/s, logs ACK/GAP/REUSE lines).
- Consensus timeouts are env-tunable without rebuild (`ClusterNodeConfig.applyTimeoutMs`):
  `CLUSTER_HEARTBEAT_INTERVAL_MS=100`, `CLUSTER_HEARTBEAT_TIMEOUT_MS=400`,
  `CLUSTER_ELECTION_TIMEOUT_MS=200` currently. Snapshots every 60 s (leader toggles the
  SNAPSHOT control counter); NOTE: a snapshot is a log-position barrier costing ~8 s of
  cluster-wide apply stall under flood (A/B measured) — consider interactions with any
  timeout tightening.
- Members log `ROLE-CHANGE role=<R> atMs=<epoch ms>` on role transitions. Members run the JVM
  under an `sh -c` wrapper so a harness can `kubectl exec` + node-clock-timestamp + `kill -9`
  the java pid in one shot — that instrument produced the 653-716 ms numbers (NTP node clocks;
  no pod-delete/poll latency). `/health` exposes memberId/role/applied/trades/snapshots;
  `/ready` gates on catch-up vs peers (CLUSTER_READY_MAX_LAG).

## How the clients work today (both are the same pattern)

`ClusterProofClient` and `ClusterGatewayMain` each hold ONE AeronCluster client and, on
failure, do **endpoint-cycling reconnect**: close the session, then try single-endpoint
connects one member at a time until the leader accepts (`AeronCluster.connect` with a 1 s
`messageTimeoutNs`). Failure is detected as an ACK STALL (no ack for 500 ms while submitting;
Aeron signals session loss as state, not exception), with a grace window after each connect.
Client-facing anatomy today: stall-detect (500 ms) + cycling (burns the 1 s connect timeout
when it tries the dead endpoint first — that is the observed bimodality) + first-ack RTT.

History you must know: endpoint-cycling exists because multi-endpoint connect (full
`ingressEndpoints` list) **wedged on kind** — the follower-redirect leg never completed. That
was diagnosed on Docker-Desktop networking BEFORE the session's biggest root cause was found
(egress channels without `term-length=64k` overflow a container's default 64 MB /dev/shm and
silently never connect). Whether the redirect wedge was that same bug in disguise has NOT been
re-tested on GKE. Also: Aeron Cluster client sessions are replicated state, and the egress
protocol includes a new-leader announcement (`EgressListener.onNewLeaderEvent`).

## Constraints and non-negotiables

- Correctness first: zero order-ID reuse (proven across ~40 kills — keep it), committed-log
  single-input, deterministic replay. Any client resubmit strategy must be idempotency-safe
  (InputEvent carries a clientOrderKey slot; engine retains idempotency state).
- Failover must stay OFF the k8s control plane (Raft-internal). k8s only reschedules corpses.
- The service apply thread is allocation-gated exact-zero; do not add allocation to the hot
  path. GC pauses are therefore small, but the consensus module threads are ordinary.
- One fault at a time (3 members tolerate one loss). Measurement discipline: role-based settle
  (1 LEADER + 2 FOLLOWERS) + catch-up margin between kills; distrust any "instability" finding
  until the measurement stack itself is proven clean — this session produced FOUR false
  conclusions from measurement artifacts (poll skew, quorum-loss kills, client reconnect
  churn, PVC rejoin latency).
- Env-only changes preferred over code where possible; code changes go through the YU12
  runtime-overrides + `pipeline/generate-state.sh YU12-aeron-cluster` + amd64 image build.

## What your proposal should cover

1. Target decomposition: where the remaining ~700 ms (system) and ~1-1.6 s (client) actually
   go, and which term each of your ideas attacks.
2. Concrete mechanisms (Aeron APIs, protocol features, timeout math, client redesign,
   measurement changes) — with the failure modes each introduces and how to falsify them.
3. A recommended implementation order with soak/verification gates.
4. Explicit risks: false-election margins (incl. the snapshot barrier), the redirect-wedge
   unknown, session-survival assumptions.

Key sources in this worktree: `docs/handoff/PROOF-yu12-gke-failover-2026-07-18.md` (all
numbers + instruments), `RECAP-yu12-full-2026-07-19.md`, runtime overrides under
`specs/YU12-aeron-cluster/generation/runtime-overrides/order-matcher/.../cluster/`
(`ClusterNodeConfig`, `ClusterNodeMain`, `ClusterProofClient`, `ClusterGatewayMain`,
`MatchingEngineClusteredService`), GKE manifests under
`specs/YU12-aeron-cluster/generation/kubernetes/cluster/gke/`.
