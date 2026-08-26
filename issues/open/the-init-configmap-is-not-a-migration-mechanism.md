# The init configmap is not a migration mechanism — and the migration that replaced it only fires on pod creation

> Facts measured 2026-08-25 on `kind-traderx-yu12-cluster`. The startup guard described below has
> LANDED and is covered by a two-arm test; the residuals at the bottom have not been fixed. Same
> family as [the manifests pin a build the rig no longer runs](the-manifests-pin-a-build-the-rig-no-longer-runs.md)
> — in both, the pack and the rig diverge and nothing is loud about it. That one is about the
> IMAGE the rig runs; this one is about the SCHEMA it runs against.

## The class

Schema ships in the `database-init-sql` configmap
([database-init-configmap.yaml](../../specs/YU17-otc-rates/generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml)),
whose `001-initialSchema.sql` key is mounted into `/docker-entrypoint-initdb.d/`. **That directory
executes only against an EMPTY data directory.** A long-lived PVC therefore keeps whatever schema it
had when it was first initialised, and every DDL change shipped afterwards reaches nothing. Nothing
compares the two, so the divergence is discovered only when a query happens to need the missing
thing.

`trade-processor` ran `spring.jpa.hibernate.ddl-auto=none`, so Hibernate never checked either.

## Two instances, one day

| | instance 1 | instance 2 |
|---|---|---|
| what drifted | `orderbook.status` CHECK constraint refused the new `QUEUED` status | `orderbook.traceid` column absent |
| shipped in | the DDL's CHECK | `2a0c2231` made the entity select `traceid` |
| how it surfaced | caught pre-emptively by the coordinator before a proof failed | `Unknown column 'or1_0.traceid' in 'SELECT'`; **271 rejected orderbook writes**; `GET /accounts/{id}/orders` → HTTP 500 |
| cost | none | surfaced as a **failing proof about session phases**, two subsystems from the cause |
| fixed by | a human running `ALTER` by hand | a human running `ALTER` by hand |

Instance 2's sharpest detail: the DDL *already contained the fix*, beside a comment explaining why
it could not fire — `database-init-configmap.yaml:442`.

## The mechanism is narrower than "there is no migration runner"

There **is** one. `eod-price-db` carries a `schema-migrate` init container
([eod-price-db.yaml](../../specs/YU17-otc-rates/generation/kubernetes/cluster/eod-price-db.yaml))
that boots a temporary `mariadbd` and applies the idempotent `900-migrations.sql` key to an
already-populated volume. It predates both incidents and still did not prevent instance 2, because:

- **init containers run on POD CREATION, not on container restart.** The db pod was created
  `2026-08-19T01:38:05Z`; its `mariadb` container has restarted 3× since (last `2026-08-23`), and
  none of those re-ran the init container.
- the `traceid` ALTER was committed `2026-08-25`. Between those dates the mechanism simply never
  ran, and **nothing ties "ship a DDL change" to "recreate the database pod"**.

So the gap is not a missing mechanism. It is that the mechanism is triggered by an event nobody
performs when they change the schema, and its not having run is invisible.

## What landed: the divergence now fails at startup

`ddl-auto=validate` — Hibernate's own feature, no custom checker — turns the drift into a startup
refusal. Verified, not assumed:

- **`validate` accepts the ACTUAL live rig schema.** A read-only `mariadb-dump --no-data` of the rig
  was replayed into a throwaway container and the real service booted against it. It tolerates every
  benign mismatch that was the reason to distrust `validate` here: entity `varchar(16)` vs live
  `varchar(32)` on `orderbook.security`, entity `DECIMAL(18,3)` vs live `decimal(18,6)` on
  `limitprice`/`lastexecutionprice`, and entity tables named `TRADES`/`POSITIONS` against lowercase
  tables (the server runs `lower_case_table_names=1`).
- **The shipped DDL and the live schema are currently byte-identical** across all 13 tables — both
  hand-fixes were complete, and there is no third drift outstanding.

`SchemaValidationFailureAnalyzer` (a Spring Boot `FailureAnalyzer`, ~25 lines) makes the refusal name
the fix rather than the symptom. Actual output:

```
Description:
Schema-validation: missing column [traceid] in table [orderbook]

Action:
The database this service is pointed at is missing schema its entities read. Startup is refused
deliberately: serving traffic would reject every write through the affected table one query at a
time, with nothing to connect the rejections back to this cause.

To fix, add the idempotent statement to the 900-migrations.sql key of
kubernetes-runtime/manifests/base/database-init-configmap.yaml, e.g.

    ALTER TABLE <table> ADD COLUMN IF NOT EXISTS <column> <type>;

then restart the database pod. Its schema-migrate init container applies 900-migrations.sql to an
already-populated volume; the 001-initialSchema.sql key runs ONLY on an empty data directory and
will not reach a live rig.
```

`SchemaMatchesShippedDdlIT` runs **both arms** — boots the real application against the shipped DDL
and expects it up; drops one shipped column and expects the boot refused naming that column. It
reads the schema out of the configmap itself (not a copy, and not Hibernate's own `create` output,
which would be a tautology) and reads `ddl-auto` out of the production properties file, so reverting
that setting fails the test instead of quietly disarming it.

## What this does and does not catch

- **Catches:** a missing table or column that an entity maps — instance 2's exact shape.
- **Does NOT catch:** instance 1's shape. Hibernate does not validate CHECK constraints, defaults,
  indexes or nullability-only differences. A stale CHECK still fails per-query at runtime.
- **Does NOT catch** drift in tables no entity maps (`stocks*`, `account_*`, `eod_*`).

## Residuals (not fixed)

1. **Nothing still ties a DDL change to recreating the db pod.** The `900-migrations.sql` key is the
   right place to put statements, but whether it has actually run against a given volume is
   unobservable. The startup guard makes the *consequence* loud for `trade-processor`'s three
   entities; it does not make the *migration* reliable.
2. **`order-matcher` maps the same three tables (`OrderBook`, `POSITIONS`, `TRADES`) and defaults to
   `ddl-auto=${SPRING_JPA_DDL_AUTO:update}`.** `update` silently issues DDL against the shared
   database — a service mutating a shared schema at startup. It is latent on this rig only because
   the cluster tier runs `traderx/cluster-node` with no datasource configured; any tier that runs the
   Spring order-matcher has it live. `update` also would not have caught instance 1 (it never
   modifies an existing CHECK) and can create a column with a Hibernate-inferred type that differs
   from what the configmap declares.
3. **States YU13–YU15 are internally incoherent.** `OrderRow` carries `traceId` from the YU13 layer,
   but the operative configmap for those states (YU06's) has no `traceid` column. They are unaffected
   today only because they resolve to YU06's `application.properties`, which still says
   `ddl-auto=none`. Turning `validate` on for them requires adding the column to their DDL first —
   which is why the change was made at the **YU16** layer, the first one whose DDL declares it.
4. **Hand-carry:** the change lives in `specs/YU16-cdm-instruments/…/trade-processor/` and so reaches
   YU16 and YU17. The `traderX-YU16-cdm-instruments` worktree needs the same four files.

## Re-derive

```bash
K="kubectl --context kind-traderx-yu12-cluster -n traderx"
# when did the init container last run (pod creation), vs when did the DDL change?
${=K} get pod -l app=eod-price-db -o jsonpath='{.items[0].metadata.creationTimestamp}{"\n"}'
git log -1 --format='%ad %h %s' --date=short -- \
  specs/YU17-otc-rates/generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml

# is the live schema still what the pack ships? (read-only)
${=K} exec deploy/eod-price-db -c mariadb -- \
  mariadb-dump -utraderx -ptraderx --no-data --skip-comments --skip-add-drop-table traderx

# both arms of the guard
cd generated/code/target-generated/trade-processor && \
  ./gradlew integrationTest --tests '*SchemaMatchesShippedDdlIT'
```
