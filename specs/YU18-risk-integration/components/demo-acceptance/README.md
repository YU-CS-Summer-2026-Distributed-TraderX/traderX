# Demo acceptance

Status: review needed, 2026-09-24 (review corrections R1–R3 applied; rerun on the integrated F1
fix). Not merged, pushed or deployed. Owner: Claude RI-07 lane, worktree `traderX-demo-acceptance`,
branch `claude/demo-acceptance`, based on integration `9931b3e5`. Backlog: [RI-07](../../../../issues/risk-integration/07-acceptance-and-demo.md).

[Spec](spec.md) · [Plan](plan.md) · [Tasks](tasks.md) · [Command guide](../../../../docs/risk-integration/local-demo-guide.md)

This component owns full-rig readiness, the isolated startup probe, the live order-type
exercise, the local trade-to-risk demonstration wrapper and read-only console status
presentation. It changes no engine, gateway, read-model, schema or risk-coordinator behaviour.

| Source | Layer |
|---|---|
| `scripts/ri07/{startup-probe.sh,rig-ready.sh,order-types-live.py,risk-flow-local.sh}` | repository root scripts (not rendered) |
| `scripts/yu15/{start-cluster-kind,start-frontend-kind,start-observability-kind,stop-cluster-kind,bring-up-gke}.sh` | repository root rig scripts, the only copies |
| `web-front-end-console/src/app/{app.ts,app.html,blotter-panel.ts}` | repository root console (the state pipeline renders no copy) |
