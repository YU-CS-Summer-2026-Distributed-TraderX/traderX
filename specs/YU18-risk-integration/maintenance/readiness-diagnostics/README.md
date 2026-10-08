# Member readiness diagnostics

Status: integrated locally, 2026-10-07; see the evidence and limits below.

The member `/ready` response distinguishes configured peer slots from valid observed applied sequences. It reports unknown comparisons when the local engine or peer readings are unavailable. Routing readiness retains its existing tolerance and no-observation behavior. Sequence catch-up concerns the maximum observed sequence only; it establishes no book equality, quorum health, leader eligibility or retained recovery guarantee.

See [spec](spec.md), [plan](plan.md), [tasks](tasks.md), the [shared quickstart](../../quickstart.md) and [component rules](../../../../docs/spec-kit/state-components.md). Generation inputs remain in the state parent. RI22 risk-capacity gauges are separately reviewed; a disposable compatibility composition passes both suites, while this commit remains independent.

Authoritative source: `specs/YU18-risk-integration/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/ClusterNodeMain.java`. Executable acceptance: the adjacent test override `ReadinessDiagnosticsTest.java`. YU18 composes after the historical YU12/YU13/YU15/YU17 full-file node overrides.

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
