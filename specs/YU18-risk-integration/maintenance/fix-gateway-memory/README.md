# FIX gateway memory

Status: bounded on `traderX-risk-integration` 2026-10-28. It answers f15ho, where the 1 h FIX arm at 400K/s filled
4 of 18 gateway heaps after their ingress fell behind at ~+24 min. Re-run of that hour on the fixed image: FIX-tail
lane. Decision:
[ADR-092](../../system/adr-092-fix-gateway-memory-bound.md).

Sources (YU18 `generation/runtime-overrides/order-matcher/.../cluster/`):
- `FixGatewayAcceptor.java`: `MAX_QUEUED_WRITES`, `INBOUND_QUEUE`, and the streaming `adopt`.
- `ClusterGatewayMain.java`: `retryFillFeed`, `QuietUntilSubscribed`, the fill-feed metrics, and
  `Inflight.dropAnswered`.
- `FixSessionStore.java`: `SentLog`, and the streaming `scan`.
- Tests: `FixGatewayMemoryBoundTest.java`, `FillFeedRetryTest.java`, `InflightRetentionTest.java`.

Settings: `FIX_MAX_QUEUED_WRITES` (2,048), `FIX_INBOUND_QUEUE` (256).

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).
