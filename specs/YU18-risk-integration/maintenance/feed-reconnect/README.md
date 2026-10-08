# Bounded feed reconnect recovery

Status: integrated locally after coordinator review and user approval; established live acceptance pending. Author: Codex RI23. Base: `bf1d13e944ce1a919800a73d70a2dc2a8e2c4587`.

After an established Aeron connection is lost, the adapter retires that client and retries within a configured incident budget. Every new connection starts with fresh symbol maps and request-correlated registration. Initial failure and exhausted recovery remain visible terminal failures. An idle connected feed stays connected without a price-activity watchdog.

See [spec](spec.md), [plan](plan.md), [tasks](tasks.md), [shared quickstart](../../quickstart.md), and [state-pack rules](../../../../docs/spec-kit/state-components.md). New YU18 `FeedAdapterMain.java` fully overrides the inherited YU12 file; YU12 remains unchanged. Tests: `FeedReconnectTest`, `FeedReconnectBudgetTest` and inherited `FeedAdapterParseTest`. No new library or core/wire/snapshot change.

Task record and [LR-02](../../../../issues/risk-integration/live-rig/lr-02-feed-reconnect.md) are coordinator-owned. LR-02 remains open: source/generated/mock evidence and an actual native cold-failure child do not establish live member/session/DNS recovery and price application. Coordinator review and direct user integration approval precede incorporation; this maintenance task is not marked done.

Integration source: `e1dcf097` preserves approved candidate459de827 runtime/test bytes. Combined full generation and52focused generated cases passed. User approved incorporation on2026-10-08. LR02 remains open; this pack is not marked fully accepted live.
