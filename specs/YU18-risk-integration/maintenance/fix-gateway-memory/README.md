# FIX gateway memory

Status: bounded on `traderX-risk-integration` 2026-10-28. It answers f15ho, where the 1 h FIX arm at 400K/s filled
the gateway heaps at ~25 min. Re-run of that hour on the fixed image: FIX-tail lane. Decision:
[ADR-092](../../system/adr-092-fix-gateway-memory-bound.md).

Sources (YU18 `generation/runtime-overrides/order-matcher/.../cluster/`):
- `FixGatewayAcceptor.java`: `MAX_QUEUED_WRITES`, `INBOUND_QUEUE`, and the streaming `adopt`.
- `FixSessionStore.java`: `SentLog`, and the streaming `scan`.
- Test: `FixGatewayMemoryBoundTest.java`.

Settings: `FIX_MAX_QUEUED_WRITES` (2,048), `FIX_INBOUND_QUEUE` (1,024).

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).
