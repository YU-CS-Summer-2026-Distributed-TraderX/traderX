# Gateway acknowledgement metrics

Status: integrated locally, 2026-10-07; see the evidence and limits below.
Updated: 2026-10-07. Owner: Codex RI28, branch `codex/ri28-ack-metrics`, accepted base `a0d6da0b`.

[Spec](spec.md) · [Plan](plan.md) · [Tasks](tasks.md). Backlog: RI-28.

The operative YU18 gateway retains the legacy `ack_unmatched` total and adds bounded reason
counters. Correlated fill status transitions are expected continuation flow. Missing correlation,
repeated partial fills with indistinguishable wire bytes, and evicted evidence remain unknown.
This is diagnostic classification; it supplies no financial or trade-loss verdict.

Generation inputs stay at the state parent. The changed sources are `ClusterGatewayMain.java`,
`GatewayAckHistory.java`, and `GatewayAckMetricsTest.java` (plus a session-identity stub in `GatewaySubmissionEncodingTest.java`) in the YU18 order-matcher runtime override.
YU18 supersedes complete gateway overrides in YU12/13/16/17; earlier states are unchanged.

Dependencies: existing keyed inflight requests, session identity supplied by AeronCluster, and the
existing 32-byte acknowledgement. Deterministic engine, wire and snapshots are unchanged.

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
