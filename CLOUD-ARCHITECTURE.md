# TraderX on GKE — Cloud Architecture

Live at **https://yaakovseif.dev** · Branch: `lmax-kubernetes-blp-ha` · Cluster: `traderx-lmax` (zone `us-east1-b`, project `traderx-501015`)

This document describes the deployed GKE architecture: node pools, what runs where, the
order-matcher (BLP) HA/single-BLP modes, throughput characteristics, and the operational commands.

## Spec-kit lineage note

The team-authored state lineage now reaches `YU15-eod-risk-extract`, parented on
`YU14-listed-equity-options` (itself parented on `YU13-limit-order-book` → `YU12-aeron-cluster` →
`YU11-aeron-replication` →
`YU10-fix-ingress`). YU11 keeps the File-backed NATS replication path as the default and adds a
flag-selected Aeron+SBE path with per-pod Archive sidecars; YU12 replaces the hand-built HA
machinery with Aeron Cluster Raft consensus (three members, odd quorum) hosting the
deterministic matching/risk core; YU13 replaces the price-triggered auto-fill matcher with a
genuine crossing limit-order book (price-time priority, limit/market/cancel, partial fills)
inside that same ClusteredService, serializing the whole resting book into the cluster
snapshot; YU14 adds listed equity options as ordinary securities (OCC-symbol identity) with a
contract-multiplier-aware risk gate and a format-3 snapshot; YU15 adds the end-of-day risk
extract, where a sequenced marker names a consensus sequence, every member renders the identical
position cut at it, and the leader's cut is joined with the published closing prices into one
immutable, byte-reproducible portfolio fixture announced on NATS. This document describes the
inherited GKE baseline and user-run capacity commands; the exact runtime contracts live in
`specs/YU12-aeron-cluster/`, `specs/YU13-limit-order-book/`,
`specs/YU14-listed-equity-options/`, and `specs/YU15-eod-risk-extract/`.

---

## 1. Cluster & node pools

The cluster has **two node pools**, deliberately split by workload:

| Node pool | Machine | Count | Boot disk | Purpose |
|-----------|---------|-------|-----------|---------|
| `default-pool` | e2-standard-2 (2 vCPU / 8 GB) | 3 | pd-balanced 100 GB | Everything except the BLP: DB, NATS, the 3 Spring services, web, observability, edge-proxy, ingress. |
| `blp-pool` | **c2-standard-4** (4 vCPU / 16 GB, compute-optimized) | 1–2 | pd-standard 50 GB | **Dedicated to `order-matcher` only** (the LMAX BLP). Tainted so nothing else lands here. |

**Why a dedicated pool for the BLP:** the BLP is a single-threaded, CPU-bound hot loop plus a
multi-threaded REST gateway. On the shared 2-vCPU BestEffort setup it was CPU-starved (~6k booked/s
and crashing under load). Giving it dedicated high-clock c2 cores with no noisy neighbours took it
to ~42k (single-BLP) — a 7× gain. The limiter is CPU cores for the gateway, **not** the message bus.

`blp-pool` node config:
- **Taint** `workload=blp:NoSchedule` — keeps all other workloads off.
- **Label** `workload=blp` — `order-matcher` targets it via `nodeSelector` + a matching toleration.
- HA needs **≥2 nodes** (required pod anti-affinity spreads the two BLP replicas across nodes).

---

## 2. order-matcher (the BLP) — two modes

`order-matcher` is a StatefulSet (`cluster-addons/order-matcher-statefulset.yaml`). It runs in one
of two modes, toggled by replica count + `BLP_REPLICATION_ENABLED`:

### HA mode (current default — 2 replicas)
- 2 replicas, one per `blp-pool` node (required anti-affinity).
- **Leader election** via a Kubernetes Lease (`order-matcher-leader`): winner = PRIMARY, other = FOLLOWER.
- PRIMARY replicates every input event to **NATS JetStream** (`TRADERX_BLP_REPLICATION`); FOLLOWER
  replays that stream to stay in lock-step and ACKs each batch.
- FOLLOWER reports **not-ready** by design, so the `order-matcher` Service routes only to the PRIMARY
  (no split-brain writes). `order-matcher-primary` Service also selects the `blp-role=primary` pod.
- Throughput: **~22k booked/s** (see §4). Failover: kill PRIMARY → FOLLOWER promotes in ~25s.

### Single-BLP mode (max throughput — 1 replica)
- 1 replica, `BLP_REPLICATION_ENABLED=false` → `ReplicatorStub` (loopback, no replication gate).
- No failover, but **~42k booked/s** — ~2× HA because it skips the replication round-trip.
- Use when throughput matters more than availability.

Both modes share the same tuning (persisted in the StatefulSet manifest):
- **Resources:** `cpu: 2` request, **no CPU limit** (a CPU limit CFS-throttles the hot loop),
  `memory: 2Gi` request / `4Gi` limit, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65.0`.
- **Tolerant health probes:** liveness/readiness `timeoutSeconds: 5`, `failureThreshold: 6`. A tight
  1s liveness probe was SIGTERM-killing a busy-but-alive CPU-bound pod under load (exit 143).
- **Recovery:** `RECOVERY_SOURCE=journal`; per-pod PVC journal at `/var/lib/traderx-lmax/journal`;
  snapshot every 5 min.

---

## 3. What runs where (data flow)

```
          Internet ──► ingress-nginx ──► edge-proxy (nginx) ──► services (path-routed)
                                                │
   web-front-end-angular ◄── NATS/socket.io ◄───┤
                                                │
   order-matcher (blp-pool)  ── REST /orders ───┘         default-pool:
     • LMAX BLP: match/fill in-memory (single writer)       • database (MariaDB 11.4, port 3306)
     • journals input to per-pod PVC                        • nats-broker (NATS + JetStream)
     • output ring ──► ProjectorHandler ──► MariaDB         • trade-processor, account-service,
                    └─► NATS bridges ──► frontend             position-service  (JPA → MariaDB)
                                                             • price-publisher, reference-data,
   [HA] PRIMARY ──► JetStream replication ──► FOLLOWER         people-service, trade-service
                                                             • grafana / prometheus / loki / tempo / otel
```

- **MariaDB is a read-model projection**, not the source of truth — the BLP's journal is. The
  projector writes trades/orders/positions to MariaDB asynchronously (non-gating) for the frontend
  and the query services.
- `account-service`, `position-service`, `trade-processor` connect to MariaDB on **port 3306**
  (`DATABASE_PG_PORT=3306` — a leftover `5432` from the Postgres era was silently breaking them).

---

## 4. Throughput (booked trades/sec, batch=1000)

| Config | booked/s | Notes |
|--------|----------|-------|
| In-process BLP only (no I/O, JUnit) | ~2,400,000 | Pure matching ceiling — proves the BLP thread is never the bottleneck |
| Single-BLP, BestEffort, shared node | ~6,000 | Original; crashed under high concurrency |
| Single-BLP, resources+probes, shared node | ~13,000 | Survives load; core-bound on 2 vCPU |
| **Single-BLP, dedicated c2 node** | **~42,000** | conc≈48–96; matches local docker-compose baseline |
| HA (sync per-event publish, old) | ~1,714 | One JetStream round-trip **per event** |
| **HA (async-pipelined publish, c2)** | **~22,000** | conc≤8; ~13× the old HA via batch-drained async publish |

The two big levers were **(1) dedicated c2 cores** for the gateway-bound single-BLP path and
**(2) async-pipelined replication** for HA. NATS→Aeron was considered and rejected: it only touches
the replication path and needs dedicated spin cores it doesn't have.

---

## 5. Operational commands

### Deploy / images
```bash
# Build + push order-matcher (repeat pattern for other services), unique dated tag:
cd generated/code/target-generated/order-matcher
./gradlew bootJar
docker buildx build --platform linux/amd64 \
  -t us-east1-docker.pkg.dev/traderx-501015/traderx/order-matcher:state009-<desc>-$(date +%Y%m%d) --push .

# Point the StatefulSet at a new image:
kubectl set image statefulset/order-matcher \
  order-matcher=us-east1-docker.pkg.dev/traderx-501015/traderx/order-matcher:<tag> -n traderx
```

### Switch HA ↔ single-BLP
```bash
# → single-BLP (max throughput, no failover)
kubectl set env  statefulset/order-matcher BLP_REPLICATION_ENABLED=false -n traderx
kubectl scale    statefulset/order-matcher --replicas=1 -n traderx

# → HA (needs blp-pool at >=2 nodes first — see below)
kubectl set env  statefulset/order-matcher BLP_REPLICATION_ENABLED=true -n traderx
kubectl scale    statefulset/order-matcher --replicas=2 -n traderx
```

### blp-pool node pool
```bash
# Create (one-time):
gcloud container node-pools create blp-pool --cluster traderx-lmax --zone us-east1-b \
  --machine-type c2-standard-4 --num-nodes 1 --disk-type pd-standard --disk-size 50 \
  --node-labels workload=blp --node-taints workload=blp:NoSchedule

# Resize (1 for single-BLP, 2 for HA):
gcloud container clusters resize traderx-lmax --node-pool blp-pool --num-nodes 2 --zone us-east1-b

# COST SAVER — scale to 0 when idle (research cluster). Note: this takes order-matcher offline,
# since it only schedules on blp-pool.
gcloud container clusters resize traderx-lmax --node-pool blp-pool --num-nodes 0 --zone us-east1-b
```
Cost: c2-standard-4 ≈ **$0.21/hr (~$153/mo)** per node at 24/7; far less if scaled to 0 when idle.

### Benchmark (in-cluster, no port-forward)
```bash
bash scripts/bench/run-gke-bench.sh <label> <runs> <secs> <batch> <conc>
# e.g. single-BLP sweet spot:  run-gke-bench.sh single 3 30 1000 48
# HA (keep conc<=8 to avoid lease starvation): run-gke-bench.sh ha 3 30 1000 8
```

### Failover test (HA)
```bash
kubectl delete pod order-matcher-0 -n traderx          # kill current PRIMARY
kubectl get lease order-matcher-leader -n traderx -w    # watch holder flip to the follower (~25s)
```

### Health / roles
```bash
kubectl get pods -n traderx -l app=order-matcher -L blp-role -o wide
kubectl get lease order-matcher-leader -n traderx -o jsonpath='{.spec.holderIdentity}'
curl -s https://yaakovseif.dev/order-matcher/health | jq .lmax   # seq watermarks
```

---

## 6. CI/CD pipeline (order-matcher only, set up 2026-07-03)

**Cloud Build → Cloud Deploy**, GitOps-style, so both of us can push without stepping on each
other's manual `kubectl`/`docker` commands on the shared cluster. Git is the source of truth;
nothing reaches the live cluster without an explicit manual approval.

### Flow

```
push to lmax-kubernetes-blp-ha
        │  (GitHub webhook, 1st-gen Cloud Build repo connection)
        ▼
Cloud Build trigger "order-matcher-cicd"  (cloudbuild.yaml)
        │  1. regenerate generated/ from committed specs/ (never build from a stale local copy —
        │     generated/ is gitignored)
        │  2. ./gradlew bootJar
        │  3. docker build + push → order-matcher:ci-$SHORT_SHA  (unique tag, never reused)
        │  4. gcloud deploy releases create
        ▼
Cloud Deploy release  →  PENDING_APPROVAL   (production target has requireApproval: true)
        │
        │  someone with roles/clouddeploy.approver approves
        ▼
skaffold renders cluster-addons/order-matcher-statefulset.yaml
(substitutes the built image into the manifest's image: field)
        ▼
applied to traderx-lmax / order-matcher StatefulSet
```

### Config files (repo root)

| File | Purpose |
|---|---|
| `cloudbuild.yaml` | Build steps: generate, build jar, build+push image, create release |
| `clouddeploy.yaml` | `DeliveryPipeline` (order-matcher-pipeline) + `Target` (production, `requireApproval: true`, points at the traderx-lmax cluster) |
| `skaffold.yaml` | Declares the order-matcher build artifact + `cluster-addons/order-matcher-statefulset.yaml` as the deploy manifest, so Cloud Deploy/skaffold know which `image:` field to substitute |

### IAM / infra (not in git)

- **`traderx-cicd@traderx-501015.iam.gserviceaccount.com`** — dedicated service account for the
  whole pipeline. Roles: `artifactregistry.writer`, `clouddeploy.releaser`, `clouddeploy.jobRunner`,
  `container.developer`, `logging.logWriter`, `cloudbuild.builds.builder`, plus a
  **self-referential** `iam.serviceAccountUser` binding (Cloud Deploy's "ActAs" check applies even
  when the triggering and executing SA are the same principal — without this the release-create
  step fails with `PERMISSION_DENIED: ActAs permissions required`).
- Cloud Build's own service agent (`service-397259609626@gcp-sa-cloudbuild.iam.gserviceaccount.com`)
  has `iam.serviceAccountTokenCreator` on `traderx-cicd`, so builds can actually run as it.
- **`gs://traderx-501015-clouddeploy-artifacts`** — Cloud Deploy's rendered-manifest storage.
  Deliberately *not* Cloud Deploy's default bucket (`<region>.deploy-artifacts.<project>.appspot.com`)
  — that name uses App Engine's domain-verified naming convention, which only Cloud Deploy's own
  internal identity can provision; a normal `gcloud storage buckets create` gets a 403.
- **Approvers** (`roles/clouddeploy.approver`): `yaakov.traderx@gmail.com`, `tanidiament@gmail.com`.

### Operating it

```bash
# Approve a pending release (or use the console: Cloud Deploy → order-matcher-pipeline)
gcloud deploy rollouts list --delivery-pipeline=order-matcher-pipeline --region=us-east1
gcloud deploy rollouts approve <rollout-name> \
  --release=<release-name> --delivery-pipeline=order-matcher-pipeline --region=us-east1

# Reject instead
gcloud deploy rollouts reject <rollout-name> \
  --release=<release-name> --delivery-pipeline=order-matcher-pipeline --region=us-east1

# Manually trigger a build without a git push (e.g. to test a local change)
gcloud builds submit --config=cloudbuild.yaml --project=traderx-501015 .

# Fetch build logs (options.logging: CLOUD_LOGGING_ONLY means `gcloud builds submit`'s own log
# tail doesn't show output — pull from Cloud Logging instead)
gcloud logging read 'resource.type="build" AND resource.labels.build_id="<id>"' \
  --project=traderx-501015 --format="value(textPayload)" --order=asc
```

### Scope and validation

Order-matcher only for now — the highest-value, most fragile service. Other services
(`account-service`, `trade-processor`, etc.) still deploy via the manual path in §5. Extend the
same skaffold/Cloud Deploy pattern to them if this proves out.

Validated by running the full pipeline manually twice via `gcloud builds submit` before wiring up
the trigger: confirmed the approval gate genuinely holds (release sat at `PENDING_APPROVAL`, live
StatefulSet pods unchanged — same image, same creation timestamps) until explicitly approved.

---

## 7. Known issues / next levers

- **HA lease starvation under load** *(not yet fixed)*: at conc≥24 the CPU-saturating load starves
  the leader-election lease renewal → the PRIMARY false-demotes (409 lease conflict) → transient
  write failures until it re-settles. Fix before HA is load-safe: longer `leaseDurationSeconds`,
  and/or run the election thread at higher priority / off the saturated cores.
- **`blp-role` label not always set** on the PRIMARY after redeploy → `order-matcher-primary` Service
  can be empty. The regular `order-matcher` Service still routes correctly (FOLLOWER is not-ready),
  so writes are unaffected, but worth fixing for the primary-only Service.
- **Beyond 42k single-BLP:** the path is gateway-CPU-bound — a `c2-standard-8` (more cores) or
  reducing per-order gateway cost (async REST, fewer allocations), **not** CPU pinning.
- **Async-replication durability** is benchmark- and single-failover-validated only; a broader
  crash/partition durability test is recommended before production trust.
- See `HANDOFF-ha-throughput-improvements.md` for further HA replication levers (larger batchRecords,
  deeper pipelining).
