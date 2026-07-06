# TraderX — BLP Failover in Kubernetes: Design, Choices & Tradeoffs

> **Branch:** `YU02-lmax-kubernetes`
> **Date:** 2026-07-01
> **Companion docs:** `LMAX-BLP.md`, `LMAX-SEQUENCER-ARCHITECTURE.md`, `CLAUDE.md`

---

## Overview

The BLP (Business Logic Processor) is a **single-threaded, in-memory, event-sourced** order matcher.
There is exactly one running instance at any moment — the **PRIMARY**. A second pod runs as a
**FOLLOWER** that stays warm by consuming the primary's event stream. When the primary fails, the
follower detects the failure and promotes itself, becoming the new primary within ≤15 seconds.

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
| `LeaderElection.java` | Runs a single-threaded 5 s tick loop; manages Lease create/renew/watch | `specs/YU02-lmax-kubernetes/.../lmax/LeaderElection.java` |
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
2. `LeaderElection.start()` schedules a single `tick()` every 5 s.
3. On the first `tick()`, both pods are in role `UNKNOWN`; both call `watchAndPromote()`.
4. `watchAndPromote()` checks whether the Lease exists and is fresh. The Lease doesn't exist yet.
5. **One pod wins the race** to POST a new Lease (k8s enforces atomicity — only one 201 response).
   The winning pod sets its role to `PRIMARY`.
6. PRIMARY: patches its own pod label `blp-role=primary` via the k8s Pod API.
7. `order-matcher-primary` Service now has exactly one Endpoint: the primary pod.
8. PRIMARY installs `NatsJournalReplicator` on its Disruptor input ring and starts publishing.
9. FOLLOWER subscribes to the JetStream stream and starts consuming events, keeping its BLP state in sync.

### Steady state

- PRIMARY renews the Lease every 5 s by PUT-ing a new `renewTime`.
- FOLLOWER calls `watchAndPromote()` every 5 s, reads the Lease, and confirms it is still fresh
  (age < `leaseDurationSeconds`). No action taken.
- The `NatsJournalReplicator` publishes every input event in the same 64-byte binary layout used
  by the on-disk journal, so the follower's BLP applies events in an identical order.

---

## Failover Sequence

When the primary pod crashes or is deleted:

1. The primary stops renewing the Lease.
2. At the next follower `tick()` (≤5 s), `watchAndPromote()` finds that
   `now - renewTime > leaseDurationSeconds` (15 s). The Lease is expired.
3. Follower atomically PUTs the Lease with its own pod name as `holderIdentity`.
4. Follower sets `role = PRIMARY`, patches its pod label `blp-role=primary`.
5. `order-matcher-primary` Service detects the label change and flips its Endpoint.
6. Follower installs `NatsJournalReplicator` and starts publishing new events.

**Total failover time (fast path via heartbeat):** heartbeat timeout (500 ms) + Lease PUT (~50 ms)
+ pod label patch (~50 ms) = **~600 ms typical**.

**Worst case (heartbeat blocked, Lease path):** leaseDurationSeconds (5 s) + tick interval (100 ms)
= **~5.1 s**.

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
5 s, giving a worst-case detection window of ~5 s plus the tick interval. The Lease API also
requires an HTTP round-trip to the k8s API server, adding latency.

**The solution:** The primary publishes a plain NATS message on `traderx.blp.heartbeat` every
100 ms. The follower subscribes via an async NATS Dispatcher; if no heartbeat arrives within
500 ms, it immediately attempts Lease theft without waiting for the next Lease poll.

The **Lease is still the authoritative promotion gate** — the heartbeat only triggers faster
detection. If the follower's Lease PUT fails (primary renewed faster — not actually dead), it backs
off. Split-brain remains impossible.

```
Follower tick (100ms):
  if heartbeat stale (> 500ms):  → watchAndPromote()  [fast path, ~600ms total]
  elif Lease poll due (every 2s): → isLeaseExpired()?  [slow path, ≤5s]
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

### 8. Single `tick()` dispatcher — no dual scheduling

**The problem:** Early implementations used two `scheduleAtFixedRate` calls — one for the primary
renew loop and one for the follower watch loop. On role transitions, both timers accumulated,
causing the pod to run both `renewOrDemote()` and `watchAndPromote()` simultaneously. This led to
split-brain: the demoted pod kept renewing the Lease.

**The fix:** A single `scheduleAtFixedRate(this::tick, 100ms, ...)` where `tick()` dispatches
based on the current role at the time it runs.

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
| Primary pod crashes | Heartbeat timeout fires in ~500 ms; follower promotes in ~600 ms total. Brief order-request failures during gap. | Expected; clients should retry. |
| Primary node fails | Same as pod crash — StatefulSet reschedules primary onto another node after promotion. | Node replacement adds ~1–3 min k8s scheduling overhead on top of the ~600 ms failover. |
| NATS broker restarts | Primary can't publish events or heartbeats; follower detects heartbeat stale in 500 ms, attempts promotion. Follower falls back to full journal replay on next restart (bounded by snapshot interval). | In-memory JetStream data is lost. File storage would survive broker restart. |
| Both pods fail simultaneously | No primary; cluster is unavailable until at least one pod restarts and replays its journal. | Single-zone GKE; multi-zone would reduce this risk. |
| Split-brain (both pods think they are primary) | Prevented by k8s Lease optimistic concurrency (`resourceVersion` PUT check). Heartbeat triggers detection but Lease is the gate. | Only one PUT with the winning `resourceVersion` succeeds. |
| Lease not renewed (silent renewal failure) | Prevented by fetching `resourceVersion` before every PUT; logs a warning on 4xx. | Root cause of original split-brain bug — fixed. |
| No follower ACK (follower down) | `NatsJournalReplicator.replicatedSeq()` falls back to `publishedSeq` after 500 ms. Primary continues in solo mode without stalling. | Follower catch-up on reconnect is bounded by JetStream message age (1 day max). |
| Follower DB writes during failover gap | Prevented by `replicationRole.isFollower()` gate in `ProjectorHandler`. | Follower never writes to DB. |

---

## Configuration Reference

| Env / Spring key | Value in GKE | Purpose |
|---|---|---|
| `BLP_REPLICATION_ENABLED` | `true` | Activates `LeaderElection` + NATS replication in `LmaxEngine`. |
| `BLP_POD_NAME` | `$(metadata.name)` via fieldRef | Injected pod name used as Lease holder identity. |
| `SNAPSHOT_INTERVAL_MS` | `300000` (5 min) | Bounds follower catch-up window after restart (with `jetsStreamSeq`). |
| `RECOVERY_SOURCE` | `journal` | Both pods recover from their per-pod journal, not MariaDB. |
| `BLP_REPLICATION_ENABLED=false` | default | Disables replication for single-pod or local dev setups. |

Parameters hardcoded in `LeaderElection.java`:

| Parameter | Value | Reason |
|---|---|---|
| `leaseDurationSeconds` | `5` | Reduces worst-case Lease-path detection to ≤5 s. |
| `RENEW_INTERVAL_NS` | `2 s` | Renew at 2.5× the lease duration — 2 missed renewals before expiry. |
| Tick interval | `100 ms` | Heartbeat publish + detection resolution. |
| `HEARTBEAT_TIMEOUT_NS` | `500 ms` | 5× the publish interval; tolerates one lost message. |

Parameters hardcoded in `NatsJournalReplicator.java`:

| Parameter | Value | Reason |
|---|---|---|
| `ACK_TIMEOUT_NS` | `500 ms` | If no follower ACK in 500 ms, fall back to solo (publish-ACK) mode. |

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
  LeaderElection.java          ← NATS heartbeat (100ms publish/watch), leaseDuration=5s, 100ms tick
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
