# YU12 Aeron Cluster — GKE deploy + bench hand-over (run by yaakov)

GCP commands are yours to run (working convention). This is the exact, ordered command set to
put the 3-member Aeron Cluster on GKE and benchmark it with label `aeron-cluster` against the
stored YU11 Aeron HA baseline (25,149 booked/s cleanest run). Nothing here has run on GKE — it is
derived from the working kind deployment (`scripts/yu12/*`, `specs/YU12-aeron-cluster/generation/
kubernetes/cluster/`) plus the inherited GKE bench harness (`scripts/bench/run-gke-bench.sh`).

Project `traderx-501015`, cluster `traderx-lmax`, zone `us-east1-b`, namespace `traderx`.
Context: `gke_traderx-501015_us-east1-b_traderx-lmax`.

## 0. Preconditions

- `blp-pool` is currently at 0 nodes (scaled down). It needs **3** nodes for a 3-member cluster
  with required anti-affinity (one member per node).
- The cluster image `traderx/cluster-node:yu12` exists locally; GKE needs it in a registry the
  cluster can pull (Artifact Registry or GCR).

## 1. Scale blp-pool to 3

```bash
gcloud container clusters resize traderx-lmax --node-pool blp-pool \
  --num-nodes 3 --zone us-east1-b --quiet
# default-pool also needs to be up for NATS/DB/gateway if you run the full stack:
gcloud container clusters resize traderx-lmax --node-pool default-pool \
  --num-nodes 3 --zone us-east1-b --quiet
kubectl config use-context gke_traderx-501015_us-east1-b_traderx-lmax
kubectl get nodes -l cloud.google.com/gke-nodepool=blp-pool
```

## 2. Build + push the cluster image

```bash
# From the worktree root. Uses the host bootJar + Dockerfile.cluster (same as scripts/yu12/build-cluster-image.sh).
REGION=us-east1
REPO=us-east1-docker.pkg.dev/traderx-501015/traderx        # adjust to your Artifact Registry repo
IMG=${REPO}/cluster-node:yu12
YU12_CLUSTER_IMAGE=${IMG} bash scripts/yu12/build-cluster-image.sh
gcloud auth configure-docker ${REGION}-docker.pkg.dev --quiet
docker push ${IMG}
```

## 3. GKE manifest overlay

The kind manifests (`specs/YU12-aeron-cluster/generation/kubernetes/cluster/`) are GKE-ready
except three things — apply these deltas (a kustomize overlay or by hand):

- **image**: set `image:` on the StatefulSet + proof-client to `${IMG}` above,
  `imagePullPolicy: Always`.
- **storageClass**: the volumeClaimTemplate uses the default `standard`; on GKE that is
  `standard-rwo` (pd-balanced). Set `storageClassName: standard-rwo` (or `premium-rwo` for SSD).
- **anti-affinity**: change `preferredDuringScheduling…` to
  `requiredDuringSchedulingIgnoredDuringExecution` so each member lands on a distinct blp-pool
  node (kind used `preferred` because it has fewer schedulable nodes). Add a nodeSelector
  `cloud.google.com/gke-nodepool: blp-pool`.
- The `term-length=64k` egress fix and the Memory `/dev/shm` mount are already in the manifests
  and are REQUIRED (root cause of the kind egress wedge; the same default-/dev/shm limit applies
  on GKE).

```bash
kubectl -n traderx apply -k specs/YU12-aeron-cluster/generation/kubernetes/cluster/   # after editing the overlay
kubectl -n traderx rollout status statefulset/order-matcher-cluster --timeout=300s
for i in 0 1 2; do
  kubectl get --raw "/api/v1/namespaces/traderx/pods/order-matcher-cluster-$i:8080/proxy/health"; echo
done   # expect one LEADER, two FOLLOWER
```

## 4. Deploy the gateway (REST + FIX ingress)

The gateway (`ClusterGatewayMain`) terminates REST/FIX and forwards through the cluster client.
Run it as a Deployment on the default-pool, pointing at the members' ingress endpoints
(`0=order-matcher-cluster-0.order-matcher-cluster.traderx.svc.cluster.local:21802,1=…:21902,
2=…:22002`), env `GATEWAY_HTTP_PORT=18110`, a Memory `/dev/shm` mount, and the same
`--add-exports/--add-opens` JAVA_TOOL_OPTIONS. Expose it as a `Service` named `order-matcher` on
18110 so the inherited bench harness finds it unchanged. (A gateway Deployment manifest is not yet
written — model it on `proof-client.yaml` with `command: […ClusterGatewayMain]` + a Service.)

## 5. Bench — label `aeron-cluster`

`run-gke-bench.sh` reads `traderx_order_events_total{event=fill|…}` from
`http://order-matcher.traderx.svc.cluster.local:18110/metrics` and posts to `/orders/batch`.

The gateway now serves the bench surface directly: `/orders/batch` (JSON array → each order
submitted through the committed path), and `/metrics` emitting
`traderx_order_events_total{event="fill"} <n>` (a fill counter incremented in `onEgress` for
every committed fill-kind ack) plus `{event="accepted"}`. So `run-gke-bench.sh aeron-cluster`
runs unchanged against the gateway Service on 18110.

Also note the **throughput lever**: the gateway submits one order at a time on its single client
owner thread (correctness-first, FIFO ack correlation). If the bench falls short of the 25,149
booked/s baseline, the fix is to PIPELINE the owner thread (offer many, correlate acks by a
per-order id) — exactly where YU11's numbers came from (amortized batch ack). This is the
expected optimization axis, not a defect.

```bash
kubectl -n traderx apply -f <bench-runner pod with /batch-load.mjs>   # inherited from YU09 bench assets
bash scripts/bench/run-gke-bench.sh aeron-cluster 3 30 1000 48
# rows append to scripts/bench/results/gke-comparison.csv, label aeron-cluster
```

Compare run-1 booked/s to the stored `yu11-aeron-ha` baseline (25,149 cleanest). Gate NFR-AC02:
meet or exceed single-BLP parity.

## 6. Client-observed failover on GKE (NFR-AC03)

The kind proof measured ~15 s client-observed failover — that is the DEFAULT Raft election
timeout on a slow Docker-Desktop host, NOT a YU12 property (the same ~15-17 s the parent state
saw on kind). To approach the sub-1 s NFR-AC03 target, tune the consensus module timeouts in
`ClusterNodeConfig` (`ConsensusModule.Context`): `leaderHeartbeatIntervalNs`,
`leaderHeartbeatTimeoutNs`, `startupCanvassTimeoutNs`, `electionTimeoutNs` — GKE's flat pod
network + faster nodes plus tightened timeouts is where three-digit-ms failover comes from.
Re-run `scripts/yu12/crash-proof-kind.sh` shape against GKE (the GAP line is the measurement).

## 7. Scale back down when done

```bash
gcloud container clusters resize traderx-lmax --node-pool blp-pool --num-nodes 0 --zone us-east1-b --quiet
gcloud container clusters resize traderx-lmax --node-pool default-pool --num-nodes 0 --zone us-east1-b --quiet
```
