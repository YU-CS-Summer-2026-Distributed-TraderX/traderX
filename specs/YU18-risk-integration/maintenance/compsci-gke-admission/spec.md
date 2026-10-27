# CompSci admission contract

Owner: CompSci setup candidate, `codex/compsci-gke`. Implementation is in
`scripts/gcp-compsci/`; all runtime generation remains state-parent-owned.
Requirement IDs CGAD-FR01 through CGAD-FR08 correspond in order to the eight
requirements below; their acceptance controls are in `admission-controls.py` and
the measured HMAC proof record.

- Explicit target is `project-compsci-484414/us-east1-b/traderx-demo`, namespace
  `traderx`; ambient context and inferred project/state are refused.
- Exactly 24 manifest slots cover 12 private images and 9 upstream images (promtail
  is the ninth, c8c8ac31). The counts are defined once in `admit.py`, and `test_prepare`
  checks them against the render. Empty, missing, duplicate, additional, mutable or
  mismatched image populations fail.
- Current full source identity and complete YU18 ancestry bind independently
  derived contexts. Current/generated context changes invalidate admission.
- Private images retain their exact original source and derivation labels; each
  packaged entry is compared with independent compilation or its declared source
  copy recipe. Root console source is explicitly bound. Empty payloads fail.
- Registry index/manifest, unique amd64 child, configuration, layers, expected
  image ID and actual executable architecture evidence must agree.
- Upstream images are admitted by reviewed immutable OCI identity/platform/layer
  coverage. Their upstream source is not claimed to have been rebuilt locally.
- Evidence is hash-bound, relative and bounded; duplicate input keys fail. No
  image loading, retagging, registry upload, credential access or apply is performed.
- HMAC proof uses the actual pinned Java sink, nonempty synthetic objects, exact
  read-back, conditional and unconditional overwrite refusal and a wrong-secret
  control. Uncertain writes require read-only reconciliation before continuation.

An admitted pack is a cold-demo artifact verdict. Startup/load behavior, supported
retained persistence, financial results and direct human rollout approval remain
separate.

## Rendered alongside the admitted pack

- The pack includes the cluster-scoped `order-matcher-archive` StorageClass and promtail
  ([GKE member runtime](../gke-member-runtime/spec.md)). `TRADERX_NAMESPACE` renders a side
  namespace: the member and ingress FQDNs follow it, and its risk-extract writes the local file
  sink, never the demo's write-once bucket.
- The hosted Desk (`web-front-end-desk`, PVC `desk-users`, `desk-https` Service, BackendConfig and
  ManagedCertificate) is derived from the admitted console Deployment by
  `render-desk-deployment.py`, from an exact private Desk digest built with `Dockerfile.desk`.
  HTTPS frontend access is rendered by `render-frontend-access.py` from `https-frontend.json`.
  Neither is part of the 24-slot admitted population.
