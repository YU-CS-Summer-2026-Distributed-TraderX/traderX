# Quickstart: YU02-lmax-kubernetes

TraderX on Kubernetes with an LMAX-architecture Business Logic Processor (BLP): single-threaded,
in-memory order matching, journaled to disk, with periodic snapshots for fast recovery.

## Local (kind)

### First run

```bash
bash pipeline/generate-state.sh YU02-lmax-kubernetes
bash generated/code/target-generated/scripts/start-state-YU02-lmax-kubernetes-generated.sh \
  --provider kind --without-sail
```

UI at **http://127.0.0.1:8080**. The script generates state, builds all images, loads them into
the kind cluster (`traderx-state-014`), applies manifests, and waits for all services to be ready.

> **If you already have the cluster running from a previous GKE-rendered deploy**, the base
> kustomization will create an `order-matcher` Deployment that conflicts with any leftover
> StatefulSet. Use `--recreate-cluster` to wipe and rebuild cleanly:
> ```bash
> bash generated/code/target-generated/scripts/start-state-YU02-lmax-kubernetes-generated.sh \
>   --provider kind --without-sail --recreate-cluster
> ```

### Subsequent runs (skip rebuild if code unchanged)

```bash
bash generated/code/target-generated/scripts/start-state-YU02-lmax-kubernetes-generated.sh \
  --provider kind --without-sail --skip-build
```

### Validate

```bash
bash scripts/test-state-YU02-lmax-kubernetes.sh
```

Validated closeout path:

```bash
bash pipeline/generate-state.sh YU02-lmax-kubernetes
bash generated/code/target-generated/scripts/test-state-YU02-lmax-kubernetes.sh
```

### Stop

```bash
bash generated/code/target-generated/scripts/stop-state-YU02-lmax-kubernetes-generated.sh
# or delete the cluster entirely:
kind delete cluster --name traderx-state-014
```

---

## GKE

### One-time cluster setup

```bash
gcloud container clusters create traderx-lmax \
  --zone us-east1-b \
  --machine-type e2-standard-2 \
  --num-nodes 3 \
  --release-channel regular

gcloud container clusters get-credentials traderx-lmax --zone us-east1-b
```

Install ingress-nginx and cert-manager, then apply cluster-level addons:

```bash
kubectl apply -f cluster-addons/
```

### Deploy (normal redeploy flow)

Generate state, push images, render GKE manifests, and apply:

```bash
bash pipeline/generate-state.sh YU02-lmax-kubernetes

bash scripts/push-state-YU02-lmax-kubernetes-gke-images.sh \
  us-east1-docker.pkg.dev/traderx-501015/traderx

bash scripts/deploy-state-YU02-lmax-kubernetes-gke.sh \
  us-east1-docker.pkg.dev/traderx-501015/traderx

kubectl apply -f cluster-addons/
```

`deploy-state-YU02-lmax-kubernetes-gke.sh` runs the prepare script then `kubectl apply -k`. The prepare
script (`scripts/prepare-state-YU02-lmax-kubernetes-gke-manifests.sh`) rewrites the base manifests for
GKE:

- Rewrites image tags to the Artifact Registry prefix
- Converts `edge-proxy` from `NodePort` to `ClusterIP` (ingress-nginx handles external access)
- Folds `order-matcher-statefulset.yaml` + headless service into the kustomization (replacing
  the base `order-matcher-deployment.yaml` + PVC)
- Injects pod anti-affinity on all Deployments

Override CORS if needed:

```bash
TRADERX_CORS_ALLOWED_ORIGINS=https://yaakovseif.dev \
  bash scripts/deploy-state-YU02-lmax-kubernetes-gke.sh \
    us-east1-docker.pkg.dev/traderx-501015/traderx
```

### Accessing the cluster

| Endpoint | URL |
|----------|-----|
| TraderX UI | https://yaakovseif.dev |
| Grafana | https://grafana.yaakovseif.dev (DNS A record needed) |
| Grafana (port-forward) | `kubectl port-forward svc/grafana 3000:3000 -n traderx` |

### Failover test

```bash
# BLP pod restart → journal replay:
kubectl delete pod order-matcher-0 -n traderx
kubectl logs -f order-matcher-0 -n traderx   # watch for "LIVE RECOVERY [journal]"

# Node drain → pod reschedule:
kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data
kubectl uncordon <node-name>
```

---

## Runtime notes

- **Database**: MariaDB 11.4 (port 3306, `--lower-case-table-names=1`). Read-model only — the
  BLP journal is the source of truth.
- **BLP recovery**: `RECOVERY_SOURCE=journal`. On restart, replays the binary journal from the
  last snapshot boundary. Readiness probe gates traffic until replay completes.
- **Journal/snapshot path**: `/var/lib/traderx-lmax/journal` on the StatefulSet PVC
  (`lmax-runtime-data-order-matcher-0`). Snapshot interval: 5 min (`SNAPSHOT_INTERVAL_MS=300000`).
- **BLP performance**: journal write-coalescing (`journal.batch.records=1024`), bounded terminal
  retention (`blp.terminal.retain=262144`).
- **Local kind note**: images are cached as `docker.io/traderx/<name>:state009`. After any image
  rebuild you must reload them: `kind load docker-image <image> --name traderx-state-014`.
