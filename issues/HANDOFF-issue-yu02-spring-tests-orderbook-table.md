# Issue: generated YU02's Spring tests fail — `Table "ORDERBOOK" not found`

**Found:** 2026-08-03. **Status: RESOLVED 2026-08-14**, and verified on a real YU02 tree as this
file demanded. The YU01 fix — pin `PhysicalNamingStrategyStandardImpl` in the `@SpringBootTest`
property block so the entity's `@Table`/`@Column` names are used verbatim — was backported to the
YU02 layer's two `@SpringBootTest` classes, `OrderMatcherApplicationTests` and
`LmaxHotPathParityTest`. Both already carried it on YU01 and neither carried it on YU02, which is
exactly the 1 + 12 = 13 failures reported below.

Measured after: `bash pipeline/generate-state.sh YU02-lmax-kubernetes` then
`./gradlew test --offline --rerun-tasks` on the generated order-matcher →
**38 tests, 0 failed, 4 skipped**, against this file's original
`38 tests, 21 passed, 13 failed, 4 skipped`. Same total, same skips, the thirteen gone.

Note it was NOT a `.sql` file, which the note below predicted ("YU01's layer now ships 1 `.sql`
file; the YU02 layer ships 0"). YU01's single `.sql` is the production `mariadb-init` schema, not a
test resource; the actual fix lives in the test property blocks.

Original report follows.

**Status when filed:** open, diagnosed only — two fixes attempted and both were wrong.
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

---

## SOLVED 2026-08-03 — root cause found while fixing the same failure on YU01

**The table is created under a different name than the projector queries.** Spring Boot's default
naming strategy splits camel humps, so the `@Table(name = "OrderBook")` entity is created as
`order_book`, while the projector writes to `orderbook` — the name `mariadb-init` creates in
production. `TRADES` and `POSITIONS` have no camel hump, so `orderbook` is the only table that
misses; and because the flush is a single transaction, its failure rolls the trades and positions
writes back with it.

**The leading hypothesis below (H2 `MODE=MySQL` identifier casing) is DISPROVEN.** So is an earlier
claim that H2 was rejecting the MariaDB `ON DUPLICATE KEY UPDATE … VALUES(col)` — probing H2
2.4.240 directly refuted that one. Read the rest of this document as a record of how the bug hid,
not as a live investigation.

Note the diagnosis was only reachable after most-specific-cause logging was added: Spring's wrapper
reports a **missing table** as `BadSqlGrammarException`, which is what sent two separate
investigations toward the SQL dialect instead of the schema.

**The fix, and why it applies here.** It was fixed on YU01 by making the *test* schema match
production, not by bending the SQL — the right call, since production's schema is authoritative and
the entity mapping is what disagrees with it. YU01's layer now ships **1 `.sql` file; the YU02
layer ships 0**, which is precisely why YU02's 13 failures remain. Backporting the same shape to
the YU02 layer is the expected fix.

⚠️ **Unverified for YU02.** The root cause and the fix pattern are confirmed on YU01; nobody has yet
re-run YU02's suite with a test schema in place. Do that before closing this.

---

## Original investigation (superseded — kept for the traps it records)

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
