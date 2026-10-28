# Exactly-once dedupe

Status: proposed 2026-10-28 and awaiting yaakov's approval of
[ADR-090](../../system/adr-090-exactly-once-dedupe.md). Nothing is implemented.

At 400K/s, the FR-IMRG14 key window (262,144 keys) covers 0.66 s. A resend after an election or a reconnect
arrives 12–42 s later. ADR-090 replaces the window for sequenced clients with a contiguous sequence per stream,
which catches a resend for the life of the stream, with ≈ 16 MB of state and a snapshot smaller than today's.

See [spec](spec.md) (requirements, test plan, CLOUD-QUEUE row), [plan](plan.md) and [tasks](tasks.md).
