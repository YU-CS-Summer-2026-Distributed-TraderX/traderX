# FIX TLS entry

Status: built and proven on kind on `traderX-risk-integration` 2026-10-29; not deployed to production (the rollout
session applies it with yaakov's approval). It gives an external FIX client, yaakov's Mac running QuickFIX/J, a TLS
entry to the production gateways. Decision: [ADR-095](../../system/adr-095-external-fix-tls-entry.md).

Sources:
- YU18 `generation/runtime-overrides/order-matcher/.../cluster/`: `FixGatewayAcceptor.withTls`, `ClusterGatewayMain`
  (`FIX_TLS_PORT`, `FIX_TLS_COMPIDS`); test `FixGatewayTlsTest.java`.
- `scripts/gcp-compsci/prepare.py` (`FIX_TLS`: the gateway's TLS socket, Secret `fix-tls`), `provision-fix-tls.py`
  (certificate, keystore, pinned trust store, Secret), `render-fix-tls-access.py` (Service `fix-tls-external`).
- `scripts/fix-tls/`: `quickfixj-client.cfg`, `FixTlsSmoke.java`, `fix-tls-smoke.sh`.
- `scripts/proofs/yu18-fix-tls-entry.sh`: the kind proof.

Settings: `FIX_TLS_PORT` (18143), `FIX_TLS_COMPIDS` (`EXTQFJ`), `FIX_SSL_KEYSTORE`, `FIX_SSL_KEYSTORE_PASSWORD`.

See [spec](spec.md), [plan](plan.md), [tasks](tasks.md) and the [runbook](runbook.md).
