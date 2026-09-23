# Risk service packaging specification

Updated: 2026-09-23. Status: planning scaffold, not an implementation-ready contract.

## Scope

A local CPU container for the existing JAX Risk Engine, built outside TraderX. This component owns the TraderX-facing service boundary, not a second engine implementation.

## Requirements to specify

Reference the external pack at /Users/yaakov/dev/jax_risk_engine/risk-engine-container-spec. Qualify host/container parity, both APIs, input/result mounts, bounded resources and graceful shutdown. Preserve known API limitations rather than disguising them.

Use FR-RS, NFR-RS and SC-RS identifiers when expanding this document. Preserve inherited behavior and published contracts. [Existing reference](../../../../docs/risk-integration/spec-kit-draft/README.md).

## Acceptance boundary

Before implementation, enumerate observable positive, negative and recovery cases with exact inputs and expected behavior. Completion requires source tests and generated/runtime checks appropriate to the change. No financial, distributed or live claim follows from a structural scaffold.
