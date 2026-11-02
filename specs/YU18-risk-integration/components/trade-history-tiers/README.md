# Trade history tiers

Status: proposed 2026-11-01, not started; scheduled after the 2026-11-05 conference. Decision:
[ADR-102](../../system/adr-102-trade-history-off-mariadb.md). Issue: `issues/risk-integration/32-trade-history-off-mariadb.md`.

Trade history moves off MariaDB: ClickHouse (the existing [history store](../history-store/README.md) feed) is the
system of record for every trade side, KDB-X is the analytics tier, GCS is the cold tier under them. MariaDB keeps
current state only. See [spec](spec.md), [plan](plan.md), [tasks](tasks.md).
