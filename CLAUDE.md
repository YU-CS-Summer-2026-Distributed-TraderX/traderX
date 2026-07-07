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
| `lmax-kubernetes-blp-ha` | lmax-kubernetes @ f0dd482 | **Active branch — CI/CD deploys from this one.** HA (leader election, NATS JetStream replication), plus all bug fixes and perf tuning from 2026-07-02 (see git log for the full list — projector/HdrHistogram/DB-port fixes, dedicated c2 node pool, async replication). |
| `lmax-kubernetes` | gridgain-research + state 014 | **Stale — do not build/deploy from this.** Frozen at f0dd482; missing every fix and the HA feature. Kept for reference only. |
| `gridgain-research` | lmax-sequencer-no-gc (state 009b) | Teammate's perf research branch. Frozen at `d70f703`; its work was folded into `lmax-kubernetes` at f0dd482 and hasn't diverged further. |
| `lmax-sequencer-no-gc` | state 009 | State 009b: LMAX Disruptor BLP implementation. |

Worktrees:
- `lmax-kubernetes-blp-ha` (active) → `/Users/yaakov/Desktop/Summer 26/lmax/traderX`
- `gridgain-research` → `/Users/yaakov/Desktop/Summer 26/lmax/traderX-gridgain-research`

`lmax-kubernetes-blp-ha` diverged from `lmax-kubernetes` at `f0dd482`. Common ancestor of
`lmax-kubernetes` and `gridgain-research`: `da2f9e2`.

---

## Spec-kit state lineage (YU02 → YU05)

This branch (`lmax-kubernetes-blp-ha`) is spec-kit state `YU02-lmax-kubernetes` — the GKE
deployment baseline described in this document. Three further states extend it, each with its own
spec pack under `specs/YUxx-<name>/` and its own same-named branch (never merged back into this
one — see each branch's own worktree):

| State | Branch | Parent | Status |
|---|---|---|---|
| `YU03-in-memory-risk-gateway` | `YU03-in-memory-risk-gateway` | YU02 | Done — pre-trade risk gateway (SEC 15c3-5, two-tier: hot-path gate + async limit sync) |
| `YU04-durable-control-feeds` | `YU04-durable-control-feeds` | YU03 | In progress (parallel session, shared `traderX` worktree) — outbox → JetStream durable control-plane feeds |
| `YU05-post-trade-compliance` | `YU05-post-trade-compliance` | YU03 | Done — settlement + reconciliation (incl. full-history orphan detection), regulatory reporting, TCA, real JWT-based auth/entitlements |

Full detail lives in each state's own spec pack (`spec.md`, `research.md`, ADRs under `system/`,
`generation/implementation-status.md`) — not duplicated here.
`HANDOFF-idea-INDEX.md` is the consolidated backlog of production-realism work still open beyond
these three states.

---

## Team split

Both of us now work on `lmax-kubernetes-blp-ha`, coordinated through the CI/CD pipeline (see
below) rather than direct `kubectl`/`docker push` to the shared cluster — that avoids collisions
from both pushing to the same live pods at once.

- **Yaakov** — GKE cluster, manifests, ingress, TLS, StatefulSet, deploy pipeline, CI/CD, BLP
  multi-replica failover infrastructure.
- **Tani** — (`tanidiament@gmail.com`) — BLP performance work: snapshot improvements, journal
  batch coalescing, bounded terminal-order retention. Has GCP project access (Editor +
  `clouddeploy.approver`, so he can approve releases too) and pushes to `lmax-kubernetes-blp-ha`
  same as Yaakov.

---

## GKE deployment — current state

**Live at:** `https://yaakovseif.dev`

| Item | Value                                                                                                                                                                                                                         |
|------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Cluster | GKE, single-zone; `default-pool` 3× e2-standard-2 + `blp-pool` 1× c2-standard-4                                                                                                                                               |
| Static IP | bound to `yaakovseif.dev` via DNS A record                                                                                                                                                                                    |
| TLS | cert-manager + Let's Encrypt (`cluster-addons/letsencrypt-issuer.yaml`)                                                                                                                                                       |
| Ingress | ingress-nginx → edge-proxy on port 8080 (`cluster-addons/traderx-ingress.yaml`)                                                                                                                                               |
| BLP | StatefulSet (`cluster-addons/order-matcher-statefulset.yaml`); **currently single-BLP** (1 replica, `BLP_REPLICATION_ENABLED=false`) pinned to `blp-pool` for throughput. Scale to 2 + replication=true to re-enable HA.      |
| BLP node pool | `blp-pool` = 1× c2-standard-4, `pd-standard`/50Gi boot disk, label `workload=blp`, taint `workload=blp:NoSchedule`. Dedicated high-clock cores for the single-threaded BLP: ~42k booked/s vs ~13k on the shared default-pool. |
| Recovery | `RECOVERY_SOURCE=journal`, `SNAPSHOT_INTERVAL_MS=300000` (5 min)                                                                                                                                                              |
| DB | MariaDB 11.4 (port 3306; `--lower-case-table-names=1`)                                                                                                                                                                        |
| Grafana | `grafana.yaakovseif.dev` ingress added; DNS A exists                                                                                                                                                                          |

> **Perf note (2026-07-02):** order-matcher throughput is CPU-bound on the single-threaded BLP,
> not message-bus-bound. The biggest wins were: (1) getting off BestEffort QoS with a CPU floor +
> memory headroom + tolerant health probes (a tight 1s liveness probe was SIGTERM-killing a
> busy-but-alive pod), and (2) a dedicated c2 node pool. NATS→Aeron was considered and rejected —
> it only touches the HA replication path and needs spare cores it doesn't have. See
> `HANDOFF-ha-throughput-improvements.md` for the HA-replication levers (batchRecords, pipelined ACK).

### Deploy

```bash
# Full deploy (rebuild manifests + kubectl apply -k)
bash scripts/deploy-state-YU02-lmax-kubernetes-gke.sh us-east1-docker.pkg.dev/traderx-501015/traderx

# Apply cluster-addons (ingress, TLS issuer; StatefulSet + headless are now in kustomization)
kubectl apply -f cluster-addons/

# Build images and push to Artifact Registry
bash scripts/push-state-YU02-lmax-kubernetes-gke-images.sh us-east1-docker.pkg.dev/traderx-501015/traderx
```

### Manifest pipeline

`scripts/prepare-state-YU02-lmax-kubernetes-gke-manifests.sh` takes the base manifests in
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
specs/YU02-lmax-kubernetes/generation/runtime-overrides/
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
  deploy-state-YU02-lmax-kubernetes-gke.sh          ← prepare + kubectl apply -k
  prepare-state-YU02-lmax-kubernetes-gke-manifests.sh ← manifest rewriting
  push-state-YU02-lmax-kubernetes-gke-images.sh     ← docker build + push to Artifact Registry

generated/code/target-generated/kubernetes-runtime/manifests/
  base/        ← source manifests (copied from kubernetes-runtime/manifests/base/ by pipeline)
  gke-rendered/ ← output of prepare script (what kubectl apply -k reads)
```

---

## Pending work

All items from `GRIDGAIN-TO-LMAX-KUBERNETES-HANDOFF.md` are complete, plus everything from
2026-07-02/03 (bug fixes, perf tuning, CI/CD — see git log on `lmax-kubernetes-blp-ha` and
`CLOUD-ARCHITECTURE.md`):

- ✅ MariaDB 11.4 replacing PostgreSQL across all 4 services + database image + init SQL
- ✅ BLP code merge from gridgain commit `d70f703` (journal batch coalescing, bounded terminal retention)
- ✅ Deployment/StatefulSet conflict resolved: orphaned Deployment deleted, StatefulSet is the only
  order-matcher controller now
- ✅ Failover tested: killed the PRIMARY pod under `lmax-kubernetes-blp-ha`'s async-replication
  fix → FOLLOWER promoted in ~25s, no split-brain, state intact
- ✅ BLP multi-replica HA implemented (StatefulSet×2, k8s Lease leader election, NATS JetStream
  replication, `order-matcher-primary` Service) — **but not the current deployed mode**, see below
- ✅ CI/CD: Cloud Build → Cloud Deploy for order-matcher (see "CI/CD pipeline" section)

### Current live mode: single-BLP, not HA

As of 2026-07-02, `order-matcher` is deployed **single-replica** (`BLP_REPLICATION_ENABLED=false`)
for throughput (~42k booked/s vs ~22k HA). HA works and is failover-tested, but is not what's
currently serving `yaakovseif.dev`. Check `kubectl get pods -n traderx -l app=order-matcher` —
one pod (single-BLP) vs two with PRIMARY/FOLLOWER roles (HA) tells you which mode is live. Switch
commands are in `CLOUD-ARCHITECTURE.md` §5.

### Remaining

- **DNS A record** for `grafana.yaakovseif.dev` pointing to the same static IP as `yaakovseif.dev`
- **Node failover test**: `kubectl drain <node> --ignore-daemonsets --delete-emptydir-data`
  (single-zone GKE only has 1 zone so this tests node replacement, not zone failover)
- **HA lease starvation under load** (real bug, not yet fixed): at high concurrency the
  leader-election lease renewal starves and the PRIMARY false-demotes. See
  `HANDOFF-ha-throughput-improvements.md` / `CLOUD-ARCHITECTURE.md` §7.
- **Extend CI/CD** to the other services (currently order-matcher only).

---

## GCP project access

Project `traderx-501015`. Current human members:

| Account | Key roles |
|---|---|
| `yaakov.traderx@gmail.com` | Owner |
| `tanidiament@gmail.com` | Editor, `clouddeploy.approver` |

Both can approve Cloud Deploy releases. To add someone else or change roles: IAM & Admin → IAM in
the console, or `gcloud projects add-iam-policy-binding traderx-501015 --member=user:<email> --role=<role>`.

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
bash pipeline/generate-state.sh YU02-lmax-kubernetes
```

Do not manually edit files under `generated/` — they will be overwritten. Edit the source overrides
under `specs/YU02-lmax-kubernetes/generation/runtime-overrides/` or the prepare script instead.

---

## CI/CD pipeline (order-matcher)

Set up 2026-07-03. **Cloud Build → Cloud Deploy**, GitOps-style: git is the source of truth, a
push triggers a build, and nothing touches the live cluster without an explicit manual approval.

**How it works:**
1. Push to `lmax-kubernetes-blp-ha` → GitHub webhook fires the Cloud Build trigger
   (`order-matcher-cicd`, 1st-gen GitHub connection, watches `^lmax-kubernetes-blp-ha$`).
2. `cloudbuild.yaml` runs as `traderx-cicd@traderx-501015.iam.gserviceaccount.com`:
   - `generate-and-build-jar`: regenerates `generated/` from committed `specs/` (fresh every
     time — `generated/` is gitignored, never build from a stale local copy), then
     `./gradlew bootJar` for order-matcher. Runs in `eclipse-temurin:21-jdk` with Node layered on
     via NodeSource (the generation pipeline touches other services too); apt needs
     `curl git ca-certificates jq python3 rsync zip unzip ripgrep`.
   - `docker build`/`push` → `us-east1-docker.pkg.dev/traderx-501015/traderx/order-matcher:ci-$SHORT_SHA`
     (unique tag per build, never reused).
   - `gcloud deploy releases create` — creates a Cloud Deploy release referencing that image.
3. The release sits at `PENDING_APPROVAL` (`clouddeploy.yaml`'s `production` target has
   `requireApproval: true`) — **nothing is applied to the cluster yet.**
4. Someone with `roles/clouddeploy.approver` (currently Yaakov + teammate) approves it —
   console (Cloud Deploy → order-matcher-pipeline) or
   `gcloud deploy rollouts approve <rollout> --release=<release> --delivery-pipeline=order-matcher-pipeline --region=us-east1`.
5. Cloud Deploy renders `cluster-addons/order-matcher-statefulset.yaml` via `skaffold.yaml`
   (substitutes the built image into the manifest's `image:` field) and applies it to the
   `traderx-lmax` cluster.

**Config files:** `cloudbuild.yaml` (build), `clouddeploy.yaml` (pipeline + target),
`skaffold.yaml` (render/deploy manifest mapping) — all at repo root.

**IAM setup (not in git):** dedicated `traderx-cicd@traderx-501015.iam.gserviceaccount.com`
service account with `artifactregistry.writer`, `clouddeploy.releaser`, `clouddeploy.jobRunner`,
`container.developer`, `logging.logWriter`, `cloudbuild.builds.builder`, plus a **self-referential**
`iam.serviceAccountUser` binding on itself (required — Cloud Deploy's "ActAs" check applies even
when the triggering and executing SA are the same). Cloud Build's own service agent
(`service-397259609626@gcp-sa-cloudbuild.iam.gserviceaccount.com`) has `iam.serviceAccountTokenCreator`
on `traderx-cicd` so it can act as it. Artifacts land in `gs://traderx-501015-clouddeploy-artifacts`
(a plain project-owned bucket — Cloud Deploy's *default* artifact bucket uses App Engine's
domain-verified `<region>.deploy-artifacts.<project>.appspot.com` naming, which only Cloud Deploy's
own internal identity can provision, not a normal `gcloud storage buckets create`).

**Scope:** currently order-matcher only (the highest-value, most fragile service). The other
services (`account-service`, `trade-processor`, etc.) still deploy via the manual path below —
extend the same skaffold/Cloud Deploy pattern to them if this proves out.

**Validated:** ran the full pipeline manually twice (`gcloud builds submit --config=cloudbuild.yaml`)
before wiring up the trigger — confirmed the approval gate actually holds (`PENDING_APPROVAL`
state) and does not touch the live StatefulSet pods until approved.

---

## Deploy discipline

The CI/CD pipeline above is now the preferred path for order-matcher — use it instead of manual
`kubectl set image`/`docker push`. For anything CI/CD doesn't cover yet (other services, node
pool changes, manifest fields outside the image tag), the same rules from before still apply, since
this is exactly the drift class of bug CI/CD exists to close: every production bug found on
2026-07-02 (broken MariaDB projector, Postgres driver + `DATABASE_PG_PORT=5432` against MariaDB)
had the fix already in git/`generated/`, but GKE running an older image built before the fix.

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
