# Engine-review hardening (S1–S6, F1–F11)

Status: integrated, 2026-10-25 to 2026-10-27. Decision for the S items: [ADR-082](../../system/adr-082-authenticated-boundary.md).
Cluster ingress authentication is ADR-079 §4; `MAX_ACTIVE_BOOKS` is ADR-079 §6.

Sources (the highest carrying layer of each file, checked for shadowing on 2026-10-27):

- YU18 `generation/runtime-overrides/order-matcher/`: `ClusterGatewayMain` (which wires the YU05
  `JwtAuthenticator`), `GatewayAccess`, `FixSessionAuth`, `FixGatewayAcceptor`, `DevSecrets`, `ClusterClientAuth`, `ClusterNodeMain`,
  `MatchingEngineClusteredService`, `MatchingEngine`, `RiskReason`, `BlpRiskState`, `ClusterRecon` and their tests.
- YU10 `generation/runtime-overrides/order-matcher/.../fix/`: `ClOrdIdLedger`, `FixOrderApplication` (F11).
- YU16 `generation/runtime-overrides/trade-service/.../auth/GatewayBearer.java` and
  `controller/TradeOrderController.java`; YU18 `kubernetes-runtime/manifests/base/trade-service-deployment.yaml`.
- `web-front-end-console/server.mjs` (root-owned console; see [console sessions](../console-sessions/README.md)).
- `specs/YU17-otc-rates/generation/kubernetes/cluster/gke/kustomization.yaml` and `secrets/*.env.example`.

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).
