# Quickstart: In-Memory Risk Gateway

> The generation hook, lifecycle delegates, risk implementation, and verification tasks are executable.

## 1) Generate This State

```bash
bash pipeline/generate-state.sh in-memory-risk-gateway
```

Expected parent: `009b-lmax-sequencer-architecture`.

## 2) Start Runtime

```bash
./scripts/start-state-in-memory-risk-gateway-generated.sh
./scripts/start-state-in-memory-risk-gateway-generated.sh --skip-build
```

Admission readiness remains false while source snapshots/deltas or BLP replay/warm-up are incomplete.

## 3) Inspect Readiness and Replica State

```bash
ORDER_MATCHER_PORT="${ORDER_MATCHER_PORT:-18110}"

curl -s "http://localhost:${ORDER_MATCHER_PORT}/health"
curl -s "http://localhost:${ORDER_MATCHER_PORT}/metrics" \
  | rg "traderx_replica_ready|traderx_replica_lag|traderx_replica_gap_total|traderx_risk_policy_version"
```

Every mandatory replica must report ready before risk-increasing admission succeeds.

## 4) Run State Smoke

```bash
./scripts/test-state-in-memory-risk-gateway.sh
./scripts/test-state-in-memory-risk-gateway.sh --skip-messaging
```

The smoke contract is detailed in `tests/smoke/README.md`.

## 5) Run Hot-Path and Regression Gates

```bash
bash pipeline/validate-no-gc-conformance.sh generated/code/target-generated/order-matcher

(cd generated/code/target-generated/order-matcher && ./gradlew test noGcTest)
(cd generated/code/target-generated/order-matcher && ./gradlew outputLatencyBenchmark)
(cd generated/code/target-generated/order-matcher && ./gradlew outputTopologyBenchmark)
(cd generated/code/target-generated/order-matcher && ./gradlew riskLatencyBenchmark)
```

Use the same profile/fixture/environment as the recorded `009b` baseline for comparison.

## 6) Exercise Required Decisions

Test fixtures must cover:

- valid accepted command;
- unknown/disabled account and unauthorized principal;
- unknown/disabled/halted/restricted security;
- missing/stale price and price-collar rejection;
- size/notional/credit/position/concentration limits;
- duplicate `clientOrderId` returning the original decision;
- reservation conversion/release on fill/cancel;
- Gateway/BLP disagreement with BLP decision winning.

## 7) Exercise Bootstrap and Failure Modes

- Inject deltas before/during/after snapshot watermark and prove no gap.
- Disconnect the durable control stream through and beyond the stale deadline.
- Inject a source-version gap and epoch change; readiness must fall and re-bootstrap begin.
- Stop risk administration while the last proven installed policy remains active.
- Restart order-matcher; verify snapshot + journal replay restores controls/reservations/idempotency.

## 8) Inspect Metrics / Dashboards

```bash
curl -s "http://localhost:${ORDER_MATCHER_PORT}/metrics" \
  | rg "traderx_replica_lag|traderx_risk_decisions_total|traderx_gateway_blp_mismatch_total|traderx_idempotency_duplicate_total"

open http://localhost:3001
```

## 9) Stop Runtime

```bash
./scripts/stop-state-in-memory-risk-gateway-generated.sh
```

## Notes

- The demo profile validates behavior/allocation but not bare-metal latency budgets.
- Rejected commands are expected in the journal and absent from accepted business outputs.
- The output disruptor is inherited; failures there are regressions, not a reason to change its topology.
