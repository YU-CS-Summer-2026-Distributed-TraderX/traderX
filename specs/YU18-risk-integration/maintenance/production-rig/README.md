# Production rig

Status: scripts, renderer and gateway drain on `traderx-risk-integration` 2026-10-29; not yet run on production
(rollout 5a, which waits on the restore-gate backport). Decision:
[ADR-096](../../system/adr-096-production-rig-lifecycle.md).

Sources:
- `scripts/gcp-compsci/rig/rig.sh`: `pools`, `up`, `down`, `status`, with `--dry-run`. Inputs: `GATEWAYS` (1..18)
  and `AUTOSCALE` (on/off).
- `scripts/gcp-compsci/rig/gateways.sh N`: the gateway count on a running rig.
- `scripts/gcp-compsci/rig/`:
  - `node-{engine,gateway,nats}.yaml`: node system configs;
  - `gateway-ilb.yaml`: the internal LB;
  - `gateway-hpa.yaml`: the HPA;
  - `fix-smoke.mjs`: the smoke order.
- `scripts/gcp-compsci/prepare.py`: `RIG_POOLS`, `MEMBER_JVM`, `MEMBER_MAX_ACTIVE_BOOKS`, `GATEWAY_DRAIN_DELAY_MS`,
  and their `validate()` rules.
- YU18 `ClusterGatewayMain.drainAndStop`, `FixGatewayAcceptor.logoutAll`.
- Tests:
  - `rig/test_rig.py`;
  - `test_prepare.py` (`test_rig_tiers…`, `test_gateways_are_one_per_node…`,
    `test_members_run_generational_zgc_with_the_book_cap_pinned`);
  - `FixSessionPersistenceIT.aDrainingGatewayLogsOutAndStillStoresTheAnswerInFlight`.

Runbook: [CompSci GKE, production rig](../../../../docs/deployment/compsci-gke.md#production-rig-adr-096).

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).
