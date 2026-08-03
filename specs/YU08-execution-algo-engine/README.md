# Feature Pack YU08: Execution Algo Engine

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: Implemented
Track: `architecture`
Lineage role: `optional`
Previous state: `YU07-historical-tick-store`

This pack defines an execution algo engine — a new `execution-algo-engine` component that slices a
parent order into TWAP (equal time-bucketed) or VWAP (volume-weighted) child orders and submits each
through `order-matcher`'s existing order-entry and risk-gateway path — on top of the
`YU07-historical-tick-store` baseline.

Primary intent:

- accept a parent order (account, security, side, quantity, algo type, duration) and slice it into
  child orders on a schedule, with zero change to `order-matcher`'s admission path,
- submit every child order through the same `POST /orders` endpoint the web front end already uses,
  so the risk gateway and BLP treat it identically to a manually entered order,
- event-source the algo engine's own parent-order schedule and observed fills so a crash resumes
  from its own log instead of restarting the schedule.

Core artifacts:

- `spec.md`
- `requirements/functional-delta.md`
- `requirements/nonfunctional-delta.md`
- `research.md`
- `data-model.md`
- `quickstart.md`
- `contracts/contract-delta.md`
- `system/architecture.model.json`
- `system/architecture.md`
- `system/runtime-topology.md`
- `system/messaging-subject-map.md`
- `system/adr-030-warm-path-algo-engine-with-jetstream-event-sourcing.md`
- `system/adr-031-pluggable-volume-profile-source.md`
- `generation/generation-hook.md`
- `generation/implementation-status.md`

Target runtime behavior:

- `execution-algo-engine` (new component) runs a scheduler loop submitting due child-order buckets
  to `order-matcher`, and a NATS subscriber tracking their fills.
- Everything else (deploy/runtime harness, observability stack, every existing service) is inherited
  unchanged from `YU07-historical-tick-store`.
