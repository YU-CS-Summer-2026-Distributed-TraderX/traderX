# Bounded settlement

Status: ready for review in the isolated branch; retained-data proof passed, zero-node cleanup verified. User authorized implementation and same retained-data test on 2026-10-13; no integration or push. Owner: 01a135b5-8b9e-7d00-b589-63cf004884a9.

The delivered unbounded due-trade query exhausted 768 MiB heap while MariaDB buffered millions of rows. This YU18 override uses bounded JDBC batches and independent transactions. See spec.md, plan.md and tasks.md. Earlier YU05 behavior is unchanged. Private retained-data evidence lives outside Git under coordination/eod-integration/review-evidence/c4d-settlement-fix-20261013.

## Applying the candidate

Apply `generation/runtime-overrides/postgres-database-replacement/mariadb-migrations/settlement-batch.sql` under a maintenance barrier before starting the corrected processor on a retained database. The generated fresh and retained Kubernetes scripts and standalone initial schema include the same index. Building the index can take time on a large table; do not infer an online, zero-impact migration from idempotence. The existing database migration/startup contract remains in force.

Configuration: `settlement.sweep.batch-size` defaults to500 (allowed1–2000), `settlement.sweep.max-batches` to100 (1–1000), and `settlement.sweep.budget-ms` to1000 (1–30000). The existing interval defaults to5000ms. The budget is checked between transactions; it does not interrupt a SQL statement or bound lock wait. One batch retains at most the configured number of IDs and updates only that batch. Each transaction commits before the next starts, releasing the shared projection lock.

`traderx_settlement_batch` measures successful transaction duration including lock wait and commit. `sweptCount` is process-local and advances after commit; it is not a durable financial counter. Force settlement shares the same lock and preserves REQUIRED participation in a caller transaction; its counter advances through an after-commit callback.

## Verified evidence

Generated suites pass115 unit tests and9 pinned-MariaDB tests, with no failures or skips. Across retained-data batch/restart arms, all5,757,028 originally due rows settled; final SQL has5,851,507 Settled rows and zero due Processing rows. The complete protected-field SHA256 witness matches. Final build completed4,137,028 remaining transitions without restart/OOM, with sampled heap at most57.5MiB under a768MiB limit and a62-second quiet window. Final completion used a test-only1s delay; shipped5s delay remains unchanged and was tested in the initial arms.

Measured250/500/1000-row transactions averaged18.94/30.84/54.33ms, including lock wait and commit, on successive portions of this isolated backlog. These are descriptive bounds evidence, not an optimality or production-throughput claim. The500-row default balances transaction size and observed per-row overhead. Full retained delivery and original all-member restoration remain separate gates.
