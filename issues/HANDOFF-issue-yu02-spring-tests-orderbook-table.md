# Issue: generated YU02's Spring tests fail — `Table "ORDERBOOK" not found`

**Found:** 2026-08-03. **Status:** open, diagnosed only — two fixes attempted and both were wrong.
**Scope:** generated `YU02-lmax-kubernetes` test suite. **Not a production defect.**

## Symptom

Against a clean render of YU02 (`rm -rf generated/code/target-generated`, regenerate,
`./gradlew test --offline --rerun-tasks`):

```
38 tests, 21 passed, 13 failed, 4 skipped
```

All 13 failures share one root cause — the Spring context cannot start:

```
Error creating bean with name 'lmaxRecovery'
  → Error creating bean with name 'lmaxEngine'
    → org.springframework.jdbc.BadSqlGrammarException: ConnectionCallback; bad SQL grammar
      → org.h2.jdbc.JdbcSQLSyntaxErrorException: Table "ORDERBOOK" not found
```

Affected: `OrderMatcherApplicationTests.contextLoads` and all twelve `LmaxHotPathParityTest` cases.

## Why it is only visible now

**Generated YU02 did not compile between 2026-07-14 and 2026-08-03**, so this suite had not run in
three weeks. `29916ed0` propagated the home branch's `OrderMatcherService` over the tip's, dropping
`onPriceTickRaw`, which the YU02-layer `PricingNatsBinarySubscriberService` calls; `867a7a7a` /
`45624b1e` (2026-08-03) restored it. These 13 failures are **not a regression from that fix** —
they are what the suite has been doing, unobserved, since before it broke. `DbWarmupReader` last
changed 2026-07-16, two days *after* the compile broke, so that change was never exercised here.

## Why it is not a production defect

`recovery.source` defaults to `db`, so `LmaxEngine.afterPropertiesSet()` warm-reads the read model
through `DbWarmupReader` (`FROM OrderBook`) on boot. In a deployment the schema is created
out-of-band by the `database-init` ConfigMap long before the order-matcher starts, so the table is
always there. The failure only appears where **Hibernate owns DDL** — the
`ddl-auto=create-drop` H2 context every `@SpringBootTest` in this module uses.

## What is established

- `OrderRecord` **is** `@Entity` with `@Table(name = "OrderBook")`; `spring-boot-starter-data-jpa`
  is on the classpath; 3 entities total. `create-drop` therefore *should* create the table.
- The test sets `spring.datasource.url=jdbc:h2:mem:ordermatcher9b;MODE=MySQL;DB_CLOSE_DELAY=-1`
  and `spring.jpa.hibernate.ddl-auto=create-drop`. It does **not** set `recovery.source`.
- No `.sql` file exists in `src/test/resources` or `src/main/resources` in the composed tree.

## The unexplained part — start here

**Generated YU15 passes 340 tests / 0 failures with materially the same setup.** Verified identical
between the YU02 and YU11/YU13 layers:

- `recovery.source=${RECOVERY_SOURCE:db}` — set identically in **every** layer's
  `application.properties`, and identical in both composed trees.
- `LmaxEngine`'s recovery guard (`if (journalRecovery) … else bootstrapFromReadModel()`) — identical.
- The `DbWarmupReader` call site — identical.
- The `@SpringBootTest` property block — YU13's is YU02's plus `risk.bootstrap.enabled=false`
  (a YU04 bootstrap flag, unrelated to the schema).

So the two states run the same recovery code against the same declared schema and the same H2
settings, and one works. **That is the thing to explain, and no theory should be trusted until it
accounts for it.**

## Fixes attempted, both wrong — do not repeat

1. **"A later layer sets `recovery.source=journal`; backport it."** Wrong. Every layer sets
   `${RECOVERY_SOURCE:db}`.
2. **Bean-ordering race — `@DependsOn("entityManagerFactory")` on `LmaxEngine`.** Wrong. The
   annotation composed into the generated tree correctly (verified) and the failure was unchanged,
   so this is not Hibernate running its DDL *late*. Reverted.

That the second attempt changed nothing is informative: the table is likely **never created under
the name the raw query looks for**, rather than created too late.

## Next diagnostic (not another theory)

Ask H2 what it actually created, rather than reasoning about it: log
`INFORMATION_SCHEMA.TABLES` at context start, or enable `spring.jpa.show-sql` /
`org.hibernate.tool.hbm2ddl` logging in the test and read the emitted `create table` statement.

The leading hypothesis to check first — **identifier casing**. H2 with `MODE=MySQL` folds unquoted
identifiers to upper case. If Hibernate emits a *quoted* mixed-case `"OrderBook"` while
`DbWarmupReader`'s raw JDBC `FROM OrderBook` resolves unquoted to `ORDERBOOK`, the table exists and
is still not found — which matches the error text exactly. If so, the fix belongs in the test's H2
URL (e.g. `DATABASE_TO_UPPER=FALSE`) or in the entity's quoting, not in the engine.

## Do NOT "fix" it by making the warm-up tolerant

Catching the missing table and starting empty is the smallest diff and is wrong. It converts
*"I cannot recover my state"* into *"I silently started empty"* — the exact silent-data-loss mode
this engine's fail-closed discipline exists to prevent, and it would apply on real deployments
against a genuinely broken database.

## Reproduce

```bash
cd ~/dev/lmax/traderX-blp-ha-demo
rm -rf generated/code/target-generated
bash pipeline/generate-state.sh YU02-lmax-kubernetes
cd generated/code/target-generated/order-matcher && ./gradlew test --offline --rerun-tasks
```

`--rerun-tasks` matters: a plain `compileJava` returned `BUILD SUCCESSFUL in 1s` from cache after a
fresh render and would have been believed.
