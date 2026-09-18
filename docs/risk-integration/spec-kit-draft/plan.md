# Delivery plan and decisions

1. Review the boundary and decide D-01 through D-05 with Alex.
2. Alex maps current versus canonical engine paths; agree the consolidation plan before deleting code.
3. Fix request/retry semantics and establish the initial contract tests as a gate.
4. Refactor through the canonical engine service, preserving conventions and independent numerical checks.
5. Bind TraderX to the agreed schemas using a new acceptance profile and explicit transport adapter.
6. Prove process-restart behavior locally; consider remote deployment only as separately authorized work.

| Decision | Proposed choice | State |
|---|---|---|
| D-01 Shared spec authority | One engine-owned or neutral pack; TraderX pins its revision and owns its producer profile | Await Alex/Yaakov agreement |
| D-02 Initial options | USD-only reporting; reject unsupported currency/unknown calculations; document selection and reconciliation outputs | Await agreement |
| D-03 Active-attempt recovery | Explicit interrupted/recoverable status or boot/lease protocol; terminal persistence alone is insufficient | Await design |
| D-04 Initial transport | Local single-worker server-path submission for acceptance; remote staging/auth defined separately | Await agreement |
| D-05 Canonical engine owner map | Alex identifies authoritative service/models and required capability changes | Audit required |

Do not use this plan as authorization to refactor Alex's checkout, push code, bring up GKE, or change accepted engine pins.
