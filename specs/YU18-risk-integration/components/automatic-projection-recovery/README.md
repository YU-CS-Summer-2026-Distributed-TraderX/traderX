# Automatic projection recovery

Owner: Claude (auto-recovery lane). Status: implemented; local review pending, 2026-09-25.

Managed-run SQL projections catch up automatically from the authoritative Aeron archive on
consumer startup, NATS reconnect and a periodic cadence, while trading continues. Builds on
[event recovery](../event-recovery/README.md), whose explicit operator endpoint is unchanged.
See [spec](spec.md), [plan](plan.md), [tasks](tasks.md) and
[operator notes](../../../../docs/risk-integration/automatic-projection-recovery.md).
Generation stays in the YU18 parent. Enabled only in the disposable `auto-recovery-local` profile.
