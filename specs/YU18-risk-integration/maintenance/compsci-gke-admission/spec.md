# CompSci admission contract

Owner: CompSci setup candidate, `codex/compsci-gke`. Implementation is in
`scripts/gcp-compsci/`; all runtime generation remains state-parent-owned.
Requirement IDs CGAD-FR01 through CGAD-FR08 correspond in order to the eight
requirements below; their acceptance controls are in `admission-controls.py` and
the measured HMAC proof record.

- Explicit target is `project-compsci-484414/us-east1-b/traderx-demo`, namespace
  `traderx`; ambient context and inferred project/state are refused.
- Exactly 23 manifest slots cover 12 private images and 8 upstream images. Empty,
  missing, duplicate, additional, mutable or mismatched image populations fail.
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
