# Account admission answers its committed decision (RI33)

Status: implemented and locally tested on branch `admission-200`, 2026-11-08; not merged, not deployed.

`POST /risk/control/account` and `/seed` answered 200 `{"applied":true}` once the ingress offer was accepted, and
took a malformed body as account 0. They now answer the members' committed decision. Root cause and evidence:
[RI33](../../../../issues/risk-integration/33-account-admission-answered-200-not-applied.md).

Source: `../../generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/ClusterGatewayMain.java`
(handler, `admitAccount`, egress kind 108), `.../cluster/MatchingEngineClusteredService.java` (the ack),
`.../risk/BlpRiskState.java` (`knowsAccount`). Test: `.../src/test/java/finos/traderx/ordermatcher/cluster/AccountAdmissionAckTest.java`.

[Specification](spec.md), [plan](plan.md), [tasks](tasks.md).
