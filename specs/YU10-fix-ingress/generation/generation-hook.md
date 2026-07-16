# Generation Hook: YU10-fix-ingress

- Hook script: `pipeline/generate-state-YU10-fix-ingress.sh`
- Render script: `pipeline/render-state-YU10-fix-ingress.sh`
- Feature pack: `specs/YU10-fix-ingress`
- Parent state: `YU09-ops-hardening`
- Overlay model: generate parent (which renders onto `YU08-execution-algo-engine` →
  `YU07-historical-tick-store` → `YU06-eod-price-production` → `YU05-post-trade-compliance` →
  `YU04-durable-control-feeds` → `YU03-in-memory-risk-gateway` → `YU02-lmax-kubernetes` →
  `014-fdc3-intent-interoperability`), then overlay this state's `generation/runtime-overrides/`
  onto the shared component tree — the same per-file overlay mechanism every prior state in this
  lineage uses.

## Hook Responsibilities

1. Delegate direct invocation via `pipeline/generate-state.sh YU10-fix-ingress`.
2. Generate parent `YU09-ops-hardening` from a clean target root.
3. Overlay `generation/runtime-overrides/` (order-matcher `fix/` package + build.gradle,
   kubernetes-runtime order-matcher deployment/service) last-wins onto the component tree.
4. Copy the spec pack into `generated/code/target-generated/YU10-fix-ingress/spec-source/`.

## Shared-override surface

This state overrides files that ancestors also override; each copy was diffed against every
ancestor's version and carries the ancestors' changes forward:

- `order-matcher/build.gradle` (YU02 owns the base; YU09 shadows it with the allocation-gate
  tasks) — the YU10 copy is YU09's content plus the QuickFIX/J dependency.
- `order-matcher/src/main/java/.../lmax/LmaxEngine.java` (YU02/YU03/YU04/YU05/YU09 shadow it) —
  the YU10 copy is YU09's content plus the FIX report-handler registration.
- `kubernetes-runtime/manifests/base/order-matcher-deployment.yaml` (YU02/YU09) — YU09's content
  plus the FIX port, `FIX_*` env, and data directory.

Verification after any regeneration: grep the generated output for one marker from each ancestor
that shares these files (allocation-gate tasks from YU09's build.gradle; the YU09 probe block in
the deployment; the YU03 risk wiring in LmaxEngine) plus this state's markers (`quickfixj`,
`18130`, `FixExecutionReportHandler`).
