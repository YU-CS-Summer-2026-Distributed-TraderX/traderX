# Risk pipeline specification

Updated: 2026-09-23. Status: planning scaffold, not an implementation-ready contract.

## Scope

Reuse the existing EOD coordinator and intake to connect one portfolio to the agreed engine service; introduce parallel independent portfolios only after the single-portfolio proof.

## Requirements to specify

Define artifact staging, job/workload/attempt identities, authentication, durable result storage, coverage validation, retry ownership, recovery and bounded concurrency. Keep independent reference calculations in acceptance tooling.

Use FR-RP, NFR-RP and SC-RP identifiers when expanding this document. Preserve inherited behavior and published contracts. [Existing reference](../../../../docs/risk-integration/spec-kit-draft/README.md).

## Acceptance boundary

Before implementation, enumerate observable positive, negative and recovery cases with exact inputs and expected behavior. Completion requires source tests and generated/runtime checks appropriate to the change. No financial, distributed or live claim follows from a structural scaffold.
