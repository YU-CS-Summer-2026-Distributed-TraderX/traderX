# YU12 kind E2E — one open leg: cluster egress to external-client pods

Status at hand-off (2026-07-17, pre-Shabbat cutoff). The three-member Aeron Cluster RUNS on
kind (`kind-traderx-yu12-cluster`, ns `traderx`): clean election (member roles visible at
`/health` via the API proxy), stable 3/3 Ready on the DNS-await image, members replicate and
re-elect among themselves. The in-process `ThreeMemberClusterTest` proves the full protocol —
election, leader kill, empty-disk rejoin, no-ID-reuse — same-host. On kind, exactly ONE leg
fails: the cluster's egress response to a NON-member client pod.

## Symptom (exact, reproducible)

`ClusterProofClient` in its own pod: `AeronCluster.connect` dies in `state=POLL_RESPONSE`:

```
ingressPublication=Publication{... isConnected=true, position=160 ...}   <- connect request
egress.isConnected=false responseChannel=aeron:udp?rejoin=false|endpoint=10.244.2.10:52971
```

The ingress leg WORKS (publication connected, 160 bytes consumed by the member). The member's
egress response to the client's pod-IP endpoint never connects. No errors in any member's
cluster/service error logs (`ClusterTool /data/cluster errors` clean on the leader).

## Ruled out live

- **Wildcard egress binding** — first failure mode was `endpoint=0.0.0.0:0`; fixed to
  pod-IP-from-downward-API. Ingress then connected; egress still didn't.
- **NetworkPolicy** — deleted entirely; identical failure.
- **Cross-node routing** — proof client pinned to the leader's own node; identical failure.
- **Member↔member UDP** — provably fine (election + log replication work continuously).
- **JVM/Unsafe flags** — fixed earlier (`--add-exports jdk.internal.misc`); all processes run.

## NARROWED (same session, minutes later) — and worked around

Exec-ing the proof client INSIDE the leader pod with a leader-only ingress list CONNECTED and
traded immediately (SEEDED + ACK refs flowing). The broken leg is therefore precisely: the
client wedges when its multi-endpoint connect lands on a FOLLOWER — the follower's egress
redirect (NOT_LEADER + leader info) never reaches the client, on kind only (in-process it
works). Leader-direct ingress + egress both work.

**Workaround shipped** (`ClusterProofClient.connectCycling`): cycle single ingress endpoints
per connect attempt — the leader is found in <= N attempts and re-found after failover. The
gateway/feed clients should adopt the same pattern until the follower-redirect root cause
(probe 1/4 below, now scoped to the redirect egress publication on followers) is closed.

## Further ruled out (session end)

- **hostNetwork client** — identical timeout (so not pod-network ingress filtering).
- **UDP TX-checksum offload** (`ethtool -K eth0 tx-checksum-ip-generic off` on all four kind
  nodes — the classic kind-on-Docker-Desktop UDP blackhole) — identical timeout.

Final shape of the defect: the member's egress/redirect response connects ONLY when the client
is in the member's own pod (proven live: exec'd client traded refs 1..4 through the log).
Toward any other destination — pod IP, host IP, checksums fixed, policies absent — the egress
publication never connects, while client→member ingress connects and commits fine. This now
points at the member-side egress publication itself (creation, setup emission, or the
`rejoin=false` response-channel semantics on these drivers) rather than the fabric. Probe 1
below (driver counters on a member while a connect is pending) is decisive and should run
first next session.

## Next probes (in order, for the next session)

1. Add `aeron-all` (or copy `AeronStat`) to the image and inspect the LEADER's driver counters
   while a connect is pending: does an egress publication to `10.244.x.y:port` get created at
   all, and in what state? That splits "module never responds" from "driver can't deliver".
2. Fixed egress port (env `PROOF_EGRESS_PORT`, declared containerPort) instead of `:0` —
   ephemeral-destination UDP vs Docker-Desktop-kind conntrack is the leading suspect.
3. `hostNetwork: true` proof client — isolates pod-network handling of the return leg.
4. Compare `io.aeron.cluster.client.AeronCluster` egress channel semantics for `rejoin=false`
   against a plain two-pod aeron ping/pong on the same channels (removes the cluster from the
   equation).

## How to resume

```bash
bash scripts/yu12/build-cluster-image.sh
bash scripts/yu12/start-cluster-kind.sh          # cluster likely still up; idempotent
bash scripts/yu12/crash-proof-kind.sh            # blocked only on the client connect
```

The destructive proof script is complete and waiting: once the egress leg connects, it runs
crash 1 → GAP measurement → PVC wipe + empty-disk rejoin → crash 2 → no-REUSE verdict
end-to-end with no further work.
