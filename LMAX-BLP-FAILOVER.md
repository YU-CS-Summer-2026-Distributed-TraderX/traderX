# TraderX — BLP Failover in Kubernetes: Design, Choices & Tradeoffs

> **Branch:** `YU02-lmax-kubernetes`
> **Date:** 2026-07-01 · **Revised 2026-07-14** — election contract rewritten after a
> lease-starvation false-demote was reproduced under saturating load (see §10).
> **Companion docs:** `LMAX-BLP.md`, `LMAX-SEQUENCER-ARCHITECTURE.md`, `CLAUDE.md`,
> `SPEC-blp-ha-lease-starvation-fix-FINAL.md` (local working note)

---

## Overview

The BLP (Business Logic Processor) is a **single-threaded, in-memory, event-sourced** order matcher.
There is exactly one running instance at any moment — the **PRIMARY**. A second pod runs as a
**FOLLOWER** that stays warm by consuming the primary's event stream. When the primary's pod dies,
the follower proves the death via the Kubernetes Pod API and promotes itself in **~2–3 s**; a
wedged-but-alive primary is displaced via Lease expiry in **≤15 s**.

This document explains every design decision, why it was made, and what it costs.

---

## Architecture

```
 ┌────────────────────────────────────────────────────────────┐
 │                 Kubernetes (traderx namespace)             │
 │                                                            │
 │  trade-service ──► order-matcher-primary (Service)         │
 │                         │ selector: blp-role=primary       │
 │                         │                                  │
 │                    ┌────▼──────────────┐                   │
 │                    │  order-matcher-0  │  PRIMARY           │
 │                    │  (Lease holder)   │──────────────────┐ │
 │                    └───────────────────┘                  │ │
 │                                                           │ │
 │                    ┌───────────────────┐   NATS JetStream │ │
 │                    │  order-matcher-1  │◄─────────────────┘ │
 │                    │  (FOLLOWER)       │  TRADERX_BLP_      │
 │                    └───────────────────┘  REPLICATION       │
 │                                                            │
 │  ┌─────────────────────────────────────────────────────┐  │
 │  │  coordination.k8s.io/v1 Lease: order-matcher-leader │  │
 │  │  holderIdentity: order-matcher-0                    │  │
 │  │  leaseDurationSeconds: 15                           │  │
 │  └─────────────────────────────────────────────────────┘  │
 └────────────────────────────────────────────────────────────┘
```

---

## Component Inventory

| Component | What it is | Where it lives |
|---|---|---|
| `LeaderElection.java` | Two daemon threads: 100 ms heartbeat (never blocks on HTTP) + 2 s lease renew/poll; demote-on-proof renewal state machine; pod-GET fast path | `specs/YU02-lmax-kubernetes/.../lmax/LeaderElection.java` |
| `NatsJournalReplicator.java` | Disruptor `EventHandler` on the PRIMARY's input ring; publishes each `InputEvent` to JetStream | same package |
| `LmaxEngine.java` | Wires it together: reads `BLP_REPLICATION_ENABLED`, starts `LeaderElection`, conditionally installs the replicator | same package |
| `ProjectorHandler.java` | Output-ring handler; gates DB writes and NATS output publishing on `replicationRole.isFollower()` | same package |
| `order-matcher-statefulset.yaml` | `replicas: 2`, `volumeClaimTemplates`, pod anti-affinity, env vars | `cluster-addons/` |
| `order-matcher-primary-service.yaml` | ClusterIP Service with `selector: { app: order-matcher, blp-role: primary }` | `cluster-addons/` |
| `order-matcher-rbac.yaml` | ServiceAccount + Role (get/create/update Lease) + RoleBinding | `cluster-addons/` |
| `nats-configmap.yaml` | Adds `jetstream { max_memory_store: 512MB }` to the NATS broker | `specs/YU02-lmax-kubernetes/.../kubernetes-runtime/manifests/base/` |

---

## Normal Operation

### Startup sequence

1. Both pods start. `LmaxEngine` recovers from its per-pod journal/snapshot PVC.
2. `LeaderElection.start()` starts two daemon threads: `blp-heartbeat` (100 ms tick, NATS only,
   never touches HTTP) and `blp-lease` (2 s tick, does the k8s API I/O).
3. `tryAcquire()` races both pods to POST a new Lease (k8s enforces atomicity — only one 201).
   The winning pod sets its role to `PRIMARY`. If a pod cannot reach NATS or bring up a working
   JetStream replicator, it **fails closed**: stays FOLLOWER/REFUSING and retries on a backoff
   loop (`scheduleReplicationRecovery`) rather than serving unreplicated.
4. PRIMARY: patches its own pod label `blp-role=primary` via the k8s Pod API.
5. `order-matcher-primary` Service now has exactly one Endpoint: the primary pod.
6. PRIMARY installs `NatsJournalReplicator` on its Disruptor input ring and starts publishing.
7. FOLLOWER subscribes to the JetStream stream and starts consuming events, keeping its BLP state in sync.

### Steady state

- PRIMARY renews the Lease every 2 s with a **single optimistic PUT** against a cached
  `resourceVersion` (no per-renewal GET). On success it stamps `lastSuccessfulRenewNs`.
- Renewal failures are classified, not fatal: a timeout/5xx/ambiguous-409 is **retried** on the
  next tick; the primary demotes only on **proof** — a foreign `holderIdentity` in the Lease, or
  `RENEW_DEADLINE_SECONDS` (10 s) elapsed since the last confirmed renewal. The old behavior
  (demote on the first failed PUT) is what caused false-demotes under CPU saturation.
- Every gateway admission passes the **synchronous admission gate** in `LmaxEngine`
  (`guardPrimaryAdmission`): orders are admitted only while
  `role == PRIMARY && age(lastSuccessfulRenew) < renewDeadline`. This is the single-writer
  boundary — kubelet readiness is just the slower traffic-draining layer.
- FOLLOWER polls the Lease every 2 s as a backup detection path (heartbeat is the fast path).
- The `NatsJournalReplicator` publishes every input event in the same 64-byte binary layout used
  by the on-disk journal, so the follower's BLP applies events in an identical order.

---

## Failover Sequence

When the primary pod crashes or is deleted (**pod-GET fast path**):

1. The primary's heartbeat goes silent; the follower notices within 500 ms.
2. The follower GETs the holder's **Pod object** (holder name == pod name; pod-read RBAC exists):
   - **404** → the process provably isn't running → steal the Lease immediately.
   - **`deletionTimestamp` set** → wait a 1 s guard (covers the kubelet-sets-timestamp-before-
     SIGTERM race), then steal.
   - **Pod alive** → no early theft; fall through to Lease expiry (below).
3. Follower atomically PUTs the Lease with its own pod name as `holderIdentity`.
4. Follower sets `role = PRIMARY`, patches its pod label `blp-role=primary`.
5. `order-matcher-primary` Service detects the label change and flips its Endpoint.
6. Follower installs `NatsJournalReplicator` and starts publishing new events.

**Real pod death (fast path):** heartbeat silence (500 ms) + pod GET + Lease PUT + label patch
= **~2–3 s** end-to-end.

**Wedged-but-alive primary (slow path):** the pod object still exists, so the follower waits for
Lease expiry — `LEASE_DURATION_SECONDS` (15 s) + poll interval = **≤ ~17 s**. This is the
deliberate price of false-demote immunity: the primary self-fences at 10 s (renew deadline), a
follower may steal at 15 s, so there is a guaranteed 5 s no-overlap margin. A JVM waking from a
long stop-the-world pause refuses its first admission at the gate (renewal age > deadline) before
its election thread even runs.

> **Note on the "gap"**: between primary death and follower promotion, the `order-matcher-primary`
> Service has no healthy Endpoints. New order requests from `trade-service` will fail with a
> connection refused error. This is intentional — the alternative (serving from a follower before
> it is confirmed primary) risks processing the same order twice.

---

## Key Design Choices

### 1. Kubernetes Lease API for leader election

**What we use:** `coordination.k8s.io/v1 Lease` resource, raw `java.net.http.HttpClient` with the
pod's service account token.

**Why not ZooKeeper / etcd / Redis:** Adding an external coordination store to a GKE research
deployment adds infrastructure that the rest of TraderX doesn't need. The k8s API server is already
the authoritative control plane — it has linearizable PUT semantics and manages our pods anyway.

**Why not a k8s client library (Fabric8, Operator SDK):** Zero extra JAR dependencies. The Lease
API is a single POST/PUT/GET against a stable Kubernetes API. The raw `HttpClient` approach is
~100 lines and is fully auditable.

**Tradeoff:** The raw HTTP approach requires careful handling of `resourceVersion` for optimistic
concurrency (a k8s PUT without `resourceVersion` returns 422) and `MicroTime` precision (k8s
requires microseconds, Java's `Instant.toString()` produces nanoseconds and is rejected with 400).
A client library would hide these details, but at the cost of a ~10 MB transitive dependency.

---

### 2. NATS JetStream as the replication transport

**What we use:** A single durable in-memory JetStream stream (`TRADERX_BLP_REPLICATION`,
subject `traderx.blp.replication.events`, max age 1 day).

**Why JetStream, not plain NATS:** Plain NATS publish-subscribe is ephemeral — a follower that
restarts would miss events published while it was down. JetStream is a persistent, sequenced log,
so the follower can resume from where it left off using a stored stream sequence.

**Why in-memory storage, not file storage:** This is a research demo, not a production system.
In-memory JetStream avoids disk I/O overhead and simplifies the NATS broker configuration. A
production deployment would use `storageType: File` so the replication log survives a NATS pod
restart.

**Why not replicate via the journal file directly:** Each pod has its own `ReadWriteOnce` PVC.
Kubernetes doesn't allow two pods to mount the same RWO volume simultaneously. NATS JetStream is
a clean message-bus approach that doesn't require shared storage.

**Tradeoff:** In-memory JetStream means the replication buffer is lost if the NATS broker restarts.
The follower falls back to full journal replay from snapshot in that case (bounded by snapshot
interval — 5 minutes maximum replay).

---

### 3. Follower catches up via JetStream sequence in snapshot

**`SnapshotStore` v2** embeds a `jetsStreamSeq` field. When a follower (or primary) takes a
periodic snapshot, it records the last JetStream sequence it consumed. On the next startup as a
follower, it subscribes from that offset rather than from message 0.

**Why this matters:** Without this, every follower restart would replay the entire JetStream
stream (up to 1 day of events) before becoming current. With `jetsStreamSeq`, the catch-up
window is bounded by the snapshot interval (5 minutes).

**V1 snapshots** (written before this change) return `jetsStreamSeq = -1`, which triggers full
replay — safe but slower.

---

### 4. Follower gates all output writes

`ProjectorHandler` checks `replicationRole.isFollower()` before every DB write and every NATS
output publish. The follower processes identical events through the same BLP, keeping state warm,
but suppresses all side effects.

**Why:** Trades and positions must only appear in the DB once. If both pods wrote to MariaDB, rows
would be duplicated (or trigger upsert conflicts). Only the primary is allowed to produce output.

**What the follower DOES do:** it applies every `InputEvent` to the in-memory BLP (order book,
positions, trade counter). This is the "warm standby" — the follower's in-memory state is
effectively identical to the primary's, so promotion is instantaneous (no replay from journal
required when becoming primary after a failover).

---

### 5. NATS heartbeat for fast failure detection

**The problem with Lease-only detection:** The k8s Lease is renewed every 2 s and expires after
15 s, giving a worst-case detection window of ~15 s plus the poll interval. The Lease API also
requires an HTTP round-trip to the k8s API server, adding latency.

**The solution:** The primary publishes a plain NATS message on `traderx.blp.heartbeat` every
100 ms **from a dedicated thread that never performs HTTP** — so a slow Lease renewal can no
longer silence the heartbeat (in the original single-thread design, one blocking renewal produced
two simultaneous death signals: stale lease *and* silent heartbeat). The follower subscribes via
an async NATS Dispatcher; on silence beyond 500 ms it runs `watchAndPromote()`, which uses the
pod-GET fast path above.

The **Lease is still the authoritative promotion gate** — heartbeat silence alone never authorizes
theft from a live pod. If the holder's pod still exists, the follower waits for Lease expiry.
Split-brain remains impossible.

```
Follower, heartbeat thread (100ms): heartbeat stale (>500ms)? → watchAndPromote()
  → holder pod 404               → steal now            [real death, ~2–3s total]
  → holder pod deletionTimestamp → 1s guard, then steal
  → holder pod alive             → only steal after Lease expiry [wedge, ≤15s]
Follower, lease thread (every 2s): heartbeat not fresh AND lease expired? → watchAndPromote()
```

**Tradeoff:** Heartbeat adds a plain NATS publish on every 100 ms tick from the primary. At
~5 bytes per message, this is negligible. The Lease remains the safety net if NATS has a transient
issue.

---

### 6. Synchronous follower ACK — true LMAX lock-step

**The gap from LMAX:** The original implementation advanced the primary's input barrier when the
NATS *broker* acknowledged the JetStream publish, not when the *follower BLP* had processed the
event. The follower could lag by hundreds of milliseconds.

**The fix:** The follower sends a plain NATS ACK to `traderx.blp.replication.ack` after injecting
each event into its local ring. The primary's `NatsJournalReplicator` subscribes to ACKs and
tracks `followerAckedSeq`.

**Batch-boundary gate (matching how LMAX actually works):** The primary publishes every event to
NATS JetStream immediately as it arrives — no spin within a batch. It only blocks at the end of
each Disruptor batch (`endOfBatch=true`), spinning until `followerAckedSeq >= batchEndSeq`. This
means one network round-trip per batch rather than per event, so the throughput ceiling is:

```
ceiling = batch_size / round_trip_latency
```

Under load the Disruptor naturally forms large batches (the gateway produces faster than the BLP
consumes), so this scales well. At LMAX's 6M events/sec they achieved this with Aeron (~1 µs LAN
latency) and large batches. We use NATS (~1–5 ms GKE latency), which gives a ceiling of roughly
100K–1M events/sec at typical batch sizes — more than sufficient for TraderX's scale.

**Solo mode fallback:** If no ACK arrives within 500 ms, the spin exits and the primary continues
without waiting. A single-replica deployment never stalls.

---

### 7. Hot-swappable `DelegatingReplicator` — replication after promotion

**The original limitation:** Once the Disruptor ring is started, its event handler chain cannot be
changed. A promoted follower was stuck using `ReplicatorStub` (loopback) for the lifetime of the
process, meaning it couldn't replicate events to a new follower pod.

**The fix:** `DelegatingReplicator` wraps the actual handler behind a `volatile` reference.
It is always wired into the Disruptor ring at startup. On role change:
- FOLLOWER → PRIMARY: `swapDelegate(new NatsJournalReplicator(...))` — the next `onEvent` call
  publishes to JetStream.
- PRIMARY → FOLLOWER: `swapDelegate(new ReplicatorStub())` — the next `onEvent` call is a loopback.

The swap is one `volatile` write; the Disruptor event-processor thread sees it on the very next
event. A promoted pod immediately replicates to a new follower without restarting.

---

### 8. Two role-dispatched schedulers — heartbeat isolated from blocking I/O

**History:** the earliest implementation ran two schedulers keyed to the role at creation time; on
role transitions both loops accumulated and a demoted pod kept renewing the Lease (split-brain).
That was fixed with a single 100 ms `tick()`. The single-tick design then caused the opposite
failure: `renewOrDemote()`'s blocking GET+PUT ran inline on the same thread as the heartbeat
publish, so one slow renewal under CPU saturation silenced the heartbeat *and* let the lease
expire simultaneously — a false-demote of a healthy primary (reproduced 2026-07-14, see §10).

**Current design:** two schedulers again, but split by *blocking behavior*, not by role — and each
tick dispatches on the **current** role at run time (the original split-brain bug cannot recur):
- `blp-heartbeat` (100 ms): NATS publish/watch only. Never performs HTTP.
- `blp-lease` (2 s): all k8s API I/O (renew as primary, poll as follower).

---

### 9. StatefulSet with per-pod PVCs and required anti-affinity

**StatefulSet** (not Deployment): each pod gets a stable hostname (`order-matcher-0`,
`order-matcher-1`) and its own `ReadWriteOnce` PVC (`lmax-runtime-data-order-matcher-0`, etc.).
This is essential because:
- The BLP journal and snapshot are pod-local; they must survive pod restarts on the same node.
- Two pods must not share a journal — each BLP replays its own sequence.

**Required anti-affinity:** `requiredDuringSchedulingIgnoredDuringExecution` on the pod's node.
If both pods were on the same node and that node failed, both would be unavailable simultaneously.
`required` (not `preferred`) guarantees they land on different nodes.

**Tradeoff:** With `required` anti-affinity and a 3-node cluster, the scheduler must always
find two distinct nodes. If one node is drained and the cluster temporarily has only two nodes,
the second pod will fail to schedule until a node is available.

---

## Failure Modes & Gaps

| Failure | Behavior | Notes |
|---|---|---|
| Primary pod crashes | Heartbeat silence (500 ms) → pod GET returns 404 → immediate steal. **~2–3 s** total. Brief order-request failures during gap. | Expected; clients should retry (order API returns 503 from the admission gate on a non-primary). |
| Primary node fails | Same as pod crash once the pod object is deleted; until then the wedge path (≤15 s) applies. StatefulSet reschedules onto another node after promotion. | Node replacement adds ~1–3 min k8s scheduling overhead on top of the failover. |
| Primary alive but wedged (STW pause, hang) | Lease expiry path: primary self-fences at 10 s (renew deadline — the admission gate rejects on renewal age even before the election thread wakes); follower steals at 15 s. | The deliberate cost of false-demote immunity. 5 s no-overlap margin between self-fence and theft. |
| Slow/failed lease renewals under load | Retried until the 10 s renew deadline; **no demote on a single failure**. A timed-out PUT that actually landed server-side is recovered via 409→GET resync (still-self-holder → refresh + retry). | This was the 2026-07-14 false-demote bug (see §10) — a healthy saturated primary demoted on one ambiguous failure. |
| NATS unreachable at startup | Pod **fails closed**: FOLLOWER/REFUSING, background backoff-retry loop (5 s → 60 s cap) re-establishes replication + election without a restart. Both pods degraded = unavailable, never split-brained. | Previously failed **open** into an unelected, unreplicated PRIMARY — if both pods hit it, that was split-brain with zero load. |
| JetStream/replicator init fails (startup or post-promotion) | Fail closed: never serve as a loopback PRIMARY. Startup: degraded + recovery loop. Post-promotion: demote so the lease lapses and the peer takes over (re-election naturally retries `ensureStream`). | A primary that cannot replicate is a data-loss primary. |
| Both pods fail simultaneously | No primary; cluster is unavailable until at least one pod restarts and replays its journal. | Single-zone GKE; multi-zone would reduce this risk. |
| Split-brain (both pods think they are primary) | Prevented by three layers: (i) atomic `resourceVersion` PUT — one current holder; (ii) renewDeadline (10 s) < leaseDuration (15 s) — the old primary self-fences 5 s before a follower is entitled to steal; (iii) the synchronous admission gate enforces (ii) on the serving path itself, surviving STW wakeups. | The Lease alone is mutual exclusion, not fencing — the gate is what stops a demoted-but-unaware primary from admitting new work. |
| No follower ACK (follower down) | `NatsJournalReplicator.replicatedSeq()` falls back to `publishedSeq` after 500 ms. Primary continues in solo mode without stalling. | Follower catch-up on reconnect is bounded by JetStream message age (1 day max). |
| Follower DB writes during failover gap | Prevented by `replicationRole.isFollower()` gate in `ProjectorHandler`. | Follower never writes to DB. |

**Known residual (accepted):** events already claimed on the input ring when the gate flips are
still processed by the single matcher thread (a milliseconds-scale drain). Clients never receive
an ACK for these (gateway futures time out), but the demoted node's local projection can briefly
diverge until it re-syncs as follower. Full elimination would need end-to-end fencing epochs
validated by downstream writers — disproportionate for this system's scope.

---

## Configuration Reference

| Env / Spring key | Value in GKE | Purpose |
|---|---|---|
| `BLP_REPLICATION_ENABLED` | `true` | Activates `LeaderElection` + NATS replication in `LmaxEngine`. |
| `BLP_POD_NAME` | `$(metadata.name)` via fieldRef | Injected pod name used as Lease holder identity. |
| `SNAPSHOT_INTERVAL_MS` | `300000` (5 min) | Bounds follower catch-up window after restart (with `jetsStreamSeq`). |
| `RECOVERY_SOURCE` | `journal` | Both pods recover from their per-pod journal, not MariaDB. |
| `BLP_REPLICATION_ENABLED=false` | default | Disables replication for single-pod or local dev setups. |

Leader-election timing contract — **env-overridable** via the StatefulSet (no rebuild needed),
defaults in `LeaderElection.java`. Invariant: `RENEW_DEADLINE < LEASE_DURATION` (the primary
self-fences before a follower may steal).

| Env var | Default | Reason |
|---|---|---|
| `LEASE_DURATION_SECONDS` | `15` | Follower may steal only after 15 s staleness. Long lease = false-demote immunity; real-kill failover stays ~2–3 s via the pod-GET fast path, so the length costs nothing on the common case. |
| `RENEW_DEADLINE_SECONDS` | `10` | Primary self-fences (demotes + admission gate closes) if holdership unconfirmed this long. 15−10 = 5 s no-overlap margin. |
| `RENEW_INTERVAL_SECONDS` | `2` | ~5 renewal attempts inside the deadline (was ~2 under the old 5 s lease). |
| `LEASE_HTTP_TIMEOUT_SECONDS` | `2` | A hung API call fails fast and is retried within the deadline instead of eating half of it (was 5 s — a single call could exceed the entire old lease). |
| `HEARTBEAT_TIMEOUT_MS` | `500` | 5× the 100 ms publish interval; tolerates one lost message. |

Election health is observable at `/metrics`: `blp_lease_renew_age_seconds` (must stay ≪ deadline),
`blp_lease_renew_latency_seconds`, and `blp_demote_total{cause="foreign_holder"|"deadline"}`.

Parameters hardcoded in `NatsJournalReplicator.java`:

| Parameter | Value | Reason |
|---|---|---|
| `ACK_TIMEOUT_NS` | `500 ms` | If no follower ACK in 500 ms, fall back to solo (publish-ACK) mode. |

---

## §10 — The 2026-07-14 lease-starvation false-demote (incident → fix)

**Symptom:** under a saturating in-cluster benchmark (`--batch 1000 --conc 48`), the healthy
PRIMARY repeatedly false-demoted mid-run (`Failed to renew Lease — demoting`), producing a
leaderless gap and 100k+ failed orders per run, then leadership flapped back.

**Measured root cause:** GC was ruled out with `-Xlog:gc*,safepoint` under load — max
stop-the-world pause was **157 ms** against a 5 s renewal window. The stall was CPU-runqueue
starvation plus **blocking** GET+PUT renewals (each with a 5 s HTTP timeout) sharing one thread
with the heartbeat publish, governed by a hair-trigger rule that demoted on the first failed
renewal — including *ambiguous* failures where the timed-out PUT had actually landed and the
primary demoted while still holding a freshly-renewed lease (nobody could serve until expiry;
no theft even needed).

**Fix (all four ship together):**
1. Renewal state machine: demote only on proof (foreign holder / 10 s deadline), single-PUT with
   cached `resourceVersion`, 2 s HTTP timeouts, heartbeat on its own thread.
2. Pod-GET fast path: long lease without slow real-death failover.
3. Synchronous admission gate at the ring claim: the single-writer boundary, STW-wakeup-safe.
4. Fail-closed replication: NATS-down / JetStream-fail can no longer self-appoint an unreplicated
   PRIMARY; degraded pods refuse traffic and recover via a background backoff loop.

Design record: three independent model proposals were cross-reviewed and converged; see
`SPEC-blp-ha-lease-starvation-fix-FINAL.md` (untracked working note) for the full spec and
validation plan.

---

## What the LMAX Article Describes vs. What We Built

The original LMAX architecture (Martin Fowler, *The LMAX Architecture*) describes a warm-standby
achieved by replicating the input disruptor stream over a physical journal writer to a second
machine, with a hardware load balancer in front. See `LMAX-SEQUENCER-ARCHITECTURE.md §10` for
the full comparison table.

Key differences in our implementation:

- **Transport:** NATS JetStream instead of a dedicated journal-writer channel
- **Leader election:** Kubernetes Lease API instead of a hardware failover device
- **Traffic routing:** k8s Service label selector instead of a hardware load balancer
- **Scope:** Research demo with ~5 securities; LMAX was 6M orders/s in production

---

## Files Changed for This Feature

```
specs/YU02-lmax-kubernetes/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/lmax/
  LeaderElection.java          ← demote-on-proof renewal state machine, cached-RV single PUT, pod-GET
                                  fast path, split heartbeat/lease threads, env-tunable 15s/10s/2s/2s
  NatsJournalReplicator.java   ← follower ACK listener, synchronous replicatedSeq(), Disruptor seq in msg
  ReplicationFollower.java     ← ACK publish to traderx.blp.replication.ack after each inject()
  DelegatingReplicator.java    ← NEW: hot-swappable Disruptor wrapper (volatile delegate)
  LmaxEngine.java              ← DelegatingReplicator always in ring; ACK listener lifecycle; natsConn→LeaderElection
  ProjectorHandler.java        ← gates DB/NATS output on isFollower()
  ReplicationRole.java         ← thread-safe PRIMARY/FOLLOWER/UNKNOWN enum bean

specs/YU02-lmax-kubernetes/generation/runtime-overrides/kubernetes-runtime/manifests/base/
  nats-configmap.yaml          ← NEW: enables JetStream in NATS broker

cluster-addons/
  order-matcher-statefulset.yaml      ← modified: replicas=2, BLP_REPLICATION_ENABLED, imagePullPolicy:Always
  order-matcher-primary-service.yaml  ← NEW: ClusterIP, selector blp-role=primary
  order-matcher-rbac.yaml             ← NEW: ServiceAccount + Role + RoleBinding for Lease API

traderX/scripts/test-state-YU02-lmax-kubernetes.sh  ← modified: checks trade-service routes to order-matcher-primary
```
