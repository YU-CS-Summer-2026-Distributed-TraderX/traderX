# The schema migration fires on POD CREATION — so a DDL change nobody recreates the pod for reaches nothing

> Facts measured 2026-08-25 on `kind-traderx-yu12-cluster`. Same family as
> [the manifests pin a build the rig no longer runs](the-manifests-pin-a-build-the-rig-no-longer-runs.md)
> — in both, the pack and the rig diverge and nothing is loud about it. That one is about the IMAGE
> the rig runs; this one is about the SCHEMA it runs against.
>
> **The startup guard below has LANDED but has NEVER RUN ON THE RIG.** `trade-processor` has not been
> rebuilt or rolled, so the running pod is still `ddl-auto=none`. Every arm was exercised off-rig
> (Testcontainers, plus a replay of a read-only dump of the live schema). **A startup guard that has
> never started the actual image is exactly the shape this project keeps finding** — treat it as
> unproven on the rig until someone rolls it.

## THE RULE (the cheap half of the fix)

**Changing `900-migrations.sql` requires RECREATING the database pod, not restarting the container:**

```bash
kubectl --context kind-traderx-yu12-cluster -n traderx delete pod -l app=eod-price-db
```

Init containers run on **pod creation**. A container restart — including every crash-loop recovery
and `kubectl rollout restart` of the container — does **not** re-run them.

## The class — stated correctly

The original framing of this gap was "the init configmap is not a migration mechanism". That is true
but it is **not** why the failures happened, and acting on it would have produced a migration runner
that already exists.

What is actually true:

- `001-initialSchema.sql` (a key of
  [database-init-configmap.yaml](../../specs/YU17-otc-rates/generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml))
  is mounted into `/docker-entrypoint-initdb.d/`, which executes **only
  against an EMPTY data directory**. On its own it reaches no long-lived volume. That much is the
  original framing, and it is correct.
- **But a migration mechanism was already built for exactly this**, and is committed: `eod-price-db`
  carries a `schema-migrate` **init container**
  ([eod-price-db.yaml](../../specs/YU17-otc-rates/generation/kubernetes/cluster/eod-price-db.yaml))
  that boots a temporary `mariadbd` and applies the idempotent `900-migrations.sql` key to an
  **already-populated** volume.
- **It predates both incidents and still did not prevent the second**, because init containers run
  on **pod creation, not container restart**. Measured: pod created `2026-08-19T01:38:05Z`; its
  `mariadb` container has restarted 3× since (last `2026-08-23`), none of which re-ran init; the
  `traceid` ALTER was committed `2026-08-25`. Between those dates the mechanism simply never ran.

**So the gap is not a missing mechanism. It is a mechanism triggered by an event nobody performs
when they change schema, whose not having run is invisible.** That is why the rule at the top of
this file is the cheap half of the fix, and why building another runner would have been wasted work.

## Two instances, one day

| | instance 1 | instance 2 |
|---|---|---|
| what drifted | `orderbook.status` CHECK constraint refused the new `QUEUED` status | `orderbook.traceid` column absent |
| shipped in | the DDL's CHECK | `2a0c2231` made the entity select `traceid` |
| how it surfaced | caught pre-emptively by the coordinator before a proof failed | `Unknown column 'or1_0.traceid' in 'SELECT'`; **271 rejected orderbook writes**; `GET /accounts/{id}/orders` → HTTP 500 |
| cost | none | surfaced as a **failing proof about session phases**, two subsystems from the cause |
| fixed by | a human running `ALTER` by hand | a human running `ALTER` by hand |

Instance 2's sharpest detail: the DDL *already contained the fix*, beside a comment explaining why
it could not fire — `database-init-configmap.yaml:442` (now `:445`).

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

## What this closed, and what it did NOT — read before treating the class as done

**This closed instance 2's shape only.** A missing table or column that an entity maps is now a
startup refusal.

**It did NOT close instance 1's shape, and chasing it was declined deliberately.** Hibernate
validates tables and columns — never CHECK constraints, defaults, indexes, or nullability-only
differences. **A stale CHECK still fails silently per-query, with the row simply never landing.**
That is the `orderbook.status` case at the top of this file, and it remains fully live.

Covering it would mean custom constraint-text assertions against `information_schema` — the custom
checker this work was explicitly told not to write, and worth less than it costs. Coordinator ruling
2026-08-25: **accepted as a stated limit; do not chase it.** Nobody should read this issue as having
closed the class.

Also not covered: drift in tables no entity maps (`stocks*`, `account_*`, `eod_*`).

## Residuals (not fixed)

1. **Nothing still ties a DDL change to recreating the db pod.** The rule at the top of this file is
   the cheap half and closes the gap for a human who knows to look; it is not a mechanism. Whether
   `900-migrations.sql` has actually run against a given volume remains **unobservable** — there is
   no marker to query and no alert if it is stale. The startup guard makes the *consequence* loud for
   `trade-processor`'s three entities; it does not make the *migration* reliable. Anything more is
   its own work (coordinator ruling 2026-08-25: leave open).
2. **`order-matcher` issues DDL against the same shared database** (`ddl-auto=update` by default).
   Split out with the full per-state coherence analysis attached, and a standing **do-not-flip**
   ruling: [order-matcher issues DDL against the shared database](order-matcher-issues-ddl-against-the-shared-database.md).
3. **States YU13–YU15 are internally incoherent.** `OrderRow` carries `traceId` from the YU13 layer,
   but the operative configmap for those states (YU06's) has no `traceid` column. They are unaffected
   today only because they resolve to YU06's `application.properties`, which still says
   `ddl-auto=none`. Turning `validate` on for them requires adding the column to their DDL first —
   which is why the change was made at the **YU16** layer, the first one whose DDL declares it.
4. **Hand-carry owed:** the change lives in `specs/YU16-cdm-instruments/…/trade-processor/` and so
   reaches YU16 and YU17 on this branch. The `traderX-YU16-cdm-instruments` **worktree** needs the
   same four files. Standing project policy; the coordinator is routing it rather than having a lane
   reach across worktrees.
5. **Never exercised on the rig** — see the note at the top. `trade-processor` has not been rebuilt
   or rolled with `validate`; the running pod is still `ddl-auto=none`.

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

---

## The inverse half, measured on GKE 2026-08-27: when it DOES re-run, it FAILS

Everything above is about a migration that **never fires**. The same mechanism produces the opposite
failure, and it is live on the GKE bench tier:

    Init container schema-migrate, exitCode 1:
    ALTER TABLE trades MODIFY COLUMN security VARCHAR(32)
    ERROR 1826 (HY000) at line 171: Duplicate CHECK constraint name 'state'

**`eod-price-db` keeps its PVC across a scale-to-zero.** So on scale-up the pod is NEW — init
containers run, correctly, by the rule above — and the migration executes against a schema **it has
already migrated**. `900-migrations.sql` is not idempotent, so it dies on the first constraint that
already exists.

**The blast radius is everything downstream**, and none of it names the cause: `reference-data`,
`account-service` and `trade-processor` all went `Error`/`CrashLoopBackOff` with MariaDB connect
failures, because the DB never left `Init:0/1`. The visible symptom is three application services
crashlooping; the cause is one DDL line 171.

**It recurs on every scale-up of that tier** until the migration is made re-runnable
(`ADD CONSTRAINT IF NOT EXISTS`, or guard each DDL on `information_schema`).

### The two halves together are the actual rule

| | fires? | outcome |
|---|---|---|
| container restart / `rollout restart` | **no** | DDL change reaches nothing, silently |
| pod recreation with a **fresh** volume | yes | clean, succeeds |
| pod recreation with a **retained** volume | yes | **fails on already-applied DDL** |

The third row is the common one in practice, because scale-to-zero is how this project parks a tier.

**Workaround used 2026-08-27** (recorded because it has a trap in it): delete the PVC and let the
migration run clean. **`eod-price-db` is a Deployment, not a StatefulSet** — nothing recreates its PVC,
so the pod then sat `Pending` with *"persistentvolumeclaim not found"* until the claim was re-applied
from `specs/YU17-otc-rates/generation/kubernetes/cluster/gke/eod-price-db.yaml`. A StatefulSet's
controller would have recreated it from its volumeClaimTemplate; a Deployment's does not.
