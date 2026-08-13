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
   else to route, so the outage persists until something restarts the pod.

   **1b. The other half — DONE 2026-08-13.** The liveness probe is now wired, and the decision it
   was waiting on is recorded here. `/live` fails at `LIVE_NO_ACK_STREAK` (default 5× the readiness
   limit = 100) and the manifest demands `failureThreshold: 6 × 10s` on top, so the kubelet
   restarts the container only after ~60s of continuous inability to commit. Proof:
   `scripts/proofs/yu16-liveness-restarts-wedge.sh` (passes; `restarts 0 -> 1` at streak 144, the
   kubelet's own `Liveness probe failed` event naming the cause).

   Why this is not the restart storm the decision was worried about, in three properties:

   - **It ignores `connected`.** A closed session is not a restart reason — an election or a member
     roll closes it and the owner thread reconnects on its own. Restarting on that would restart
     every gateway on every failover.
   - **It cannot fire on an idle gateway.** The streak only advances on a submit that got no ack, so
     a cluster-wide outage with no traffic offered restarts nothing.
   - **It is reached by volume, not by elapsed time,** and then has to persist for a minute.

   The residual risk is real and accepted: a cluster-wide outage *under live load* will restart
   gateways, because from the gateway's side that is indistinguishable from its own wedge. It costs
   the in-flight orders of a tier that was not committing anything anyway, and the kubelet's restart
   backoff caps the flap rate — against a single public IP routing every order into a gateway that
   books what it denies.

   A **startupProbe** landed with it (`/live`, 120s), which is what let the readiness
   `failureThreshold` drop 24 → 3: the old 24 existed purely to tolerate a slow JVM+Aeron start,
   i.e. two minutes in which an unready gateway stayed in the Service. It reads `/live` and not
   `/ready` deliberately — `/live` is 200 as soon as the process serves, so a cluster that is down
   while this pod boots cannot hold startup open until the kubelet kills it.

   **What liveness still does not catch:** the same partial-degradation blind spot as readiness
   (any ack resets the streak), and an idle wedged gateway — which is correct but means the first
   ~100 clients after a quiet wedge are still lied to before the restart is asked for.

   Two measured limits on the fix:

   - **It only catches a TOTAL wedge.** A gateway that fails most orders but succeeds occasionally
     never builds a streak, because any ack resets it. Observed live oscillating at 8–12 during a
     partial wedge. That is the intended trade — a rate-based signal would trip under legitimate
     saturation — but it means partial degradation stays invisible.
   - ~~**Under heavy load the probe cannot be served at all** (§5), so the streak never gets
     read.~~ — **fixed 2026-08-13**, see §5.

2. ~~**§5 first, arguably.**~~ Its *probe* half is done (separate probe server). The hang itself is
   still undiagnosed and is still more severe than the wedge it hides inside.
2. Then find the session. `submitOrder` returning null means no egress ack arrived for the
   request. Worth checking whether the gateway's egress subscription is re-established on a leader
   change, or whether it stays bound to the old leader's publication.
3. ~~Reproduce on kind if possible~~ — **done, 2026-08-13.** It reproduces reliably:

   ```bash
   CTX=kind-traderx-yu12-cluster GW_SVC=order-matcher-gw \
     bash scripts/proofs/yu12-gke-failover-transparency.sh
   ```

   **For §1's divergence specifically, do NOT use this route.** Quorum loss produces the same
   booked-but-denied orders deterministically in ~90 seconds with no leader kill and no race — 159
   resting orders for 160 clients told 504, measured 2026-08-13. See the drain experiment in §5.
   The wedge is only needed for §5's hang.

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

**The probe half is fixed, 2026-08-13.** `/ready`, `/health` and `/live` are now also served by a
separate `HttpServer` on `GATEWAY_PROBE_PORT` (18111) with its own single-thread executor, and every
probe in `gateway.yaml` reads that port. They stay registered on 18110 too, so the proofs and
benches that curl it are untouched. Asserted directly rather than assumed: step 2 of
`yu16-liveness-restarts-wedge.sh` takes the reading *while* 80 concurrent orders (the pool is 64)
are parked on acks that will never arrive, and the probe port answered 200/503 throughout. So the
verdict Kubernetes now acts on is the gateway's own, not a timeout.

**The hang itself is NOT fixed and is still not diagnosed** — the order path still fills up and
still does not drain after load stops. What changed is that it is now survivable without a human:
liveness fails on the streak (or, if the JVM itself is gone, on timeout) and the kubelet restarts
the container, which is the only known cure. The open question is unchanged and still worth
answering: why a bounded 12s wait per request never clears in eight minutes.

**And the liveness proof does not answer it — do not read it as if it did.** Its step 4 commits an
order after a 160-order drive, which looks like evidence the backlog drains; it is not, because the
restart under test kills the owner queue first and step 4 always meets a fresh JVM. §6 has the
reasoning.

### The drain experiment, run 2026-08-13 — and what it does and does not settle

Run on kind with `LIVE_NO_ACK_STREAK=100000` so no restart could intervene: lose quorum, drive
2 × 80 concurrent orders (streak reached exactly 160), restore quorum, then stop **all** load and
watch. Result:

> **First committed order at +0s after quorum returned, `restarts=0`, streak already back to 0.**

So on the pipelined tier, under quorum loss, **the abandoned-task backlog is not self-sustaining**.
160 abandoned tasks did not cost 160 × `ACK_TIMEOUT_MS` of owner-thread time, because the per-task
10s is spent only while the cluster refuses the offer — once quorum is back each queued task offers
in microseconds and the queue evaporates. The "queue that cannot drain" hypothesis is dead for this
shape.

**It is NOT an answer to §5, and must not be read as one.** §5's hang was the leader-kill WEDGE
under a sustained generator, in which the gateway stopped answering *all* HTTP and had not
recovered after eight minutes with zero load offered. Quorum loss induces the same *property*
(nothing can commit) but evidently not the same *mechanism* — this run recovered instantly where
that one never did. §5's mechanism remains unreproduced and unexplained. What is now known is
narrower and still worth having: whatever §5 is, it is **not** simply a deep owner queue.

### The side finding, which is worth more than the drain answer: §1 has a DETERMINISTIC repro

The same run reproduced the invisible-orders defect exactly, in about 90 seconds, with no leader
kill and no race:

| witness | reading |
|---|---|
| submits that got no committed ack | **160** (the gateway's own `noAckStreak`, so every one of those clients was answered 504) |
| `traderx_book_open_orders` | 51 → **211** |
| `traderx_cluster_next_order_ref` | **212** |
| member agreement | all three identical: `applied=7568 open=211 nextRef=212 hash=-734721819140448701` |

**159 of the 160 orders every client was told had failed are resting in the book.** (One did not
consume a ref — its offer never cleared before the deadline.) That is §1, on demand, without the
1-in-4 wedge race the rest of this document is built around. Anyone working on §1 should use quorum
loss to produce the divergence and stop hunting the wedge for it; the wedge is only needed for §5.

## §6. Where the fix has actually run, and where it still has not

The probe work was written once and back-ported to YU12–YU15 and the GKE tier. "Back-ported and
renders correctly" is not "has run", and the two are recorded separately here on purpose — a
manifest that renders and a funnel that compiles are the two failure modes this project keeps
paying for. Ticked off 2026-08-13.

### YU12's gateway — VERIFIED on the kind rig, 2026-08-13

`646b1e1d` was generate + `compileJava` clean and nothing more. It matters separately from YU13's
back-port (which is operative for YU13–YU15 and had run) because **YU12 is a different program**:
it has no `submitPipelined`, so the no-ack streak is hooked into `submitOrder` instead — a
synchronous funnel with no in-flight window, where every submit round-trips through the owner
thread. That funnel had never been exercised anywhere.

Run on `kind-traderx-yu12-cluster` on YU12's own image (`traderx/cluster-node:yu12`, built from
`traderX-YU12-aeron-cluster`), members and gateway both on that build, fresh epoch. Both YU16 proof
scripts, run directly from the YU16 worktree — YU12 carries no `scripts/proofs`, and
`scripts/yu15/run-proofs.sh` must NOT be used for this because it pins `BASELINE_IMAGE` and would
silently rebuild the rig onto the baseline before reporting.

| | reading |
|---|---|
| pod comes up | Ready, kubelet driving all three probes at 18111 |
| `yu16-ready-tracks-commit.sh` | **PASS** — control 25 orders/streak 0/200; quorum gone 503 at streak 25; discriminator held (`connected:true` with /ready still 503); one committed order (`orderRef 50`) cleared it |
| `yu16-liveness-restarts-wedge.sh` | **PASS** — control 80 concurrent (> the 64 pool) left /live 200 and the pod untouched; probe port answered 200 under saturation both rounds; /live 503 at streak 144; `restarts 0 -> 1`, `reason=Error` not OOMKilled, kubelet event `Container gateway failed liveness probe, will be restarted`; recovered order `orderRef 131` |

**The synchronous tier accumulates the streak at the same rate as the pipelined one**, which was the
open question and the reason the run was worth doing. 80 concurrent orders produced streak 80 in one
round and 144 in two — the YU16 shape exactly. Each submitter's own `ft.get()` expires at
`ACK_TIMEOUT_MS + 2s` regardless of where its task sits in the owner queue, so queue position does
not slow accumulation, and the `LIVE_NO_ACK_STREAK` env knob was not needed. The readiness run makes
it exactly 1:1: 25 orders, streak 25.

**What this run does NOT establish**, and should not be read as establishing: whether a synchronous
gateway drains its abandoned-task backlog. Every timed-out submitter leaves a task queued on the
owner thread, and on this tier each can burn a full ack timeout. The recovered order in step 4
committed fine — but the restart under test kills the container, and the owner queue with it, so
step 4 always meets a fresh JVM by construction. Measuring the drain needs a drive with no restart
(`LIVE_NO_ACK_STREAK` raised above the reachable streak, then watch whether an order commits after
load stops).

**That experiment has since been run, ON THE PIPELINED TIER — see the drain subsection at the end
of §5, which is the canonical result.** There the backlog drains instantly: the ack timeout is only
spent while the cluster refuses the offer, so a restored quorum empties the queue in microseconds.

**It does not close the question this section asked**, which was about the SYNCHRONOUS gateway. The
mechanism measured is `offerPipelined`'s retry loop — offer, pollEgress, check deadline — and YU12
does not have it: `submitOrder0` → `onOwner` → `offerAndAwait` waits on an ack condition, not on the
offer clearing. The same fast drain is plausible there and is not measured. Left explicitly
unmeasured rather than inferred; it is the same script with the rig on `:yu12`.

What the pipelined result does settle reaches further than drain, and is the reason it is
cross-referenced here at all: it retired §5's own proposed mechanism. Whatever that hang is, it is
not merely a deep owner queue.

### The GKE tier — VERIFIED on a live cluster, 2026-08-13

`15e758df`/`f40d110e`/`9bb7b5dc`/`0d7fab5c`/`86552494` wired startup + readiness + liveness at 18111
into each branch's `gke/gateway.yaml`. Those had rendered and had never met a kubelet. They have now:
cluster `traderx-bench` in `traderx-505400`, YU16 build at `:yu16`, three members and one gateway at
`replicas: 1` behind the LoadBalancer — the configuration the wedge was first observed in.

**The probes behaved correctly in every respect checked, and the change did not destabilise a
healthy tier** — which was its main risk:

| what | reading |
|---|---|
| pods Ready, `containerPort: 18111` present | yes; gateway `restarts=0` through the whole healthy period |
| probe failures during normal operation | none. The only `Unhealthy` events were startup during JVM boot and readiness while quorum was genuinely absent |
| `GATEWAY_PROBE_PORT` on the serving pod | `18111` — `a2781db7`'s pin, exercised by a kubelet for the first time |
| `yu16-ready-tracks-commit.sh` | **PASS** — 503 at streak 25, discriminator window held, cleared by `orderRef 51` |
| `yu16-liveness-restarts-wedge.sh` | **PASS** — `/live` 503 at streak 144, `restarts 4 -> 5`, `exitCode 143`, recovered with `orderRef 452` |

Three GKE-specific readings worth keeping, because none of them could have been taken on kind:

- **The 120s startup budget has about 2x headroom on real hardware, not a lucky fit.** The
  startupProbe recorded 12 `connection refused` failures at `periodSeconds: 5` — ~60s from container
  start to first answer on 18111 — against a budget of 24 x 5s. GKE's JVM+Aeron boot is the slow
  case this constant exists for, and it clears it twice over.
- **Liveness did not fire on an idle gateway that genuinely could not commit.** Before the members
  were scheduled, the gateway sat ~10 minutes unable to commit anything with zero traffic offered,
  and was never restarted. That is the anti-storm property (§1b) confirmed on the tier where a
  restart storm would actually cost something, rather than argued from the code.
- **`/ready` and `/live` disagreeing is visible and correct.** With quorum gone and no traffic,
  `/ready` was 503 (it counts `connected`) while `/live` stayed 200 (it deliberately ignores
  `connected`). The distinction that keeps an election from restarting every gateway is observable,
  not just intended.

**THE LANDMINE HAS A TAIL, and `apply -k` does not clear it.** Fixing the manifest and recreating the
pool got the *StatefulSet spec* right, and members 0 and 1 still would not schedule: the existing
pods carried the hand-patched `nodeSelector: blp-compact` from the deleted pool, and a pod's node
selector is immutable. `apply -k` updated the spec and left two pods Pending against it —
`0/4 nodes are available: 4 node(s) didn't match Pod's node affinity/selector` — while the third,
which the apply had recreated, ran fine. **Deleting the stale pods is part of the fix**; without it
the tier comes up one-third scheduled and looks like a quota problem.

**What the run cost, and what it says about the proofs rather than the gateway.** The gateway
behaved correctly in every run; `yu16-liveness-restarts-wedge.sh` failed three of them, on four
separate assumptions that were true on kind and false here. All four are fixed in `8634eb01` and
the detail is in that commit. The pattern is the durable part and it is not "test on GKE":

> A proof's timing constants and its string matching are claims about the ENVIRONMENT, and a
> passing run does not falsify them. Every one of the four was green on kind for a reason unrelated
> to the property it asserted — an event string the kubelet's rate limiter structurally cannot
> deliver on this path, a reconnect budget sized for kind's boot, a `port-forward` never re-spawned
> after the restart it exists to watch, and a 20s wait defending against a 60s failure mode.

That is a different class from the vacuous-pass audit's existing rules, which are about assertions
being too *weak*. These assertions were strong; they were about the wrong universe.

**The event half of that has a consequence in PRODUCTION, not just in the proof.** The saturation is
structural: readiness fails at 20 and liveness at 100, so by the time liveness fires, readiness has
been failing for ~80 further no-ack submits and has long since spent the shared `reason=Unhealthy`
budget. Measured on GKE: `Unhealthy count=150` for readiness, and **no liveness `Unhealthy` event at
all**. So when this probe restarts a real gateway, the `Liveness probe failed` event will usually be
missing there too — anyone who builds alerting on that string for this gateway gets silence while
the restart is happening. The reliable signals are the `Killing` event (`Container gateway failed
liveness probe, will be restarted`), emitted once per kill and so not subject to that pressure, and
the container's own `restartCount`. This is a consequence of choosing `LIVE_NO_ACK_STREAK` well
above `READY_NO_ACK_STREAK`, which is deliberate for every other reason (§1b) — it is worth the
trade, but the alerting has to key off the right event.

Recorded here rather than as a comment in the five `gateway.yaml` copies on purpose: this document
already exists on every branch that carries the defect, so it reaches the same readers, and a
comment maintained in five places is exactly the divergence surface the lineage rule punishes.

**The rig itself.** Two facts about the cloud that cost most of an hour and are not about this
issue at all, kept because the next person will meet them: `us-east1-b` had no `c2d-standard-8`
capacity (`ZONE_RESOURCE_POOL_EXHAUSTED` / `GCE_STOCKOUT`), and a `CREATE_NODE_POOL` operation
**cannot be cancelled and blocks every other operation on the cluster while it retries** — 35
minutes, with `node-pools delete` returning `Cluster is running incompatible operation` throughout.
So a stockout is not a fast failure to retry around. Check
`gcloud compute instance-groups managed list-errors <mig>` early rather than reading a long
`PROVISIONING` as slow provisioning. The pool that eventually came up is `n2-standard-8` without
`--placement-type COMPACT`; compact placement narrows eligible hosts and `76c45bbb` already found
it is not the latency lever, so a correctness rig should not ask for it.

**The `blp-c4d-tuned-pool` landmine is now fixed at the source.** `gke/statefulset-emptydir.yaml`
pinned a pool that a compact-placement experiment had deleted; the live StatefulSet only worked
because someone had hand-patched the selector, and any fresh `apply -k` would have left all three
members Pending on "didn't match Pod's node affinity/selector". Fixed by recreating the pool under
the name the manifests expect, which repairs every branch at once rather than baking an
experiment's pool name into the tier. The machine-type divergence that comes with it (c2d, not c4d,
because CPUS_ALL_REGIONS is 32) is recorded at the `nodeSelector` itself.

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
