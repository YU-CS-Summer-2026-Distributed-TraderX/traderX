# Queued owner-task deadline lifecycle

Status: implemented and locally verified; coordinator review pending. Owner: Codex RI23. Base: `22d4c9067ce6a01f33678e7cb061c8d0e677e9c3`.

Synchronous `ClusterGatewayMain.onOwner` calls retire queued work when their waiter times out or is interrupted, if retirement wins the atomic race with owner start. A held task already removed from the queue is fenced too. Once start is claimed, the original callable and future keep running; caller timeout remains an unknown outcome. No owner interruption or rollback occurs.

See [spec](spec.md), [plan](plan.md), [tasks](tasks.md), [shared quickstart](../../quickstart.md), and [component rules](../../../../docs/spec-kit/state-components.md). Generation inputs remain at the state parent. Operative source/test: YU18 `ClusterGatewayMain.java` and adjacent `OwnerTaskDeadlineTest.java` runtime overrides. Earlier YU12/YU13/YU16/YU17 gateway overrides are inherited history; YU18 composes last. The pipeline extension below applies the same unstarted fence to new/cancel/replace/typed queued offers. After start, normal offer/ACK/reaper ownership remains unchanged.
