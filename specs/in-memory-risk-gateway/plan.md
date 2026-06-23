# Implementation Plan: in-memory-risk-gateway

## Scope

- Optional architecture child of `009b-lmax-sequencer-architecture`.
- Replace producer-path account/reference/price/risk remote validation with local versioned replicas.
- Add authoritative sequenced BLP validation, exposure reservation, idempotency, and decision events.
- Preserve `009b` input/output disruptor topology, matching policy, accepted NATS/UI contracts, and
  async relational query model.
- Keep risk administration on the control plane; no synchronous remote risk engine.

## Milestones

### P0 — Contracts and baseline harness

- Lock submitted-command, decision/reason, control delta, snapshot/watermark, and configuration schemas.
- Capture current `009b` admission latency, remote-call behavior, no-GC, and output benchmark baselines.
- Add fixtures for accounts, entitlements, securities, prices, policies, restrictions, and kill switches.

### P1 — Authoritative source feeds and replica substrate

- Add transactional/versioned change publication and watermarked snapshots to account/reference owners.
- Add minimal authenticated risk administration for limits, restrictions, and kill switches.
- Implement retained control streams and reusable subscribe-buffer-snapshot-catch-up replica library.
- Add readiness, gaps, re-bootstrap, checksum/schema, staleness, and capacity telemetry/tests.

### P2 — Gateway local screening

- Install replicas in trade-service and order-matcher admission surfaces.
- Remove account/reference remote validation and client-driven symbol registration.
- Implement shared stable screening rules, source-version diagnostics, and fail-closed readiness.
- Normalize required `clientOrderId` and trusted principal context into bounded primitive command fields.

### P3 — Sequenced authoritative BLP risk

- Extend input/control codecs and journal compatibility.
- Apply complete decision-relevant controls in global sequence order.
- Implement stable BLP check order, exact exposure calculation, reservation lifecycle, idempotency, and
  accepted/rejected decision events.
- Extend snapshot/replay and warm-standby state.

### P4 — External decision semantics and failure modes

- Make synchronous APIs await correlated BLP decisions and return stable rejection bodies/statuses.
- Implement explicit degraded-mode/risk-reducing behavior, mismatch audit, capacity rejection, and
  policy treatment of resting orders.
- Preserve accepted output contracts and ensure rejected commands produce no accepted business output.

### P5 — Proof and publication

- Extend no-GC/banned-API gates and Gateway/BLP latency benchmarks.
- Add multi-Gateway overshoot, bootstrap race, gap/epoch, replay, idempotency, reservation, and failure
  tests.
- Run inherited `009b` smoke, output latency, and output topology regressions.
- Provision dashboards/alerts, complete generated-state lifecycle scripts, and publish only after all
  exit criteria pass.

## Deliverables

1. Requirement and no-GC deltas under `requirements/`.
2. External/internal/snapshot/control contracts in `contracts/contract-delta.md`.
3. Research, data model, architecture, topology, subject map, and ADRs.
4. Generated control/command codecs and bounded primitive state structures.
5. Source outbox/snapshot and durable-stream configuration.
6. Gateway replica/screening and BLP decision/reservation/idempotency implementation.
7. State-native generation, lifecycle, smoke, and benchmark entrypoints.
8. Prometheus/Grafana risk and replica observability.
9. Deterministic replay and compatibility evidence.

## Implementation Order Constraints

- Do not remove remote validation until local replica readiness and parity fixtures exist.
- Do not treat Gateway checks as authoritative aggregate risk.
- Do not add BLP control logic until event schemas and replay rules are locked.
- Do not change synchronous success semantics until correlated decision handling is tested.
- Do not tune/redesign the output ring in this state.
- Do not claim compliance based on technical control tests alone.

## Exit Criteria

- All FR/NFR/SC items are traced to implementation and tests.
- Gap-free bootstrap, multi-Gateway aggregate correctness, deterministic replay, and reservation
  invariants pass.
- Admission has no remote validation lookup and no BLP external call.
- Gateway/BLP hot-path allocation is zero after warm-up.
- Performance profile meets NFR-IMRG01 with full percentile reports.
- Accepted `009b` REST/WS/NATS/UI journeys and output benchmarks pass without material regression.
- Catalog/docs/generation/lifecycle metadata consistently use `in-memory-risk-gateway`.
- State is ready for generated snapshot publication; implementation status is updated with evidence.

