# Feature Specification: Execution Algo Engine

**Feature Branch**: `YU08-execution-algo-engine`
**Created**: 2026-07-10
**Status**: Implemented
**Input**: `issues/HANDOFF-idea-execution-algo-engine.md`, parented on `YU07-historical-tick-store`

## User Stories

- As a trader, I want to submit a large order as a TWAP parent order so it is sliced into equal
  time-bucketed child orders instead of moving the book with one large print.
- As a trader, I want a VWAP parent order option so child order sizing follows a volume-weighted
  profile instead of a flat schedule.
- As a trader, I want to query a parent order's progress (buckets submitted, quantity filled,
  status) while it runs.
- As a platform engineer, I want every child order to pass through the exact same order-entry and
  risk-gateway path as a manually entered order, with no special-cased bypass.
- As a platform engineer, I want the algo engine's own schedule and fill state to survive a crash by
  replaying its own event log, not by restarting the parent order from scratch.

## Functional Requirements

- FR-AE01: The state SHALL accept a parent order (`accountId`, `security`, `side`, `quantity`,
  `algoType`, `durationSeconds`, `bucketSeconds`) via `POST /algo/orders`.
- FR-AE02: For `algoType=TWAP`, the state SHALL slice the parent quantity into equal-sized buckets
  across the requested duration, with any integer-division remainder added to the last bucket.
- FR-AE03: For `algoType=VWAP`, the state SHALL slice the parent quantity across buckets weighted by
  a volume profile obtained from a pluggable `VolumeProfileSource`.
- FR-AE04: Every child order SHALL be submitted to `order-matcher`'s `POST /orders` endpoint with a
  positive `limitPrice` derived from the security's current price, unchanged from every other
  caller of that endpoint.
- FR-AE05: The state SHALL track each child order's fill progress by subscribing to the existing
  `/accounts/*/orders` broadcast subject and correlating incoming events by `orderId`.
- FR-AE06: The state SHALL expose parent-order progress via `GET /algo/orders/{parentOrderId}` and
  `GET /algo/orders`.
- FR-AE07: The state SHALL append every parent-order state transition (creation, child submission,
  fill observation, completion) to a durable JetStream stream before applying it to in-memory state.
- FR-AE08: On startup, the state SHALL rebuild every parent order's in-memory state by replaying its
  own JetStream stream, without any synchronous fetch to another service.
- FR-AE09: When the configured `DuckDbVolumeProfileSource` returns zero matching rows for a
  security, the state SHALL fall back to `SyntheticVolumeProfileSource`'s weights rather than fail
  or block the parent order.

## Non-Functional Requirements

- NFR-AE01: The state SHALL run as an independent process from `order-matcher`; no change is made
  to the BLP's Disruptor rings, journal, or matching loop.
- NFR-AE02: Child order submission SHALL introduce no new order-admission code path in
  `order-matcher` — every child is indistinguishable, at the risk gateway and BLP, from a manually
  entered order carrying the same fields.
- NFR-AE03: A restart of `execution-algo-engine` SHALL resume every in-flight parent order's
  schedule and observed fills from its own JetStream stream, with no operator intervention.
- NFR-AE04: The state SHALL reuse the project's existing NATS/JetStream client
  (`io.nats:jnats:2.20.5`) and Spring Boot service shape rather than introducing a new messaging
  library or service framework.

## Success Criteria

- SC-AE01: Generation hook exists and is runnable (`pipeline/generate-state-YU08-execution-algo-engine.sh`).
- SC-AE02: State smoke test path is defined (`scripts/test-state-YU08-execution-algo-engine.sh`).
- SC-AE03: Smoke checks validate that the shared `kustomization.yaml` retains every ancestor state's
  resource entries alongside this state's two additions.
- SC-AE04: A TWAP parent order, run end to end against a local kind cluster, produces child orders
  observed as accepted by `order-matcher` and progress visible via `GET /algo/orders/{id}`.
- SC-AE05: A VWAP parent order produces bucket quantities that differ from an equal split,
  confirming the volume-profile weighting is applied, and falls back to the synthetic profile when
  no historical data matches.
- SC-AE06: Killing and restarting `execution-algo-engine` mid-run resumes a parent order's schedule
  from its JetStream stream without re-submitting already-submitted buckets.
