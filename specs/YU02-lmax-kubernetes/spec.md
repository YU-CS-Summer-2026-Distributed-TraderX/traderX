# Feature Specification: LMAX Kubernetes

**Feature Branch**: `YU02-lmax-kubernetes`  
**Created**: 2026-06-29  
**Status**: Draft  
**Input**: Forward port of `009b-lmax-sequencer-architecture` onto `014-fdc3-intent-interoperability`

## User Stories

- As a platform engineer, I want the latest Kubernetes/C3 TraderX runtime to run with the LMAX trading path instead of the older matcher path.
- As a cloud operator, I want the LMAX state to have explicit Kubernetes startup, readiness, storage, and recovery rules before we deploy it remotely.
- As a maintainer, I want `009b` business-path semantics ported intentionally onto `014`, instead of a raw branch merge that drags along stale runtime assumptions.
- As a frontend/integration owner, I want inherited FDC3 behavior from `014` to remain intact unless the LMAX port explicitly changes it.
- As a QA engineer, I want staged validation that separates inherited platform behavior from new LMAX-on-Kubernetes behavior.

## Functional Requirements

- FR-LK01: `YU02-lmax-kubernetes` SHALL inherit `014-fdc3-intent-interoperability` as its single publish-lineage parent.
- FR-LK02: The state SHALL preserve the Kubernetes/C3/FDC3 runtime contracts already present in `014` unless a contract change is documented in this pack.
- FR-LK03: The state SHALL forward-port the `009b` LMAX hot path by replacing inherited matcher-path internals with the sequencer, single-threaded BLP, output ring, and gateway-role changes from `009b`.
- FR-LK04: `order-matcher` SHALL act as the LMAX hot-path node under Kubernetes while preserving inherited service identity unless an explicit rename is approved.
- FR-LK05: `trade-service` SHALL take the `009b` Gateway/Receptionist role under the Kubernetes runtime.
- FR-LK06: Startup SHALL include snapshot load, journal replay, warm-up replay, and readiness gating before the LMAX node is declared healthy.
- FR-LK07: The state SHALL define durable Kubernetes storage and lifecycle rules for journal, snapshot, and checkpoint assets.
- FR-LK08: The state SHALL document how inherited Sail/FDC3 assets from `014` coexist with the LMAX path and what, if anything, changes.
- FR-LK09: The first implementation phase SHALL produce a port matrix and implementation-status document before claiming runtime parity.

## Non-Functional Requirements

- NFR-LK01: The forward port must stay architecture-first and avoid generated-output-only edits for durable behavior.
- NFR-LK02: Kubernetes manifests, runtime scripts, and generated docs must describe the LMAX node as stateful and replay-gated rather than stateless request/response infrastructure.
- NFR-LK03: The state must preserve inherited `014` frontend behavior and interoperability unless a documented requirement changes it.
- NFR-LK04: The generated state must remain deterministic and publishable once implementation begins.
- NFR-LK05: Validation must separate inherited `014` platform checks from new `009b` LMAX-path checks.

## Technical Debt Register

- TD-LK01: The current scaffold intentionally inherits `014` documentation/assets while the actual LMAX-on-Kubernetes port is not yet implemented.
- TD-LK02: The initial state id intentionally drops numeric prefixing per user direction, so any tooling that assumes numeric ids must be validated as part of implementation.
- TD-LK03: The durable shape of volume claims, warm-standby behavior, and failover semantics under Kubernetes remains to be specified in follow-up work.

## Success Criteria

- SC-LK01: The repo contains a registered `YU02-lmax-kubernetes` state pack with explicit lineage to `014`.
- SC-LK02: The state pack documents the `009b -> YU02-lmax-kubernetes` forward-port strategy clearly enough to guide implementation.
- SC-LK03: Generation entrypoints exist for the state and produce a scaffolded generated artifact directory without claiming full runtime support.
- SC-LK04: Follow-up implementation can port code by architectural responsibility rather than raw branch merge.
