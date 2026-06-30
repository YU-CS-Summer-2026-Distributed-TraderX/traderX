# Implementation Plan: lmax-kubernetes

## Goal

Create a new non-numeric state named `lmax-kubernetes` whose single lineage parent is
`014-fdc3-intent-interoperability`, while forward-porting the `009b` LMAX trading architecture onto the
latest Kubernetes/C3 runtime.

## Workstreams

1. State registration
   - catalog entry
   - learning doc entry
   - generation hooks and runtime harness registration
2. Port matrix
   - identify `009b` service/runtime deltas
   - classify each delta as backend, manifest, startup, storage, observability, or frontend contract
3. Runtime implementation
   - port matcher/Gateway internals
   - port stateful runtime semantics to Kubernetes
   - preserve inherited `014` FDC3 behavior
4. Validation
   - inherited `014` platform smoke
   - LMAX-specific startup/readiness/replay smoke
   - messaging and UI regression checks
