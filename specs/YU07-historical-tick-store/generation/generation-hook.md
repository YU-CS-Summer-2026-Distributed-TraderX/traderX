# Generation Hook: YU07-historical-tick-store

- Hook script: `pipeline/generate-state-YU07-historical-tick-store.sh`
- Render script: `pipeline/render-state-YU07-historical-tick-store.sh`
- Feature pack: `specs/YU07-historical-tick-store`
- Parent state: `YU06-eod-price-production`
- Overlay model: generate parent (which renders onto `YU05-post-trade-compliance` →
  `YU04-durable-control-feeds` → `YU03-in-memory-risk-gateway` → `YU02-lmax-kubernetes` →
  `014-fdc3-intent-interoperability`), then overlay this state's `generation/runtime-overrides/`
  onto the shared component tree — the same per-file overlay mechanism every prior state in this
  lineage uses.

## Hook Responsibilities

1. Delegate direct invocation via `pipeline/generate-state.sh YU07-historical-tick-store`.
2. Generate parent `YU06-eod-price-production` from a clean target root.
3. Overlay the new `tick-store` component in full (`capture.py`, `ingest_taq_quotes.py`, `gcs.py`,
   `duckdb_query_examples.sql`, `requirements.txt`, `Dockerfile`, `tests/`) — a brand-new directory
   in the shared component tree, not an override of any ancestor's file.
4. Overlay the k8s manifests: `tick-store-deployment.yaml` (no PVC — writes straight to GCS,
   research.md Decision 6) and the extended `kustomization.yaml` (starts from YU06's copy, appends
   the one new resource entry).
5. Materialize the state scaffold + spec-source copies under
   `generated/code/target-generated/YU07-historical-tick-store`.
6. Inherit everything else (runtime harness, other manifests, GKE deploy scripts, observability
   stack, every existing service) unchanged from `YU06-eod-price-production`.

## Shared-file override caution (see research.md)

Only one file is overridden by an ancestor **and** by YU07:

- `kubernetes-runtime/manifests/base/kustomization.yaml` — overridden by every ancestor state
  through YU06 (each appends its own resource entries). YU07's copy starts from YU06's current
  version and appends `tick-store-deployment.yaml`, never replacing it.

`tick-store`'s own files (`capture.py`, `ingest_taq_quotes.py`, `gcs.py`, etc.) have no ancestor
version — this is a new component, so there is nothing to clobber there.

Verify empirically after generating: regenerate, then grep the generated `kustomization.yaml` for
an ancestor marker (`eod-session-close-cronjob.yaml`, `order-matcher-lmax-data-pvc.yaml`) **and**
the YU07 marker (`tick-store-deployment.yaml`).

## Build / verify

```bash
bash pipeline/generate-state.sh YU07-historical-tick-store
cd generated/code/target-generated/tick-store && python3 -m pytest tests/ -v
```

Deploy uses the inherited `YU06`/`YU02` GKE scripts/CI (the state adds only the `tick-store`
component's image content, its Deployment manifest, and one `kustomization.yaml` entry). The
`tick-store-gcs-hmac` Secret is created out-of-band (quickstart.md) — generation never touches it.
