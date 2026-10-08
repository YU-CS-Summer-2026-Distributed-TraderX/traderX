# Intended image admission

Status: integrated locally, 2026-10-07; see the evidence and limits below.

The GKE startup entrypoint renders an explicitly selected state ancestor's manifests and checks every container against an independent immutable artifact plan. It defaults to inspection. Missing producer metadata, a mutable tag, omitted init container, wrong component owner or unsupported artifact refuses before apply. An inherited component is valid when its intended build state, owner and generated content are explicitly declared and verified. A newer deployment can deliberately select an ancestor build using a separately mapped generation context; the newest source layer is not automatically its intended artifact.

The proof runner checks its selected member/consumer image reference on every kind node before preparation, even when the existing baseline needs no rebuild. It reads structured Kubernetes node and CRI image inventories. Content IDs and running pods do not establish repository-reference resolution. It never loads, deletes or retags node images.

Source: `scripts/yu15/image-admission.py`, `start-cluster-gke.sh`, `run-proofs.sh`. The accepted RI18 fingerprint/payload primitives and RI17 cleanup supervisor are dependencies. Their contracts remain independent: image content admission does not qualify a retained writer/reader boundary or authorize a reset.

Offline entrypoint, with an installed YAML parser selected explicitly:

```bash
IMAGE_ADMISSION_PYTHON=/path/to/python-with-PyYAML bash scripts/yu15/start-cluster-gke.sh \
  --context gke_example-project_us-east1-b_example-cluster \
  --project example-project --location us-east1-b --cluster example-cluster --namespace traderx \
  --state YU18-risk-integration --manifest-pack YU17-otc-rates \
  --expected /private/path/intended.json --artifacts /private/path/inspected.json
```

Only `kubectl kustomize` is called by this inspection, after its structured local input closure is bounded. Remote resource paths, external generators/transformers and Helm refuse before rendering. It uses local source and supplied offline inspection/review evidence; it does not contact a cluster or registry. JSON-rendered input is also supported by the helper's `admit` command without PyYAML. The tool does not install dependencies or create missing attestations. Current checked-in GKE refs are mutable and require a deliberate reviewed manifest/artifact pinning decision before admission can succeed; no production images were created or repinned for RI20.

`--apply` is a separate operator action after admission, using exactly the validated render and schema snapshots. Startup prints the admission report and removes its temporary snapshots on exit; the helper's direct `render --output-dir` command can preserve offline snapshots for review. It preserves schema-before-database ordering. Invocation requires separately authorized deployment and retained-state planning; admission itself grants neither. Default context environment variables from the old GKE entrypoint no longer select a project.

```bash
python3 scripts/tests/image-admission/test_admission.py
python3 scripts/tests/proof-cleanup/test_cleanup.py
python3 scripts/tests/stp-image-provenance/test_provenance.py
```

The proof runner's `--check-images` mode performs only node-reference reads. Its kind adapter is deliberately conservative: every returned node is checked, including a control-plane node. Non-kind contexts refuse until an appropriate separately authorized inventory adapter exists. The deployment helper's offline artifact inspection is not a GKE node-availability check.

See [spec.md](spec.md) for the required artifact/review fields and [tasks.md](tasks.md) for evidence boundaries.

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
