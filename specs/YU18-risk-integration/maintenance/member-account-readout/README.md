# Member account readout

Status: integrated locally, 2026-10-07; see the evidence and limits below.

The admin member endpoint GET /risk/control/accounts observes known account IDs and enabled flags in this member's engine risk table. Directory existence and gateway offered controls do not establish engine presence. Disabled accounts remain present.

Source: ../../generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/ClusterNodeMain.java. Tests: corresponding src/test/java/finos/traderx/ordermatcher/cluster/MemberAccountReadoutTest.java.

[Specification](spec.md), [plan](plan.md), [tasks](tasks.md). Uses existing YU18 risk.accountTuples and inherited JwtAuthenticator. No core, wire, snapshot, gateway, SQL or UI change. Shared generation and state indexes remain state-owned.

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
