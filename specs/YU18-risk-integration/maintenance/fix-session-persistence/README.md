# FIX session persistence

Status: integrated on `traderX-risk-integration` (`0e9ee1b3`, `d7a44f50`, and the fail-closed follow-up),
proven on a GKE side namespace; on production `traderx` since roll 4 (2026-10-28, `00b31d33`; rollout record §13). Decision:
[ADR-086](../../system/adr-086-persistent-fix-sessions.md), group commit in
[ADR-099](../../system/adr-099-fix-store-group-commit.md). Base requirements: YU10 FR-FIX04, FR-FIX11,
FR-FIX12; retires YU12 TD-AC01 where `FIX_SESSION_STORE=jetstream`.

Sources (YU18 `generation/runtime-overrides/order-matcher/.../cluster/`): `FixSessionStore.java`,
`FixWriteBarrier.java`, `FixGatewayAcceptor.java`, `ClusterGatewayMain.java` (unknown-ticker reject); tests
`FixSessionPersistenceIT.java`, `FixStoreGroupCommitIT.java`, and the gated benches `FixStoreThroughputIT.java`
(FIX_STORE_BENCH=1) and `FixGatewayThroughputIT.java` (FIX_GW_BENCH=1). Manifest: YU17 `generation/kubernetes/cluster/gke/gateway.yaml`; render guard
`scripts/gcp-compsci/prepare.py`. Proofs: `scripts/proofs/yu18-fix-resume.sh`, `yu18-fix-store-faults.sh`,
`yu18-fix-session.sh`; cost: `scripts/bench/fix-session-latency.mjs`.

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).
