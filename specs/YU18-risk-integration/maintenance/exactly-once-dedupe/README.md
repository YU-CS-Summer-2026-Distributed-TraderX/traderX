# Exactly-once dedupe

Status: approved 2026-10-28 and implemented on `traderX-risk-integration`; not deployed.
Decision: [ADR-090](../../system/adr-090-exactly-once-dedupe.md).

Sources (YU18 `generation/runtime-overrides/order-matcher/`): `risk/StreamDedupe.java`, `risk/BlpRiskState.java`,
`cluster/MatchingEngineClusteredService.java` (gate, format 14), `lmax/AeronReplicationCodec.java` (tail),
`cluster/BinaryGatewayAcceptor.java` (YU18 override: the NEW flags byte), `cluster/ClusterGatewayMain.java`,
`cluster/FixGatewayAcceptor.java` and `cluster/FixSessionStore.java` (FR-XOD-12). Tests: `ExactlyOnceStreamTest`,
`BinarySequencedFrameTest`, `FixExactlyOnceIT`. Harness half (hxb loadgen `plan.sequenced`, HxNativeReplay
`duplicateKeys`/`resendsOfCommitted`, arms `xs`/`xn`): side-session notes `exactly-once-sequenced.patch`.

At 400K/s, the FR-IMRG14 key window (262,144 keys) covers 0.66 s. A resend after an election or a reconnect
arrives 12–42 s later. ADR-090 replaces the window for sequenced clients with a contiguous sequence per stream,
which catches a resend for the life of the stream, with ≈ 10 MB of state and a snapshot smaller than today's.

See [spec](spec.md) (requirements, test plan, CLOUD-QUEUE row), [plan](plan.md) and [tasks](tasks.md).
