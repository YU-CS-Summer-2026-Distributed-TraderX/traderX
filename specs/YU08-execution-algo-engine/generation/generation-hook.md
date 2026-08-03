# Generation Hook: YU08-execution-algo-engine

- Hook script: `pipeline/generate-state-YU08-execution-algo-engine.sh`
- Render script: `pipeline/render-state-YU08-execution-algo-engine.sh`
- Feature pack: `specs/YU08-execution-algo-engine`
- Parent state: `YU07-historical-tick-store`
- Overlay model: generate parent (which renders onto `YU06-eod-price-production` →
  `YU05-post-trade-compliance` → `YU04-durable-control-feeds` → `YU03-in-memory-risk-gateway` →
  `YU02-lmax-kubernetes` → `014-fdc3-intent-interoperability`), then overlay this state's
  `generation/runtime-overrides/` onto the shared component tree — the same per-file overlay
  mechanism every prior state in this lineage uses.

## Hook Responsibilities

1. Delegate direct invocation via `pipeline/generate-state.sh YU08-execution-algo-engine`.
2. Generate parent `YU07-historical-tick-store` from a clean target root.
3. Overlay the new `execution-algo-engine` component in full (Java source, `build.gradle`,
   `Dockerfile`, tests) — a brand-new directory in the shared component tree, not an override of
   any ancestor's file.
4. Overlay the k8s manifests: `execution-algo-engine-deployment.yaml` +
   `execution-algo-engine-service.yaml` (no PVC — state lives in JetStream) and the extended
   `kustomization.yaml` (starts from YU07's copy, appends the two new resource entries).
5. Materialize the state scaffold + spec-source copies under
   `generated/code/target-generated/YU08-execution-algo-engine`.
6. Inherit everything else (runtime harness, other manifests, GKE deploy scripts, observability
   stack, every existing service, `tick-store`) unchanged from `YU07-historical-tick-store`.

## Shared-file override caution (see research.md)

Only one file is overridden by an ancestor **and** by YU08:

- `kubernetes-runtime/manifests/base/kustomization.yaml` — overridden by every ancestor state
  through YU07 (each appends its own resource entries). YU08's copy starts from YU07's current
  version and appends `execution-algo-engine-deployment.yaml` +
  `execution-algo-engine-service.yaml`, never replacing it.

`execution-algo-engine`'s own files have no ancestor version — this is a new component, so there is
nothing to clobber there.

Verify empirically after generating: regenerate, then grep the generated `kustomization.yaml` for an
ancestor marker (`eod-session-close-cronjob.yaml`, `tick-store-deployment.yaml`) **and** the YU08
marker (`execution-algo-engine-deployment.yaml`).

## Build / verify

```bash
bash pipeline/generate-state.sh YU08-execution-algo-engine
cd generated/code/target-generated/execution-algo-engine && ./gradlew test
```

Deploy uses the inherited `YU07`/`YU02` GKE scripts/CI (the state adds only the
`execution-algo-engine` component's image content, its Deployment/Service manifests, and one
`kustomization.yaml` entry pair). No out-of-band credential is required for this state (unlike
`tick-store`'s GCS HMAC Secret) unless `ALGO_VOLUME_PROFILE_SOURCE=duckdb` is enabled, in which case
it reuses `tick-store`'s existing `tick-store-gcs-hmac` Secret read-only.
