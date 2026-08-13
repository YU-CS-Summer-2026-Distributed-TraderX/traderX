# Issue: the gateway silently stops committing after a leader kill, and every probe says it is fine

**Status:** OPEN. Reproduced on GKE twice (YU15 `:bench` 2026-08-12, YU16 `:yu16` 2026-08-13) and
**on kind 2026-08-13**, which makes it locally reproducible in about two minutes and removes any
need for cloud spend to fix it. Not diagnosed to a line of code.
**Related:** `HANDOFF-issue-yu-vacuous-pipeline-guards.md` (the proof that found it reported the
wrong cause, §4 below).

> **The first version of this document called this an outage. That was wrong, and wrong in the
> direction that matters.** The gateway does not stop working. It keeps committing orders to the
> cluster and tells every client they failed. See §1.

## §1. What actually happens

Kill the cluster leader while a single gateway is serving traffic. The cluster does everything it
is supposed to: a new leader is elected, all three members stay in lockstep, the applied sequence
keeps advancing. Every subsequent order returns

```
HTTP 504 {"error":"no committed ack"}
```

and it does not stop. But the order **was not refused** — the gateway's ingress path is intact and
the cluster sequences and books it. Only the ack path back to the gateway is gone.

Measured on kind, 2026-08-13, with the cluster's own `next_order_ref` as the witness:

| test | client saw | `next_order_ref` delta | resting orders created |
|---|---|---:|---:|
| 5 orders, 5 distinct `clientOrderId`s | **5 × 504 failed** | +5 | **5** |
| 3 orders, the SAME `clientOrderId` | **3 × 504 failed** | +3 | **1** |

Ten orders sat in the read model in status `NEW` for an account whose client had been told every
single one of them failed:

```
1-876  IBM  1  200.000000  NEW
1-875  IBM  1  200.000000  NEW
...    (ten rows, all reported to the client as 504 no committed ack)
```

So the failure mode is **not lost orders, it is invisible orders**: the client's view and the
book's view diverge silently and permanently, in the direction of the client under-counting its own
exposure. For an OMS that is worse than refusing the order outright, which is at least honest.

**The ClOrdId ledger still protects a retrying client from duplication** — three sends of one key
produced one resting order — so a correctly-implemented client does not double up. It does still
burn a `next_order_ref` per attempt, which matters for §4.

A `kubectl rollout restart` of the gateway clears it instantly with nothing else changed. Same
order, kind, immediately after: `{"orderRef":879,"kind":1}`.

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

0. **It is not a refusal.** Every 504 is a lie: the order is on the log and in the book. A crash
   loses nothing (the client retries into a healthy replica); this creates state the client does
   not know it owns. Position, exposure and risk all drift silently from what the client believes.
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

1. ~~**Make the probe honest first**~~ — **DONE 2026-08-13**, `gateway readiness: report the ability
   to COMMIT, not the state of a socket`. `/ready` now fails after 20 consecutive submits that got
   no committed ack, counted in `submitPipelined` where every ingress path funnels. Proof:
   `scripts/proofs/yu16-ready-tracks-commit.sh`.

   **Correction to what this section used to say:** readiness alone does *not* convert this into a
   pod restart. It removes the pod from the Service — which stops the LoadBalancer feeding a
   gateway that lies, and that is the substantive win — but with a single replica there is nowhere
   else to route, so the outage persists until something restarts the pod. A **liveness** probe on
   the same signal is what would make it self-heal, and is deliberately NOT wired yet: liveness on
   a business-logic signal risks restart storms and wants a much higher threshold. That is an open
   decision, not an oversight.

   Two measured limits on the fix:

   - **It only catches a TOTAL wedge.** A gateway that fails most orders but succeeds occasionally
     never builds a streak, because any ack resets it. Observed live oscillating at 8–12 during a
     partial wedge. That is the intended trade — a rate-based signal would trip under legitimate
     saturation — but it means partial degradation stays invisible.
   - **Under heavy load the probe cannot be served at all** (§5), so the streak never gets read.

2. **§5 first, arguably.** The hang below is more severe than the wedge it hides inside.
2. Then find the session. `submitOrder` returning null means no egress ack arrived for the
   request. Worth checking whether the gateway's egress subscription is re-established on a leader
   change, or whether it stays bound to the old leader's publication.
3. ~~Reproduce on kind if possible~~ — **done, 2026-08-13.** It reproduces reliably:

   ```bash
   CTX=kind-traderx-yu12-cluster GW_SVC=order-matcher-gw \
     bash scripts/proofs/yu12-gke-failover-transparency.sh
   ```

   with the gateway at `replicas: 1` and `execution-algo-engine` at 0. The whole fix-and-prove
   cycle is therefore local and free; the GKE rig is not needed for this at all. The proof's own
   header says "WHY GKE — election behaviour on kind's starved CPUs is not the system's behaviour",
   which is a fair caution about *timing* — but this defect is not a timing claim, and it shows up
   on both rigs identically.

## §5. A worse failure hiding inside it: the gateway stops serving HTTP entirely

Found 2026-08-13 while building the readiness proof. Drive a wedged gateway hard enough — an
unbounded generator, roughly 20 orders/sec — and it stops answering **any** HTTP request, `/ready`
and `/health` included. Not 503. No response at all, connection accepted and never served.

**It does not recover.** Measured: eight minutes with zero load offered, polling every 30 seconds,
still 000 every time. The JVM was alive (PID 1, 23 minutes uptime), the Aeron side was alive (the
control feed kept applying and logging), and TCP kept accepting. Only a restart cleared it.

The mechanism is almost certainly the HTTP executor: every in-flight order parks one of the 64 pool
threads for the full `ACK_TIMEOUT_MS` (10s) plus slack, and under a wedge none of them complete
early. `gateway.yaml` already carries a comment about exactly this shape — the pool was raised from
8 to 64 because "the readiness probe starved behind them and k8s pulled the gateway out of the
Service mid-bench". 64 only moves the cliff; it does not remove it. What is NOT explained is why it
never drains after load stops, which is the part worth investigating: a bounded 12s wait per request
should clear thousands of queued requests in minutes, and it did not clear in eight.

**Why this matters more than the wedge.** It defeats any probe-based fix. A readiness signal the
server cannot serve is not a signal — the pod does go NotReady, but by probe *timeout*, which is
what the old build did too, so nothing is gained and nothing is diagnosable. Under load the honest
503 from §1's fix never gets sent.

Worth considering: serving `/ready` and `/health` from a separate tiny HTTP server with its own
single-thread executor, so the probe path cannot be starved by the order path no matter what the
order path is doing.

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

**And its central accounting is unsound.** On the kind run it reported

```
stream done: 783 acked, 1 needed retries, 0 gave up
next_order_ref: 30 -> 868 (delta 838)
[FAIL] cluster booked 838 orders for 783 acks: 55 DUPLICATED
```

"55 DUPLICATED" is not a duplication count. The proof equates `next_order_ref` delta with orders
booked, and §1 shows a ref is consumed by orders that never rest — a same-key retry burns a ref and
is then suppressed. So the counter measures *ref allocations*, not *bookings*, and the two differ
by exactly the traffic this defect generates.

Note also that only **1** order needed a retry across the whole stream, so those 55 refs cannot be
retry-caused. Something allocated ~55 refs for one client request each without the client seeing a
failure — most likely the gateway resubmitting internally when an ack does not arrive, which would
be the same root cause as §1. **Unverified**: that hypothesis has not been checked against
`ClusterGatewayMain.submitOrder`, and it is the most promising next thread.

Whoever fixes the proof should assert against a booking-grained quantity (open-order count, or the
read model) rather than the ref counter.
