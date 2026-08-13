# Issue: the gateway silently stops committing after a leader kill, and every probe says it is fine

**Status:** OPEN. Reproduced twice on GKE, on two different builds, 2026-08-12 (YU15 `:bench`) and
2026-08-13 (YU16 `:yu16`). Not diagnosed to a line of code — this document records the observation,
the falsification steps already taken, and the reason it is worse than an ordinary crash.
**Related:** `HANDOFF-issue-yu-vacuous-pipeline-guards.md` (the proof that found it reported the
wrong cause, §4 below).

## What happens

Kill the cluster leader while a single gateway is serving traffic. The cluster does everything it
is supposed to: a new leader is elected, all three members stay in lockstep, the applied sequence
keeps advancing. The **gateway** never recovers its cluster session. From that moment every order
returns

```
HTTP 504 {"error":"no committed ack"}
```

and it does not stop. There is no timeout after which it heals. A `kubectl rollout restart` of the
gateway Deployment clears it instantly and nothing else has to change.

## The evidence, 2026-08-13 (YU16 build, project traderx-505400)

Leader `order-matcher-cluster-1` killed at 00:55:14 by
`scripts/proofs/yu12-gke-failover-transparency.sh`. State ~8 minutes later:

| what | reading |
|---|---|
| members | `applied=348` on all three, `engineApplied=348`, leader = member 2 |
| gateway pod | `restarts=0`, `ready=true`, started `04:09:21Z` — **before** the kill, never bounced |
| `GET /ready` | `{"connected":true}` |
| `GET /health` | `{"connected":true}` |
| `POST /orders` | `{"error":"no committed ack"}` |
| gateway log | last line predates the kill. **Nothing** about the leader change, the session, or the 504s |

Same order, immediately after `rollout restart deploy/cluster-gateway` and no other change:

```
{"orderRef":305,"kind":1}
```

## Why this is worse than a crash

1. **The readiness probe cannot see it.** `/ready` reports `connected:true` throughout, so
   Kubernetes never takes the pod out of the Service and never restarts it. A crash would have
   self-healed; this does not.
2. **The load balancer keeps sending traffic to it.** `order-matcher-gw` is a LoadBalancer Service
   with `externalTrafficPolicy: Local`, and the LB health check follows the same readiness signal.
   The one public IP therefore routes every external order into a black hole.
3. **It is silent.** No exception, no reconnect attempt, no log line at any level. Nothing to alert
   on, and nothing for a supporter to find. The OTel work (`yu13-otel-*`) instruments the REJECT
   path, which this never reaches — the order is not rejected, it is un-acked.
4. **The cluster looks perfect while it happens**, which sends an investigator to consensus first.
   That is where the 2026-08-12 run went, and it cost most of an hour.

## What has been ruled out

- **Not the cluster.** All three members agreed on `applied` throughout, before and after, and a
  direct in-cluster order path is unaffected — `yu12-gke-recovery` and
  `yu12-gke-cross-epoch-idreuse` both PASS on the same rig minutes either side of the wedge, and
  both kill leaders themselves.
- **Not the YU16 build.** First seen on YU15 `:bench`, reproduced on YU16 `:yu16`. Nothing in the
  bond work touches session handling.
- **Not the load balancer.** The 504 body is the gateway's own (`ClusterGatewayMain.handleOrder`
  returns it when `submitOrder` yields no `ExecResult`), so the request reached the gateway and the
  gateway answered.
- **Not slow recovery.** It persisted ~8 minutes across repeated requests and did not self-heal.

## The condition that hides it

**One gateway.** The GKE correctness rig runs `replicas: 1` because the gateway Deployment carries
`requiredDuringSchedulingIgnoredDuringExecution` anti-affinity on hostname and the 32-vCPU quota
leaves exactly one untainted node. With more than one gateway the survivors keep serving and the
wedged replica's share of traffic looks like an elevated error rate rather than an outage — which
is very likely why a campaign that ran four gateways never surfaced this.

That makes the single-gateway rig the *useful* configuration for this bug, not a degraded one.

## Next steps for whoever picks this up

1. **Make the probe honest first**, before diagnosing the session. `/ready` returning
   `connected:true` while no order can commit is a bug on its own and it is the reason nothing
   self-heals. A readiness signal for an ingress process should reflect its ability to commit, not
   the liveness of a socket — e.g. fail readiness after N consecutive `no committed ack`s. That
   alone converts a silent permanent outage into a pod restart.
2. Then find the session. `submitOrder` returning null means no egress ack arrived for the
   request. Worth checking whether the gateway's egress subscription is re-established on a leader
   change, or whether it stays bound to the old leader's publication.
3. Reproduce on kind if possible — it would make this a committed proof rather than a GKE-only
   observation. `yu12-gke-failover-transparency.sh` is the driver; it kills the leader mid-stream.

## §4. The proof reported the wrong cause

`yu12-gke-failover-transparency.sh` announced:

```
[FAIL] 1 orders were never acknowledged even after retries — the outage was not transparent
```

That verdict is about **consensus transparency**, and consensus was fine. Its retry loop cannot
distinguish a dead connection from a cluster refusal — `curl -s` with no output and a cluster
rejection both land as an empty `out` — so it attributes any failure to the failover. On the
2026-08-12 run it reported `0 acked` including in the 20 seconds *before* the kill, which should
have been impossible to read as a failover result at all.

The finding was real; the attribution was not. This is rule 7 of the vacuous-pass audit and the
script has not yet been fixed.
