# Risk service packaging specification

Updated: 2026-09-23. Status: planning scaffold, not an implementation-ready contract.

## Scope

A local CPU container for the existing JAX Risk Engine, built outside TraderX. This component owns the TraderX-facing service boundary, not a second engine implementation.

## Requirements to specify

Reference the external pack at /Users/yaakov/dev/jax_risk_engine/risk-engine-container-spec. Qualify host/container parity, both APIs, input/result mounts, bounded resources and graceful shutdown. Preserve known API limitations rather than disguising them.

Use FR-RS, NFR-RS and SC-RS identifiers when expanding this document. Preserve inherited behavior and published contracts. [Existing reference](../../../../docs/risk-integration/spec-kit-draft/README.md).

## Acceptance boundary

Before implementation, enumerate observable positive, negative and recovery cases with exact inputs and expected behavior. Completion requires source tests and generated/runtime checks appropriate to the change. No financial, distributed or live claim follows from a structural scaffold.

## Local CI acceptance boundary (RI-08, 2026-09-24)

- FR-RS01: Consumer CI shall verify installed EOD readers and the existing Node status bridge
  using synthetic local input, preserving false risk-readiness flags.
- FR-RS02: External packaging checks shall accept an explicit checkout and full reviewed SHA,
  refuse revision/dirty-source mismatches, and use the external owner's builder.
- NFR-RS01: Smoke containers shall be bounded, isolated and removed without touching retained rigs.
- SC-RS01: Current console/status build, 152 installed EOD tests and one VERIFIED mock status
  job pass in a read-only network-disabled container.
- SC-RS02: Linux/amd64 image provenance, all-layer inspection, readiness, result-schema hash
  and missing-bundle HTTP 422 pass; a wrong checkout revision fails before building.

These packaging checks do not specify or implement a financial engine. See RI-08 for measured
revision, commands and external hosted-wiring dependency. Full API contracts remain incomplete.
