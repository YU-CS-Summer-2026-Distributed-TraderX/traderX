# Risk Integration — state plan

Updated: 2026-09-23. Parent: YU17-otc-rates. Integration branch: traderX-risk-integration.

1. Maintain shared architecture, contracts, quickstart, data model and generation in the state parent.
2. Preserve the [EOD plan](components/eod-integration/plan.md) and its existing implementation.
3. Specify and deliver [order types](components/order-types/plan.md) within YU18.
4. Package the engine in its own repository using the [risk-service plan](components/risk-service/plan.md).
5. Connect one portfolio before introducing [parallel risk workers](components/risk-pipeline/plan.md).

The coordinator reviews cross-component contracts and merges accepted changes into traderX-risk-integration. Temporary task branches/worktrees may isolate simultaneous writers; they are not new state branches. Lane dispatch remains pending user confirmation. No deployment is implied.
