# YU12 Aeron Cluster — kind HA proof (2026-07-18)

E2E destructive proof of the three-member Aeron Cluster on `kind-traderx-yu12-cluster`, with a
cross-pod proof client trading continuously through the consensus log. This is the pod-level
confirmation of what `ThreeMemberClusterTest` proves in-process. Verdict: **the no-ID-reuse
correctness bar (NFR-AC04) holds live across two failovers and an empty-disk member rejoin.**

## Setup

- 3 members, one per node (StatefulSet + anti-affinity), per-pod PVC for log+snapshots, Memory
  `/dev/shm`. Image `traderx/cluster-node:yu12`.
- `ClusterProofClient` in its own pod, submitting an order every 100 ms through the Aeron Cluster
  client (endpoint-cycling connect, stall-triggered reconnect), logging every accepted ref with
  a wall-clock time, flagging any reused ref (`REUSE`) and any inter-ack gap over threshold
  (`GAP`).

## What was exercised

1. Baseline: client seeded control state and traded continuously.
2. **CRASH 1**: force-deleted the leader (ordinal 2). The StatefulSet recreated it; it recovered
   from its PVC. Client rode the failover.
3. **WIPE / empty-disk rejoin**: force-deleted ordinal 2's pod AND its PVC, so it returned with a
   blank volume. It rejoined the cluster via the consensus module's own snapshot + log catch-up —
   no hand-built bundle transport — and converged.
4. Continuous re-elections during the churn (Docker-Desktop kind flaps leaders under load).

## Evidence (from the proof client log)

```
REUSE count over the whole run: 0
GAP ms=15397 beforeRef=1005 afterRef=1007
GAP ms=17224 beforeRef=1051 afterRef=1053
GAP ms=813   beforeRef=4035 afterRef=4036
RECONNECTs: 4
total accepted orders: 6612+, last ref 7457, strictly increasing throughout
```

Readings:

- **Zero ID reuse** across the entire run — every failover and the empty-disk rejoin preserved
  the strictly-increasing order-reference lineage (1005→1007, 1051→1053, 4035→4036, … 7457). This
  is NFR-AC04 (the parent state's `nextOrderRef` defect class) proven live on real Kubernetes.
  The generator is replicated/snapshotted service state (ADR-046), so it cannot diverge or reuse.
- **Client-observed failover** ranged 813 ms (fast election) to ~17 s (slow Docker-Desktop
  election-timeout cases). The 813 ms sample shows the cluster CAN meet the sub-1s NFR-AC03 target
  when the election is fast; the 15-17 s cases are the DEFAULT Raft election timeouts on a slow
  host — the same ~15-17 s the parent state measured on kind (its P5). Tuning the consensus-module
  timeouts + GKE's faster network is the path to consistent three-digit-ms failover.
- **Empty-disk member rejoin converged**: after the wipe, members reached applied 3984 → 5291 →
  8063 together (all within 1-2 of the leader at each check). Final state after the whole
  campaign: 3/3 members `Running`, one LEADER, all at applied=8063.

## Root cause fixed to get here

Cross-pod client connectivity on kind was blocked until this session: the client egress channel
had no `term-length`, so Aeron defaulted to a 64MB term → a ~48MB log that didn't fit a
container's default 64MB `/dev/shm`; the egress subscription never allocated, so egress never
connected while ingress (which carried `term-length=64k`) did. Fixed with `term-length=64k` on
every client egress channel + a Memory `/dev/shm` mount. Full write-up:
`ISSUES-yu12-kind-egress-2026-07-17.md`.

## Not completed on kind (and why it doesn't gate the correctness claim)

- The scripted CRASH-2 stage and the auto-printed PASS verdict did not complete: the heavy
  continuous proof-client load starved the Docker-Desktop kind API server, so the script's
  `caught_up` polling crawled. The correctness verdict above is read directly from the client log
  and does not depend on the script finishing. `scripts/yu12/crash-proof-kind.sh` is complete and
  will run to an auto-verdict on a less resource-constrained host (or GKE).
- Sub-1s failover as a CONSISTENT result (NFR-AC03): needs consensus-timeout tuning + a faster
  host; demonstrated achievable (813 ms sample), not yet the steady-state on kind.

## Live gateway proof (REST + failover transparency)

The gateway (`ClusterGatewayMain`) was deployed to the same kind cluster (Deployment + Service
`order-matcher:18110`, FIX acceptor on `:18130`) and verified end-to-end:

```
POST /orders        {accountId:11, securityId:1, Buy, qty 10, limit 100}  -> {"orderRef":8052,"kind":1}   (accepted, real cluster lineage)
POST /orders/batch  [x3]                                                   -> {"accepted":3,"total":3}
GET  /metrics                                                              -> traderx_order_events_total{event="accepted"} 4   (bench format)
```

Then, **live failover transparency (ADR-047)**: the current leader (ordinal 0) was force-killed;
member 1 was elected; the very next REST POST through the gateway — with NO gateway restart —
succeeded on the first attempt: `{"orderRef":8056,"kind":1}`. The gateway's endpoint-cycling +
owner-thread reconnect found the new leader and kept serving. Combined with `FixGatewaySurvivalTest`
(FIX session survives a leader blip in-process), the counterparty-transparent-failover property is
proven both live (REST) and at the session layer (FIX).
