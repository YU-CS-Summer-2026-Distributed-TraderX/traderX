# Research: YU02-lmax-kubernetes

## Starting Assumptions

- `014-fdc3-intent-interoperability` is the latest Kubernetes/C3 convergence-line runtime we should build on.
- `009b-lmax-sequencer-architecture` is still the authoritative source for LMAX hot-path behavior.
- `YU02-lmax-kubernetes` should be implemented as a new child state, not as a direct merge between unrelated branch histories.

## Open Research Questions

1. Which `009b` runtime overrides are still authoritative versus superseded in `YU02-lmax-kubernetes`?
2. Which Kubernetes manifests from the `014` line need stateful LMAX-specific changes?
3. Which runtime assumptions in `009b` are compose-era only and must be redesigned for Kubernetes?
4. Does any inherited `014` FDC3/frontend logic depend on old matcher behavior in a way that must be revisited?
