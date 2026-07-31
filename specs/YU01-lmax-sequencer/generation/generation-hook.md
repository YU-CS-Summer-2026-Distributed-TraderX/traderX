# Generation Hook: YU01-lmax-sequencer

- Hook script: `pipeline/generate-state-YU01-lmax-sequencer.sh`
- Feature pack: `specs/YU01-lmax-sequencer`

This state follows the patch-set overlay model and branches off `009` (sibling to the canonical `010+`
lineage).

## Patch-Set Inputs

- Parent state id: `009-order-management-matcher`
- Patch directory: `specs/YU01-lmax-sequencer/generation/patches/`
- Canonical patch file: `0001-state-overlay.patch`

## Hook Responsibilities

1. Generate parent state (`009`) output.
2. Apply all ordered patch files from this pack.
3. Run SBE codec generation (`generateSbe`) from `sbe/order-input.xml` / `sbe/order-output.xml` before
   compilation; generated encoders/decoders must exist for the build to pass.
4. Regenerate architecture docs from `system/architecture.model.json`.
5. Materialize hot-path observability assets (ring/BLP/egress/no-GC metrics wiring, Prometheus targets,
   Grafana dashboards including the allocation alert and GC-pause panels).
6. Wire the no-GC conformance gate: Gradle `noGcTest` source set/task with Epsilon JVM args, the
   banned-API static check, and `pipeline/validate-no-gc-conformance.sh`.
7. Materialize run-profile launchers (`demo` default; `perf` and `noGcTest` documented) and the
   journal/snapshot/checkpoint data directories.
8. Update the inherited state-ui-metadata frontend overlay so the UI title, About page, and status
   view render this state's id (`YU01-lmax-sequencer`) per FR-09B42, while leaving every
   other inherited frontend override (`009` Admin tab, blotters, header structure) untouched.
9. Preserve all lineage contracts: external OpenAPI/NATS/UI surfaces, `OrderBook` schema in
   `database/initialSchema.sql` (publish gate from `009` still applies), `C2` build/publish workflow,
   GHCR run bundle, and `runtime/deploy/` bundle.
10. Produce deterministic output suitable for branch publishing.

Runtime scripts:

- `scripts/start-state-YU01-lmax-sequencer-generated.sh`
- `scripts/status-state-YU01-lmax-sequencer-generated.sh`
- `scripts/stop-state-YU01-lmax-sequencer-generated.sh`
- `scripts/test-state-YU01-lmax-sequencer.sh`

## Capture / Refresh Patch

Use the patch capture workflow after implementing deltas in this state:

```bash
bash pipeline/create-state-patchset.sh YU01-lmax-sequencer 009-order-management-matcher
```
