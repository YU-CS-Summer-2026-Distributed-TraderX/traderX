# order-matcher issues DDL against the shared database at startup (`ddl-auto=update`)

> **Ruling (coordinator, 2026-08-25): do NOT flip this.** Filed with the per-state analysis attached
> so the next person does not flip it blind. Split out of
> [the init configmap is not a migration mechanism](schema-migration-fires-only-on-pod-creation.md),
> which fixed the sibling exposure in `trade-processor`.

## What it is

`order-matcher` ships `spring.jpa.hibernate.ddl-auto=${SPRING_JPA_DDL_AUTO:update}` and maps three
entities onto the **same tables `trade-processor` owns**:

| entity | table | source layer |
|---|---|---|
| `OrderRecord` | `OrderBook` | upstream base — not overridden by any `specs/` layer |
| `Trade` | `TRADES` | YU01, YU02, YU05 |
| `Position` | `POSITIONS` | YU01, YU02 |

With no `SPRING_JPA_DDL_AUTO` set, `update` is live: **a service that issues DDL against a shared
database at startup.** That is a bigger thing than the per-query failure mode we just closed —
`trade-processor` merely *read* a schema it did not match; this one *changes* it.

## Why it is worse than what was fixed, and still not urgent

Worse:

- `update` is unreviewed DDL. It creates a column with the type **Hibernate infers from the entity**,
  which need not match what `database-init-configmap.yaml` declares — so the rig can end up with a
  column no DDL file describes.
- It would **not** have caught the `orderbook.status` CHECK drift either: `update` never modifies an
  existing constraint, and never narrows or drops.
- It makes "what schema does this rig have?" depend on **which service started first**.

Not urgent:

- **Inert on the cluster tier.** `cluster-gateway` and `order-matcher-cluster` run
  `traderx/cluster-node` with **no datasource configured** — verified 2026-08-25, no `DATABASE_*`
  env on either workload. The Spring JPA order-matcher is not running on this rig at all.
- The tier where it *was* live is the **single-BLP tier, retired 2026-08-21**.

## The per-state analysis (the part that makes flipping safe or unsafe)

Flipping to `validate` is only safe where the operative entity mappings and the operative configmap
agree — the trap that made the `trade-processor` change land at YU16 rather than YU17. Resolved
per state (last-wins over `specs/*/generation/runtime-overrides/`):

| state | operative `application.properties` | operative configmap | order-matcher columns missing from DDL |
|---|---|---|---|
| YU01 | YU01 | *(none — compose tier carrier)* | not analysed by this method |
| YU02–YU03 | YU02 / YU03 | YU02 | none |
| YU04 | YU04 | YU04 | none |
| YU05 | YU05 | YU05 | none |
| YU06 | YU06 | YU06 | none |
| YU07–YU08 | YU07 | YU06 | none |
| YU09–YU10 | YU09 | YU06 | none |
| YU11–YU14 | YU11 | YU06 | none |
| YU15 | YU11 | YU15 | none |
| YU16 | YU16 | YU16 | none |
| YU17 | YU16 | YU17 | none |

**Result: column-coherent at every state YU02→YU17.** `OrderRecord` maps 12 `OrderBook` columns and
notably **no `traceid`** — which is also why `update` would not have papered over instance 2.

The method was self-tested before being trusted: run against `trade-processor` it flags exactly
YU13–YU15 (`orderbook.traceid`), the incoherence derived independently. A checker that returned
"coherent" everywhere for both services would have been a broken probe, not a clean bill.

## What this analysis does NOT cover — read before flipping

1. **Whether any deploy path relies on `update` to CREATE the tables on a fresh database.** The
   column check assumes the tables already exist from the configmap. A tier that has no init SQL and
   currently leans on `update` to bootstrap would **fail to start** under `validate`. This is the
   open question, and it is the one that matters.
2. **Type/precision agreement.** Only column *existence* was checked. (For `trade-processor`,
   Hibernate was empirically shown to compare JDBC type codes, not precision or length — so this is
   likely benign, but it is not verified for these entities.)
3. `SPRING_JPA_DDL_AUTO` is an **env-overridable** default; any environment setting it explicitly
   needs checking separately.

## Re-derive

```bash
# is it live anywhere? (no DATABASE_* env => JPA path not configured)
K="kubectl --context kind-traderx-yu12-cluster -n traderx"
${=K} get statefulset order-matcher-cluster deploy/cluster-gateway \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{range .spec.template.spec.containers[*]}{range .env[*]}  {.name}{"\n"}{end}{end}{end}'

# the shipped default
grep -rn 'ddl-auto' specs/*/generation/runtime-overrides/order-matcher/src/main/resources/application.properties
```
