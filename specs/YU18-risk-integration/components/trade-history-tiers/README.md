# Trade history tiers

Status: first slice landed 2026-11-06, local and kind only (tiering policy, reader inventory, recon on ClickHouse,
G3h history-store gate check, KDB-X `gs://` answered); nothing in production. Decision:
[ADR-102](../../system/adr-102-trade-history-off-mariadb.md). Issue: `issues/risk-integration/32-trade-history-off-mariadb.md`.

Trade history moves off MariaDB: ClickHouse (the existing [history store](../history-store/README.md) feed) is the
system of record for every trade side, KDB-X is the analytics tier, GCS is the cold tier under them. MariaDB keeps
current state only. See [spec](spec.md), [plan](plan.md), [tasks](tasks.md).
