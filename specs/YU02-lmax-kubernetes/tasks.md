# Tasks: YU02-lmax-kubernetes

## Phase 1: State Scaffold

- [x] Register `YU02-lmax-kubernetes` in the state catalog
- [x] Create `specs/YU02-lmax-kubernetes`
- [x] Add generation and runtime harness entrypoints
- [x] Add learning-guide placeholder

## Phase 2: Port Matrix

- [x] Inventory all durable `009b` runtime overrides by service and responsibility
- [x] Map each `009b` delta to its `014` target location
- [x] Identify compose-only assumptions that need Kubernetes redesign
- [x] Record frontend/FDC3 invariants inherited from `014`
- [x] Resolve initial database/runtime compatibility direction for the first port slice
- [x] Confirm Postgres as the long-term runtime baseline

## Phase 3: Implementation

- [x] Seed `order-matcher` LMAX internals into `YU02-lmax-kubernetes` runtime overrides
- [x] Seed `trade-service` Gateway role into `YU02-lmax-kubernetes` runtime overrides
- [x] Reconcile copied service configs to the current `014` postgres baseline
- [x] Add replay-gated actuator readiness for `order-matcher`
- [x] Add persistent Kubernetes/Tilt storage manifests for `order-matcher`
- [ ] Reconcile `trade-processor` and supporting service boundaries after hot-path cutover
- [ ] Validate live Postgres projector behavior under the new runtime semantics
- [ ] Update generated runtime scripts and manifests for dedicated `YU02-lmax-kubernetes` lifecycle behavior

## Phase 4: Validation

- [ ] Validate inherited `014` behavior remains intact
- [ ] Validate LMAX replay-gated readiness on Kubernetes
- [ ] Add live smoke checks for runtime availability and regression boundaries
