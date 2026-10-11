# TraderX on the CompSci project

Preparation target: project `project-compsci-484414`, cluster `traderx-demo`,
zone `us-east1-b`, namespace `traderx`. Source base: `238dd1f4` (accepted YU18).
This is a fresh cold synthetic demo profile. It does not establish retained-state
compatibility, connected portfolio risk, financial correctness or benchmark capacity.
Alex's risk engine is not upgraded or deployed by this profile.

## Prepared configuration

The renderer takes explicit inherited GKE engine manifests, current fully generated
service manifests and the inherited console configuration. The generated database
schema comes first. It uses the CompSci registry and these migrated buckets:

| Purpose | Bucket |
| --- | --- |
| Raw TAQ | `project-compsci-484414-traderx-taq-raw` |
| Extract delivery | `project-compsci-484414-traderx-risk-extracts` |
| Journal archive | `project-compsci-484414-traderx-journal-archive` |

Only the extract writer and console consume storage in this initial profile.
TAQ reader permission is prepared for a separate explicit replay workload; the
publisher does not fetch or reinterpret the raw vendor dataset automatically.
Legacy restore and backup jobs are excluded. All Services are internal ClusterIP;
there is no Ingress, external load balancer, automatic seed job or automatic EOD cut.
Use local port forwarding for the first proof. Source buckets stay intact.

The engine retains the inherited `emptyDir` demo storage. Pod/node loss can destroy
engine state. Do not resize to zero after meaningful trading and treat subsequent
boots as restoration. The database PVC alone does not make this a durable profile.

## Build and render

Run from this owned checkout. Use JDK 21 and Python with `PyYAML==6.0.3`.
Docker must be running; builds target `linux/amd64` explicitly.

```bash
export SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"
TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh YU18-risk-integration
python3 scripts/gcp-compsci/prepare.py --output /private/tmp/compsci-manifests \
  --tag <unique-candidate-tag> --dns-ip <actual-kube-dns-service-IP>
python3 scripts/gcp-compsci/build-images.py --output /private/tmp/compsci-images \
  --tag <same-unique-candidate-tag>
python3 scripts/gcp-compsci/test_prepare.py
```

The build runner admits all selected contexts against independent full-ancestry
generation before compiling anything. The root-owned console has a separate
content-hashed snapshot. Evidence includes derivation, context hashes and actual
Docker image IDs/architectures. No push/apply occurs. The renderer's `candidate.yaml`
is intentionally not deployment-ready: local candidate tags must be replaced by
published immutable repository digests, and per-image payload evidence reviewed.
The existing RI20 GKE entrypoint cannot consume this custom profile without an
explicit compatible admission adapter; do not use it or disable its checks.

The candidate adds opt-in `SOURCE_DATE_EPOCH` for reproducible UI timestamps and
preserves a credential-free origin URL in the isolated derivation repository.
This fixes independently measured metadata-only differences; exact context hashing
and rejection of changed build inputs remain enabled. Default generation timestamps
remain unchanged when the variable is unset.

## Node plan

Existing `default-pool`: one `e2-standard-4` support node.
Proposed additional `engine-pool`: three `e2-standard-4` nodes, one member per node,
taint `traderx-engine=true:NoSchedule`. Each member requests three CPUs and 8 GiB,
exactly as its inherited configuration. Gateway/console replicas are one for the
initial functional demo. Anti-affinity must keep members on separate hostnames.
No autoscaling, spot nodes, performance tuning or full-HA gateway claim.

Prepared command for the additional pool (do not run during offline preparation):

```bash
gcloud container node-pools create engine-pool \
  --project=project-compsci-484414 --cluster=traderx-demo --zone=us-east1-b \
  --node-locations=us-east1-b --num-nodes=3 --machine-type=e2-standard-4 \
  --disk-type=pd-balanced --disk-size=30 --image-type=COS_CONTAINERD \
  --service-account=traderx-gke-nodes@project-compsci-484414.iam.gserviceaccount.com \
  --scopes=https://www.googleapis.com/auth/cloud-platform \
  --workload-metadata=GKE_METADATA --node-taints=traderx-engine=true:NoSchedule
```

This adds three paid nodes; the existing support node remains. Total nominal
resources become 16 vCPUs / 64 GiB across four nodes. No pool was created by preparation.

`plan.json` records declared CPU/memory requests, not measured capacity. Services
without explicit requests need runtime measurement; the support node has not been
qualified for the complete stack. Additional nodes require an explicit paid-compute
operation; preparation creates none. Check quota/stock before node-pool creation.

## Storage authentication

Review `scripts/gcp-compsci/storage-access.sh`; `--apply` is an explicit IAM mutation.
It grants the extract writer only Object Creator and Object Viewer on the extract
bucket, and grants console/TAQ reader only Viewer on their respective buckets.
It grants no node-wide bucket access, object deletion, or journal restoration.

The current Java sink uses the GCS XML/S3-compatible API and requires HMAC; Workload
Identity does not replace that credential. Provision a dedicated HMAC key for
`traderx-risk-extract@project-compsci-484414.iam.gserviceaccount.com` into the private
Kubernetes Secret `risk-extract-gcs-hmac` (keys `access-key-id`, `secret-access-key`).
Never paste the HMAC secret, store it in tracked YAML, or reuse a journal credential.
HMAC provisioning and create/read/overwrite-denial live proof remain pending.

The console uses direct Workload Identity for read-only archive access. Required
application secrets are listed in `plan.json`; do not use the inherited seeded
console password or public development signing keys. Database credentials must
match the database bootstrap configuration. Optional sandbox credentials do not
enable a sandbox here.

## Before application deployment

1. Review exact candidate images, registry digests and source/packaged payload evidence.
2. Configure private application secrets and scoped storage authentication.
3. Approve/create the additional engine pool and verify member placement.
4. Validate the exact immutable pack with server dry-run; create the schema before DB boot.
5. Apply only the reviewed pack, then compare running image IDs with the lock.
6. Prove authenticated order -> engine booking -> SQL/read model and extract storage
   with nonempty effects and failed overwrite control. Record scenario and identities.

Do not infer deployment acceptance from rendering, image build or Kubernetes readiness.
Before stopping compute, export required evidence/state and document the cold-start
semantics; retained-data deletion and source-bucket cleanup are separate operations.
