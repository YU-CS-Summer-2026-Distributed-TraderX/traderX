# Console runtime packaging

Updated: 2026-10-08. Status: candidate implemented and tested in an owned Docker container; review and integration pending. Owner: Codex console packaging lane.

The console image omitted `desk-orders.mjs`, so its default command exited before serving requests. The root `web-front-end-console/Dockerfile` now includes that module in its explicit runtime copy list. Server, Desk, auth and engine behavior are unchanged.

See [spec](spec.md), [plan](plan.md), [tasks](tasks.md), and the [bounded task](../../../../issues/risk-integration/tasks/lr05-console-packaging.md). The root Dockerfile remains the implementation owner; this pack adds no generation overrides.
