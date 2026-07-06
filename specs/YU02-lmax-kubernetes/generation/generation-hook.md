# Generation Hook: YU02-lmax-kubernetes

- Hook script: `pipeline/generate-state-YU02-lmax-kubernetes.sh`
- Render script: `pipeline/render-state-YU02-lmax-kubernetes.sh`
- Feature pack: `specs/YU02-lmax-kubernetes`

This state currently follows a scaffold-first render model: generate the `014` parent state, then render a
state-local scaffold that captures the forward-port plan and generated-state identity without claiming full
runtime implementation.

## Render Inputs

- Parent state id: `014-fdc3-intent-interoperability`
- Upstream runtime source: `generated/code/target-generated/fdc3-intent-interoperability`
- State artifact target: `generated/code/target-generated/YU02-lmax-kubernetes`

## Hook Responsibilities

1. Generate parent state `014-fdc3-intent-interoperability` into target-generated workspace.
2. Render a state-local scaffold that records:
   - generated-state README
   - implementation status
   - forward-port matrix
   - copied spec-source references
   - intended upstream lineage
3. Reserve state-local generation areas for future runtime overrides and manifests.
4. Keep inherited `014` contracts as the default until the LMAX port explicitly changes them.
5. Emit deterministic output suitable for continued forward-port work.
