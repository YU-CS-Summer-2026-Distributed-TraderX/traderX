# TraderX — Project Context for Claude

## What this project is

FINOS TraderX is a demo equities trading platform. This branch (`lmax-kubernetes`) is a research
fork that replaces the standard order-management service with an **LMAX-architecture Business Logic
Processor (BLP)** — single-threaded, in-memory, event-sourced via a Disruptor ring buffer — and
deploys the whole system to **Google Kubernetes Engine (GKE)**.

The "order-matcher" service IS the BLP. The BLP is the hot-path core: all order matching happens in
one single-threaded in-memory engine, journaled to disk, with periodic snapshots. The MariaDB
database is an async read-model only — not the source of truth.

Key reading: `LMAX-SEQUENCER-ARCHITECTURE.md`, `LMAX-BLP.md`, `LMAX-INPUT-DISRUPTOR.md`,
`LMAX-OUTPUT-DISRUPTOR.md`.

---

## Branch map

| Branch | Based on | Purpose |
|--------|----------|---------|
| `lmax-kubernetes` | gridgain-research + state 014 | **Active work.** GKE cloud deployment with HA. |
| `gridgain-research` | lmax-sequencer-no-gc (state 009b) | Teammate's perf research branch. |
| `lmax-sequencer-no-gc` | state 009 | State 009b: LMAX Disruptor BLP implementation. |

Worktrees:
- `lmax-kubernetes` → `/Users/yaakov/Desktop/Summer 26/lmax/traderX`
- `gridgain-research` → `/Users/yaakov/Desktop/Summer 26/lmax/traderX-gridgain-research`

Common ancestor of lmax-kubernetes and gridgain-research: `da2f9e2`.

---

## Team split

- **Yaakov** — GKE cluster, manifests, ingress, TLS, StatefulSet, deploy pipeline, and
  BLP multi-replica failover infrastructure (planned — not yet implemented).
- **Teammate** — BLP performance work: snapshot improvements, journal batch coalescing,
  bounded terminal-order retention. Works on `gridgain-research` branch.

---

## GKE deployment — current state

**Live at:** `https://yaakovseif.dev`

| Item | Value |
|------|-------|
| Cluster | GKE, single-zone; `default-pool` 3× e2-standard-2 + `blp-pool` 1× c2-standard-4 |
| Static IP | bound to `yaakovseif.dev` via DNS A record |
| TLS | cert-manager + Let's Encrypt (`cluster-addons/letsencrypt-issuer.yaml`) |
| Ingress | ingress-nginx → edge-proxy on port 8080 (`cluster-addons/traderx-ingress.yaml`) |
| BLP | StatefulSet (`cluster-addons/order-matcher-statefulset.yaml`); **currently single-BLP** (1 replica, `BLP_REPLICATION_ENABLED=false`) pinned to `blp-pool` for throughput. Scale to 2 + replication=true to re-enable HA. |
| BLP node pool | `blp-pool` = 1× c2-standard-4, `pd-standard`/50Gi boot disk, label `workload=blp`, taint `workload=blp:NoSchedule`. Dedicated high-clock cores for the single-threaded BLP: ~42k booked/s vs ~13k on the shared default-pool. |
| Recovery | `RECOVERY_SOURCE=journal`, `SNAPSHOT_INTERVAL_MS=300000` (5 min) |
| DB | MariaDB 11.4 (port 3306; `--lower-case-table-names=1`) |
| Grafana | `grafana.yaakovseif.dev` ingress added; DNS A record still needed |

> **Perf note (2026-07-02):** order-matcher throughput is CPU-bound on the single-threaded BLP,
> not message-bus-bound. The biggest wins were: (1) getting off BestEffort QoS with a CPU floor +
> memory headroom + tolerant health probes (a tight 1s liveness probe was SIGTERM-killing a
> busy-but-alive pod), and (2) a dedicated c2 node pool. NATS→Aeron was considered and rejected —
> it only touches the HA replication path and needs spare cores it doesn't have. See
> `HANDOFF-ha-throughput-improvements.md` for the HA-replication levers (batchRecords, pipelined ACK).

### Deploy

```bash
# Full deploy (rebuild manifests + kubectl apply -k)
bash scripts/deploy-state-lmax-kubernetes-gke.sh us-east1-docker.pkg.dev/traderx-501015/traderx

# Apply cluster-addons (ingress, TLS issuer; StatefulSet + headless are now in kustomization)
kubectl apply -f cluster-addons/

# Build images and push to Artifact Registry
bash scripts/push-state-lmax-kubernetes-gke-images.sh us-east1-docker.pkg.dev/traderx-501015/traderx
```

### Manifest pipeline

`scripts/prepare-state-lmax-kubernetes-gke-manifests.sh` takes the base manifests in
`generated/.../manifests/base/`, rewrites them for GKE, and writes to
`generated/.../manifests/gke-rendered/`. Changes it makes:
- Copies `cluster-addons/order-matcher-statefulset.yaml` + headless service into the output;
  removes `order-matcher-deployment.yaml` + `order-matcher-lmax-data-pvc.yaml` from kustomization
- Rewrites image tags to the Artifact Registry prefix (including the StatefulSet)
- Converts edge-proxy service from NodePort → ClusterIP (ingress-nginx handles external access)
- Injects pod anti-affinity on all Deployments to spread across the 3 nodes

**Local kind cluster note:** Images are loaded under `docker.io/traderx/<name>:state009`. After
running `prepare`, re-tag them with the GAR prefix via `ctr -n k8s.io images tag` so
`imagePullPolicy: IfNotPresent` works without pulling from GAR:
```bash
docker exec traderx-state-014-control-plane ctr -n k8s.io images tag \
  docker.io/traderx/order-matcher:state009 \
  us-east1-docker.pkg.dev/traderx-501015/traderx/order-matcher:state009
```
(Repeat for each service image.)

---

## Key directories

```
specs/lmax-kubernetes/generation/runtime-overrides/
  order-matcher/          ← BLP Java source overrides (Journaler, MatchingEngine, LmaxEngine, …)
  trade-processor/        ← DB driver + app config overrides
  account-service/        ← DB driver + app config overrides
  position-service/       ← DB driver + app config overrides
  kubernetes-runtime/manifests/base/   ← K8s manifest overrides (order-matcher deployment, PVC)
  tilt-kubernetes-dev-loop/            ← Tilt dev-loop manifest overrides

cluster-addons/
  traderx-ingress.yaml               ← ingress-nginx Ingress resource (yaakovseif.dev → edge-proxy)
  letsencrypt-issuer.yaml            ← cert-manager ClusterIssuer + headless service definition
  order-matcher-statefulset.yaml     ← BLP StatefulSet (2 replicas, imagePullPolicy:Always, BLP_REPLICATION_ENABLED=true)
  order-matcher-headless-service.yaml ← headless service for StatefulSet peer DNS
  order-matcher-primary-service.yaml ← ClusterIP service selecting only the pod with blp-role=primary label
  order-matcher-rbac.yaml            ← ServiceAccount + Role + RoleBinding for Lease API access

scripts/
  deploy-state-lmax-kubernetes-gke.sh          ← prepare + kubectl apply -k
  prepare-state-lmax-kubernetes-gke-manifests.sh ← manifest rewriting
  push-state-lmax-kubernetes-gke-images.sh     ← docker build + push to Artifact Registry

generated/code/target-generated/kubernetes-runtime/manifests/
  base/        ← source manifests (copied from kubernetes-runtime/manifests/base/ by pipeline)
  gke-rendered/ ← output of prepare script (what kubectl apply -k reads)
```

---

## Pending work

All items from `GRIDGAIN-TO-LMAX-KUBERNETES-HANDOFF.md` are now complete:

- ✅ MariaDB 11.4 replacing PostgreSQL across all 4 services + database image + init SQL
- ✅ BLP code merge from gridgain commit `d70f703` (journal batch coalescing, bounded terminal retention)
- ✅ Docs cherry-pick from gridgain commit `111848c`
- ✅ Deployment/StatefulSet conflict resolved: StatefulSet + headless service folded into kustomization
- ✅ Grafana ingress added (`grafana.yaakovseif.dev`); DNS A record still needed
- ✅ Failover tested: `kubectl delete pod order-matcher-0` → replayed journal, restored state in < 2s
- ✅ BLP multi-replica HA: StatefulSet×2, k8s Lease leader election, NATS JetStream replication, `order-matcher-primary` Service

### Remaining

- **DNS A record** for `grafana.yaakovseif.dev` pointing to the same static IP as `yaakovseif.dev`
- **Node failover test**: `kubectl drain <node> --ignore-daemonsets --delete-emptydir-data`
  (single-zone GKE only has 1 zone so this tests node replacement, not zone failover)
- **Orphaned Deployment cleanup** (one-time): `kubectl delete deployment order-matcher -n traderx`
  (pre-StatefulSet artifact — does not affect failover)

---

## BLP recovery flow

On restart, `LmaxEngine` reads `RECOVERY_SOURCE`:
- `journal` → replays the binary journal file from the last snapshot boundary forward; signals
  readiness via Spring `ReadinessState.ACCEPTING_TRAFFIC` only after replay completes.
- `db` → warms up from Postgres (legacy, not used in this branch).

Journal path: `/var/lib/traderx-lmax/journal` (mounted from the StatefulSet's per-pod PVC).
Snapshot path: same volume. Snapshot interval: 5 minutes (`SNAPSHOT_INTERVAL_MS=300000`).

The StatefulSet uses `volumeClaimTemplates` so each pod gets its own `ReadWriteOnce` PVC —
`lmax-runtime-data-order-matcher-0`, etc. Pod anti-affinity is `required` (not preferred) so
replicas are guaranteed to land on different nodes.

## BLP replication & leader election (`lmax-kubernetes`)

With `BLP_REPLICATION_ENABLED=true` (set in the StatefulSet), pods compete for a Kubernetes Lease
(`order-matcher-leader`, `traderx` namespace). The winner becomes PRIMARY and:

1. Patches its own pod label `blp-role=primary` so the `order-matcher-primary` Service routes to it.
2. Publishes every input event to NATS JetStream stream `TRADERX_BLP_REPLICATION`.
3. Renews the Lease every 5 s (`leaseDurationSeconds=15`).

The FOLLOWER watches the Lease every 5 s. If the Lease is expired or missing, it promotes itself,
patches its label, and starts publishing. Failover time is ≤15 s (one lease duration) in the worst
case, typically ~5–10 s.

See `LMAX-BLP-FAILOVER.md` for a full explanation of design choices and tradeoffs.

---

## Generation pipeline

The `generated/` directory is an ephemeral build output. To rebuild it:

```bash
bash pipeline/generate-state.sh lmax-kubernetes
```

Do not manually edit files under `generated/` — they will be overwritten. Edit the source overrides
under `specs/lmax-kubernetes/generation/runtime-overrides/` or the prepare script instead.

---

## Deploy discipline

CI/CD does not exist yet, so deployed state drifts from committed source unless deploys are done
deliberately. Every production bug found on 2026-07-02 (broken MariaDB projector, Postgres driver +
`DATABASE_PG_PORT=5432` against MariaDB) had the same shape: the fix was already in git/`generated/`,
but GKE was running an older image built before the fix. Rules to prevent recurrence:

- **Rebuild and redeploy from `generated/` after any merge that touches a service.** What runs on
  GKE must match what's committed. Do not leave a merged code change undeployed.
- **Use unique, dated image tags per build** (e.g. `state009-fixed-20260702`), never reuse a fixed
  tag like `state009`. Reused tags make it impossible to tell which build is live without comparing
  Artifact Registry push timestamps against commit timestamps.
- To audit for drift: compare each deployed pod's image digest push time
  (`gcloud artifacts docker images list <img> --include-tags --format="value(version,createTime)"`)
  against the git log for that service's source.

## Repo hygiene

- **Never commit local handoff / scratch docs** (files like `HANDOFF-*.md`, `*-HANDOFF.md`, `STATE.md`,
  `LEARNING.md`, and similar working notes). They are local working artifacts, not project source.
  Leave them untracked; do not `git add` them even when staging related changes.

---

## Observability

Grafana, Prometheus, Loki, Tempo, and OpenTelemetry Collector are all deployed. Grafana has an
ingress route at `grafana.yaakovseif.dev` (TLS via cert-manager; DNS A record still needed).

Port-forward if DNS isn't set up yet:
```bash
kubectl port-forward svc/grafana 3000:3000 -n traderx
```
