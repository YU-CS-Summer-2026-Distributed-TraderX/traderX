# Exactly-once client

Status: the venue contract and the FIX restatement on `traderX-risk-integration`; the client is the hxb loadgen
(`independent-baseline/pods/loadgen.mjs`, `plan.exactlyOnce`, binary), proven locally through a gateway kill and a
leader kill with a strict native replay. FIX client side and the GKE arm pending. Decision:
[ADR-091](../../system/adr-091-exactly-once-client.md). Base requirement: YU03 FR-IMRG14 (idempotent retry).

Sources (YU18 `generation/runtime-overrides/order-matcher/.../cluster/`): `FixGatewayAcceptor.java`
(`sendRestatement`); engine `lmax/MatchingEngine.java` (`answeredAsDuplicate`, unchanged); tests
`FixGatewayFillReportTest.java`, `IdempotencyEvictionTest.java`. Harness (outside this repo):
`pods/loadgen.mjs`, `tests/test_loadgen_exactly_once.mjs`, `native/.../HxNativeReplay.java`, arm `xo`.

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).
