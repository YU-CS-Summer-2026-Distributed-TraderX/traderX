# Smoke Tests: in-memory-risk-gateway

- Planned primary script: `scripts/test-state-in-memory-risk-gateway.sh`
- Inherited no-GC gate: `pipeline/validate-no-gc-conformance.sh`
- Inherited output regressions: `outputLatencyBenchmark`, `outputTopologyBenchmark`

## Startup and Readiness

- Runtime starts from generated state with parent `009b` services healthy.
- Gateway admission stays unready until all mandatory replicas install a verified snapshot and catch up
  to durable high watermark.
- BLP admission stays unready until snapshot/journal replay and JIT warm-up complete.
- State metadata/UI header/About/status identify `in-memory-risk-gateway`.

## Replica Bootstrap

- Updates immediately before/during/after snapshot watermark are applied exactly once.
- Duplicate delta is ignored; reorder/gap/epoch change invalidates readiness and re-bootstrap occurs.
- Invalid checksum/schema and bootstrap-buffer overflow fail bootstrap without dropping updates.
- Replica readiness/version/high-watermark/lag/gap/re-bootstrap metrics are present.

## Gateway Screening

- Valid known account/security/principal passes preliminary screen with no remote validation lookup.
- Unknown/disabled account, unauthorized principal, unknown/disabled/halted/restricted security,
  kill switch, stale/missing price, price collar, size, and notional violations reject locally.
- Unknown ticker does not create a symbol-table entry.
- Static/runtime instrumentation proves no account/reference/price/risk REST/JPA/JDBC call on admission.

## Authoritative BLP Decisions

- Valid command is accepted and reserves exact exposure before becoming executable.
- Credit, position, and concentration limit failures reject with stable reason/version fields.
- Two concurrent Gateways against one remaining headroom cannot overshoot.
- Deliberately stale Gateway pass followed by newer BLP control state rejects; BLP wins and mismatch
  telemetry increments.
- Rejected command is in journal but absent from executable order, reservation, trade, position, and
  accepted NATS subjects.

## Idempotency and Reservation Lifecycle

- Duplicate `clientOrderId` returns original decision/sequence and creates no duplicate mutation.
- Partial fill converts only filled reservation; full fill clears remainder.
- Cancel/expiry releases remaining reservation exactly once.
- Replay/restored state produces no negative/double-released reservation.
- Capacity exhaustion produces explicit `CAPACITY` behavior without unbounded fallback.

## Failure / Degraded Mode

- Control-stream disconnect before stale deadline alerts; beyond deadline risk-increasing admission
  fails closed.
- Risk administration outage preserves last proven policy without a command-path lookup.
- Version gap/invalid policy quarantines update and blocks affected admission.
- Explicitly allowed cancel/risk-reducing operation remains available in documented modes; unrelated
  risk-increasing order remains blocked.

## Determinism / Recovery

- Mixed control/price/command journal replay yields byte-equivalent decisions and identical policy,
  reservation, idempotency, order, and position state.
- Snapshot plus journal tail recovers to last journaled sequence within inherited recovery target.
- Replay performs no external account/reference/risk query.

## Compatibility

- Accepted create/list/cancel/force-fill/auto-fill and market-trade journeys remain `009b` compatible.
- Accepted `/orders`, account orders, trades, account trades, and positions subjects retain payloads.
- UI blotters/tickets update normally for accepted business events.
- Relational query projection remains correct and eventually catches up.
- Output-disruptor topology and handler benchmark behavior remain unchanged.

## Metrics / Gates

- Required replica/Gateway/risk/idempotency/mismatch metrics exist with bounded labels.
- Prometheus targets are up and Grafana risk/replica panels are provisioned.
- Epsilon-GC and banned-API gates cover new Gateway/BLP risk code and pass.
- Gateway screening and BLP decision percentile reports meet `NFR-IMRG01` in perf profile.
- Inherited `009b` state smoke, output latency, and output topology benchmarks pass without material
  regression.

