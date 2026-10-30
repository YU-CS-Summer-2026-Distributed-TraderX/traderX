# Cluster test failover race

Status: integrated 2026-10-30. A test-harness fix; no consensus, gateway or engine code changed.

`ThreeMemberClusterTest` (inherited from YU12, copied up to YU18 unchanged) was the one recurring red in
full order-matcher suites on a loaded Mac. It timed out at 120 s three times on 2026-10-30, and in the
ADR-090 and order-types runs before that. Each time it passed when rerun alone. Every report pointed at the
shared `await()` line, so the failing wait was never named. It was taken to be the election.

It was not the election. The test offered the next order before its own client had learned the new leader.
The order went into the dead leader's ingress and was lost, and the egress wait for its ack ran out. The
cluster had elected, and the survivor had followed, in under a second. Aeron does not deliver ingress
across a failover. Production gateways already answer such orders as ambiguous for a deduped resend
(`ClusterGatewayMain#onNewLeader`, ADR-090/091). The test client offered once and never resent.

See [requirements](spec.md), [implementation](plan.md) and [evidence](tasks.md).

Source: `specs/YU18-risk-integration/generation/runtime-overrides/order-matcher/src/test/java/finos/traderx/ordermatcher/cluster/ThreeMemberClusterTest.java`.
