# TraderX on the CompSci project

October11 accepted state: the initial pack and hosted repairs through `1132510f`
are approved and integrated in canonical (merge `85afc999`). Trusted HTTPS works at
`https://demo.yaakovseif.dev` and `https://desk.yaakovseif.dev`, restricted to the
operator network by Cloud Armor. The old plaintext Service/address is retired.
All four EOD stages, authenticated controls and repaired presets were verified live.
See the [completed repair record](../../issues/risk-integration/resolved/compsci-demo-repairs-20261011.md)
and [current queue](../../issues/risk-integration/20261011-current-index.md).

Preparation target: project `project-compsci-484414`, cluster `traderx-demo`,
zone `us-east1-b`, namespace `traderx`. Initial preparation base: `238dd1f4` (accepted YU18 at that time).
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
Legacy restore and backup jobs are excluded. The base admitted pack uses internal ClusterIP Services;
the separately authorized HTTPS resources below add the hosted frontend. The base
pack has no automatic seed job or automatic EOD cut.
Use local port forwarding for the first proof. Source buckets stay intact.

The engine keeps the inherited pod-lifetime storage, but since 2026-10-26 each member's
`/data` (Aeron log + archive) is a dedicated 32 GiB Hyperdisk Balanced ext4 volume
(`storageclass.yaml` `order-matcher-archive`, a generic ephemeral volume in
`statefulset-emptydir.yaml`; `prepare.py` renders the class into the candidate since 2026-10-27, so a
fresh cluster needs no hand-applied object), not the node boot disk. The stall hunt on the hxb rig
(STALLS.md, 2026-10-25) traced every between-snapshot member spike at 400K orders/s to the
leader's archive write waiting in the boot disk's shared ext4 journal: 5-6 waits per 3 minutes
on the boot disk, 0 on a dedicated volume. The volume is created with the pod and deleted with
it, so recovery semantics are unchanged: pod/node loss still destroys that member's state and
it rejoins by catch-up. Hyperdisk attaches to C4D/C4/C3/N4 machines only; on an N2 pool set
the class's `type` to `pd-balanced`. Do not resize to zero after meaningful trading and treat
subsequent boots as restoration. The database PVC alone does not make this a durable profile.

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
requires per-image payload review and deployment admission before apply. Pass
`--published <published.json> --public-lock scripts/gcp-compsci/public-image-lock.json` to reproduce
the private/public immutable pins from reviewed locks. Both locks are required
together; public pins must select the recorded linux/amd64 manifest and private
pins must agree with the recorded registry identity.
The reviewed public lock is committed at `scripts/gcp-compsci/public-image-lock.json`: the
October 11 lock plus `grafana/promtail:2.9.8` (amd64 child `sha256:0a818ddc…`, added 2026-10-27 because
the render emits promtail since `c8c8ac31`). Every pin was re-checked with `docker buildx imagetools
inspect` that day; the `nats` and `mariadb` tags have since moved upstream, the pinned digests have not.
The inherited fixed-path RI20 GKE entrypoint does not consume this custom profile.
Use the explicit `scripts/gcp-compsci/admit.py` companion below; the inherited
admission path and its refusal rules remain unchanged.

`admit.py` admits only images built from `REVIEWED_IMAGE_COMMIT` (one constant in `admit.py`; `9e0fd80b` since
2026-10-27). Its measured evidence is produced by `measure-admission.py`: on a checkout of exactly that commit,
rendered with that commit's `SOURCE_DATE_EPOCH` and its GitHub origin, run

```bash
python3 scripts/gcp-compsci/measure-admission.py --rendered <candidate.yaml> --work <new folder> --build-records <build-images.py output>
```

It reads the population from the candidate, derives the contexts independently, compares every image payload
with an independent reference build, records OCI and runtime-architecture evidence, then runs
`prepare-admission.py`, `admit.py` and `admission-controls.py`. Moving to a new image commit means changing the
constant and rerunning it.

The candidate adds opt-in `SOURCE_DATE_EPOCH` for reproducible UI timestamps and
preserves a credential-free origin URL in the isolated derivation repository.
This fixes independently measured metadata-only differences; exact context hashing
and rejection of changed build inputs remain enabled. Default generation timestamps
remain unchanged when the variable is unset.

## Node plan

Existing `default-pool`: one `e2-standard-4` support node.
User-created `engine-pool`: three `c4d-standard-8` nodes, one member per node,
taint `traderx-engine=true:NoSchedule`. Each member requests three CPUs and 16 GiB
(12 GB heap, ADR-079 §6), exactly as its inherited configuration. With the archive purge
sidecar (2.5 CPU, not wired by default; ADR-079 §6), run members on `c4d-standard-16`. Gateway/console replicas are one for the
initial functional demo. Anti-affinity must keep members on separate hostnames.
No autoscaling, spot nodes, performance tuning or full-HA gateway claim.

The member NetworkPolicy (ADR-079 §4) is enforced only on a cluster created with Dataplane V2
(`gcloud container clusters create ... --enable-dataplane-v2`); it cannot be enabled on an
existing cluster, and on a cluster without it the policy is applied and inert, as measured on
the hxb rig (EOD-RISK.md). Whether `traderx-demo` was created with it has not been verified
from this checkout. Create the next production cluster with the flag, and after the members
are Running run `scripts/proofs/yu18-networkpolicy-enforced.sh` (positive and negative control
through the real Aeron ingress); until it passes, `CLUSTER_CLIENT_SECRET` is the only control
that closes the ingress.

Operator creation command (do not repeat for the existing pool):

```bash
gcloud container node-pools create engine-pool \
  --project=project-compsci-484414 --cluster=traderx-demo --zone=us-east1-b \
  --account=aseif@mail.yu.edu \
  --node-locations=us-east1-b --num-nodes=3 --machine-type=c4d-standard-8 \
  --disk-type=hyperdisk-balanced --disk-size=100 --image-type=COS_CONTAINERD \
  --boot-disk-provisioned-iops=3000 --boot-disk-provisioned-throughput=140 \
  --enable-gvnic \
  --service-account=traderx-gke-nodes@project-compsci-484414.iam.gserviceaccount.com \
  --scopes=https://www.googleapis.com/auth/cloud-platform \
  --workload-metadata=GKE_METADATA --node-taints=traderx-engine=true:NoSchedule
```

The operator created the pool; all four nodes were API-verified Ready on October 11.
Nominal resources are 28 vCPUs / 109 GiB across four nodes. The engine pool remains
tainted and the member manifest requires separate hostnames.

The renderer declares initial CPU/memory envelopes for the eleven previously
unbounded support workloads. These are cold-demo startup budgets, not production
sizing. Support requests are 2.4 CPUs / 4,960 MiB; existing system pods requested
0.89 CPUs / 1,679 MiB at the October 11 observation. Together they fit the observed
support allocatable 3.92 CPUs / 13,273 MiB. This is scheduler-budget fit;
startup/load, throttling and memory behavior still require live observation.
Additional capacity remains an explicit paid-compute operation.

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
The dedicated HMAC was privately created on October 11. An actual-container proof
using the pinned Java sink created three unique synthetic objects (69 body bytes),
verified exact reads before/after both overwrite forms, received three unconditional
and three conditional 403 AccessDenied refusals, and rejected the wrong-secret
control with 403 SignatureDoesNotMatch. The proof objects are retained. The earlier
probe URI ending with a slash produced a double slash and failed signing; read-only
reconciliation found no objects, and correcting only the harness URI passed. The
runtime pack uses the bucket root URI and required no IAM or image change.

`scripts/gcp-compsci/provision-secrets.py` generates random app/DB/Grafana credentials
and a Node-compatible console scrypt hash. It saves values in an explicit owner-only
directory outside the checkout and submits Secret objects over stdin, keeping
values out of command arguments and output. Existing Secrets must match saved
credentials; no implicit rotation. HMAC creation is marked before submission;
uncertain outcomes refuse automatic retry.

October 11 setup created and read-back verified `auth-secrets`,
`mariadb-credentials`, `grafana-credentials` and `risk-extract-gcs-hmac`.
Local material is in `/Users/yaakov/.config/traderx/compsci/credentials.json` (0600),
with the one-time HMAC response retained in the same 0700 directory. Private
console/Grafana passwords are in that file; keep its values out of Git and board posts.

The console uses direct Workload Identity for read-only archive access. Required
application secrets are listed in `plan.json`; do not use the inherited seeded
console password or public development signing keys. Database credentials must
match the database bootstrap configuration. Optional sandbox credentials do not
enable a sandbox here. Database bootstrap, migration and clients use the same Secret
with a separate root password. The readiness probe reads runtime credentials.
Grafana also uses a private Secret reference.

## Before application deployment

The companion's `compiled-context-v1` provider binds independently derived full
ancestry, original build context/labels and separately measured reference/actual
application payload entries. Java archives are compared by all entry bytes;
the .NET publish and console static output are independently compiled, Node
recipes independently rebuilt, and API Explorer content matched to source.
Actual executable architecture measurements bind the exact image IDs.
`locked-upstream-v1` covers the eight reviewed upstream immutable OCI manifests,
configurations and layers; it does not claim their upstream source was rebuilt.
OCI indexes must select exactly one recorded amd64 child. Root-console ownership
and upstream images are explicit, without inventing RI18 producer labels.

Package measurements with `prepare-admission.py`, then run:

```bash
python3 scripts/gcp-compsci/admit.py \
  --root "$PWD" \
  --rendered <evidence>/candidate.yaml \
  --expected <evidence>/expected.json \
  --artifacts <evidence>/artifacts.json \
  --derivation <evidence>/derivation.json \
  --output <new-admitted-directory> \
  --context gke_project-compsci-484414_us-east1-b_traderx-demo \
  --project project-compsci-484414 --location us-east1-b \
  --cluster traderx-demo --namespace traderx
```

Admission is read-only and writes the exact admitted manifest snapshot. Changed
source/evidence/payload/target/platform, missing slots, duplicate images and empty
populations are refused. A new source revision needs a fresh independent derivation;
unchanged complete contexts can retain the independently measured payload evidence.
The caller must obtain rollout approval before applying the admitted snapshot.

1. Review exact candidate images, registry digests and source/packaged payload evidence.
2. Verify scoped storage create/read/overwrite-denial with the provisioned HMAC.
3. Verify application member placement on the already-created engine pool.
4. Review the exact immutable pack's server dry-run; create the schema before DB boot.
5. Apply only the reviewed pack, then compare running image IDs with the lock.
6. Prove authenticated order -> engine booking -> SQL/read model and extract storage
   with nonempty effects and failed overwrite control. Record scenario and identities.

Do not infer deployment acceptance from rendering, image build or Kubernetes readiness.
Before stopping compute, export required evidence/state and document the cold-start
semantics; retained-data deletion and source-bucket cleanup are separate operations.


## Demo account initialization

After a cold application rollout, run `python3 scripts/gcp-compsci/initialize-accounts.py --apply` from the CompSci checkout. This reads the nonempty live directory, admits absent accounts through the sequenced control endpoint, and verifies each directory ID is enabled on all three member engine tables. This includes Test Account 20 (22214). Existing explicit suspensions fail closed for operator review. The command without `--apply` only verifies. An ambiguous submission stops; reconcile member state before rerunning.

The console Accounts controls require administrator sign-in. The hosted server supplies its private control credential; a browser-provided token cannot override it. The table records this browser session's successful commands, not a live engine-state readout. Initialization does not submit orders, import TAQ data, or change retained positions.


## Full demo reference initialization

After account initialization, run `python3 scripts/gcp-compsci/initialize-reference-data.py --apply`. It compares the complete instrument catalog with its control snapshot, registers the catalog and configured publisher option chain, keeps matured/expired securities disabled, and sequences missing demo marks from the existing publisher while preserving existing marks. It does not fabricate prices for unquoted instruments or non-USD FX rates. Limit orders may supply a price for unquoted enabled instruments; market-order pricing remains limited to the available demo marks.

The bootstrap reapplies the existing session phase as an acknowledgement fence only while all members agree and their queues are empty. It verifies all three members have applied the returned sequence, then checks the complete gateway control inventory and Amazon member marks. The gateway inventory is not a direct member security-table readout. Use the command without `--apply` for read-only inventory and Amazon-mark verification. Check the deployed counterparty CSV covers every directory account. No market order, trade, external feed fetch, or TAQ conversion is part of initialization.

The Accounts page renders its controls only for a signed-in administrator; account creation and enable/disable are also server-gated. Requested password changes update the private credential file and Secret-backed scrypt hash, rotate the console session key, and require a console rollout. Never put a plaintext password in source, deployment manifests or evidence reports.

## Optional restricted external console

The base admitted pack remains internal. Separately authorized external access uses
`console-external`, selecting only the console on TCP80 →8080. Reserve the regional
IPv4 address `traderx-console` in `us-east1`, then render with
`scripts/gcp-compsci/render-frontend-access.py --address <reserved-ip> --source <your-public-ip>`.
The renderer accepts only one public IPv4 source and emits a /32 allowlist; it cannot
render an unrestricted public endpoint. Review and server-dry-run the emitted Service
before applying it to the explicit CompSci context. This separate Service does not
change images, the internal Service, or engine state. GKE creates a backend-service
external load balancer and source-restricted firewall rule. The reserved address and
load balancer remain billable resources until explicitly removed.

Open `http://<reserved-ip>/`. This profile is HTTP, not TLS: the source restriction
limits reachability but does not encrypt sign-in traffic. Use the existing encrypted
port-forward path for credential entry until HTTPS is configured. If your network's
public IP changes, rerender and apply the /32 allowlist. Do not widen it to 0.0.0.0/0.

The Today's end of day error mentioning a dev proxy can also mean a dropped connection.
Check the actual `/eod/chain?date=YYYY-MM-DD` response and the connection before changing
EOD state. A failed port-forward can leave the loaded SPA visible while every refresh
fails. A chain read is read-only; never trigger another close or publish to repair access.

## HTTPS console at demo.yaakovseif.dev

The separate `scripts/gcp-compsci/https-frontend.json` provisions a GKE managed
certificate, frontend HTTP-to-HTTPS redirect, console-only Service, BackendConfig
and Ingress. Its global static address is `traderx-console-https` (`34.54.116.2`).
Name.com needs an A record `demo` pointing to that address. Do not add an AAAA
record for this IPv4-only endpoint or replace the domain's other records.

Before applying the manifest, create and verify the Cloud Armor policy
`traderx-console-network`: default priority2147483647 denies403; priority1000
allows only the operator's current public IPv4 /32. Both rules must enforce,
not preview. BackendConfig attaches this policy to the console backend. The
policy is required before exposure; never temporarily allow all sources for
certificate issuance. Google manages certificate renewal. The frontend preserves
`X-Forwarded-Proto: https`, which causes the existing console login handler to
set its HttpOnly/SameSite cookie Secure.

Completion requires certificate Active, valid hostname TLS without skipping
verification, the actual backend's securityPolicy attached, HTTP redirection,
signed-out account controls401, authenticated login with a Secure cookie, and
EOD refresh over HTTPS. Resource creation alone is not acceptance. Once verified,
retire the separately created plaintext console-external Service and regional
traderx-console address; keep all application pods, images and state intact.
HTTPS provisioning and DNS may take time. Until certificate validation passes,
do not enter credentials or bypass a browser certificate warning.

## Hosted Desk and demo corrections (October 11)

`desk.yaakovseif.dev` uses a separate single-replica Desk Deployment, 1Gi membership
PVC, admitted-console-based amd64 image, and the same network-restricted Cloud Armor
policy. `render-desk-deployment.py` derives the Secret references, service account,
resource limits and bridge endpoints from the exact admitted console Deployment.
Its Recreate strategy preserves the single-process session/file contract. A new
`desk-https` managed certificate is added to the existing HTTPS Ingress; the original
console certificate is preserved. Name.com needs a `desk` A record to34.54.116.2.
This profile adds one support workload, no engine node. Operator password is required
for every hosted Desk session. Workspace names are not production trader identities.

The console validates browser Origin at its own proxy boundary, then removes it on
the internal hop; backend localhost CORS no longer rejects hosted EOD/reconciliation
requests. Foreign origins are refused before credential forwarding. FIX starts its
quote subscription even when opened directly, and missing marks give an explicit
message. The listed-option story selects an unexpired quoted AAPL240call (December2026 on this rig), records
the offered price, and reuses it for the second ticket. A remote extract is complete only when its
service READY verdict confirms production, with the recorded version in its detail.

EOD chain, extract-volume and GCS archive reads use asynchronous child processes.
The local HTTP concurrency test starts the actual console against slow fake CLI
readers and requires /healthz to answer before the EOD request completes. This
checks that storage/cluster reads do not block sign-in, health or unrelated API
requests. A failed connection now reports a connection failure, rather than
assuming a developer proxy is absent. Existing CLI timeouts and unreadable-stage
semantics remain in force.

CLI read subprocesses share a bounded scheduler (default two active,64queued;
`CONSOLE_CLI_CONCURRENCY` permits the measured comparison values1/2/3). Unbounded
parallel GCS reads caused a recorded768Mi OOM during a cold EOD page load. Both
shell and argv-based readers now consume the same slots; overflow fails explicitly.
`/healthz` reports only scheduler counts, not commands or credentials. Validate peak
cgroup memory and cold-page latency together before changing this bound.

The option story reads the selected contract's admitted engine reference via
`/m0/bbo` and reuses it across both tickets. A drifting display quote cannot silently
replace the engine's collar reference; a missing reference refuses the preset.
No further synthetic orders are needed merely to verify field selection.

The October11 cold-read sizing proof compared21 actual EOD/archive GET requests.
Two CLI slots peaked at219MiB of the768MiB limit, completed the full read workload
in29s and the chain in10s, without restarts. One slot took51s at146MiB; three took25s
at277MiB. These are bounded demo observations, not production sizing. Canonical
integration regenerated all30 ancestry states in an isolated copy and matched
all11 generated runtime contexts to the reviewed deployment. Its20focused tests
and global quality gates passed; the separate broad suite's RI31 gaps remain open.
