# The eod-price-db schema migration cannot run a second time

**Status:** RESOLVED 2026-09-02 — root cause was not the migration's shape but MariaDB's silent refusal to drop a column-level check; fixed in the YU17 layer with one MODIFY COLUMN, no wipe
**Found:** 2026-08-27 during a GKE bring-up; hit again 2026-08-28 on the next bring-up
**Class:** non-idempotent migration — fails closed, but only after the volume has state

## The reading

`eod-price-db` never becomes Ready. Its `schema-migrate` init container exits 1 and the Deployment
sits in `Init:Error`, restarting:

    ERROR 1826 (HY000) at line 195: Duplicate CHECK constraint name 'state'

from

    ALTER TABLE trades ADD CONSTRAINT state
      CHECK (state in ('New','Processing','Settled','Cancelled','Rejected'))

## Why it recurs

The migration is written as a first-boot script — plain `ALTER TABLE ... ADD CONSTRAINT`, no
`DROP ... IF EXISTS`, no guard. That is fine on an empty volume and fatal on a populated one.

`eod-price-db` **has a PVC** (`eod-price-db-data`, 10Gi, `standard-rwo`), so the volume survives a pod
restart, a node drain, and a scale-to-zero. The migration therefore re-runs against a schema that
already has the constraint. **Every restart after the first fails**, which means the read model is
one node drain away from being down until someone intervenes.

Worth stating plainly because a comment in `deploy-gke` says the opposite — *"`eod-price-db` has no
PVC, so every restart is a first boot"*. That was true once. It is not true now, and the skill's
advice about the init ConfigMap is written on top of the assumption.

## The workaround used (twice), which is NOT a fix

Give it a clean first boot:

    kubectl -n traderx scale deploy eod-price-db --replicas=0
    kubectl -n traderx wait --for=delete pod -l app=eod-price-db --timeout=120s
    kubectl -n traderx delete pvc eod-price-db-data
    kubectl -n traderx wait --for=delete pvc/eod-price-db-data --timeout=180s
    kubectl apply -f specs/YU17-otc-rates/generation/kubernetes/cluster/gke/eod-price-db.yaml -n traderx
    kubectl -n traderx scale deploy eod-price-db --replicas=1

Two things about it that are not optional:

- **`wait --for=delete` on the PVC, not just the delete.** A delete *response* is not proof; a
  re-bind race can resurrect the old volume and the migration fails again against the same state,
  which reads as "the wipe didn't work" rather than "the wipe wasn't finished".
- **Re-apply the manifest.** Nothing recreates a *Deployment's* PVC — that is a StatefulSet
  behaviour. Deleting it and scaling back up leaves the pod `Pending` on a missing claim, and the
  error names the claim rather than the mistake.

**Check the init ConfigMap before letting it reinitialise.** A fresh boot runs `database-init-sql` as
it exists *at that moment*. If a stale ConfigMap is in the namespace the read model initialises at
`DECIMAL(18,3)`, and every bond price then rounds on the way in — still books, still shows a
position, wrong by a tenth of a point of par, no error anywhere. Compare against the operative layer
first; on 2026-08-28 both read 16 x `DECIMAL(18,6)` and 2 x `DECIMAL(20,6)`, so it was safe.

## The actual fix

Make the migration idempotent. Line 195 is the one that fires, but the whole script should be read
for the same shape — a single guarded statement leaves the rest of the file untested on the
second-boot path, and the second boot is the only path that matters here.

Either guard each statement, or gate the whole script on a schema-version row it writes on success,
which is the version that also stops a partially-applied migration from being re-attempted from the
top.


---

## Root cause (2026-09-02) — and why the wipe recipe below was never necessary

The migration already contained `DROP CONSTRAINT IF EXISTS state` immediately before the failing
ADD, so on paper it *was* idempotent. It is not, because of a MariaDB behaviour worth knowing:

**MariaDB will not drop a COLUMN-LEVEL check by name, and reports success when it declines.**

`001-initialSchema.sql` declares `state VARCHAR(20) CHECK (state in (...))`. That is a column-level
check, auto-named after the column. Against it:

| statement | result |
|---|---|
| `ALTER TABLE trades DROP CONSTRAINT IF EXISTS state` | **succeeds, removes nothing** |
| `ALTER TABLE trades DROP CHECK state` | `ERROR 1064` — not MariaDB syntax |
| `ALTER TABLE trades DROP CHECK IF EXISTS state` | `ERROR 1064` — not MariaDB syntax |
| `ALTER TABLE trades MODIFY COLUMN state VARCHAR(20)` | **clears it** |

Measured on `mariadb:11.4` against this exact schema, not inferred. The silence is what made it
expensive: a statement that declines and returns success is indistinguishable from one that worked,
so a drop/add pair that is *visibly* idempotent kept passing review while failing every restart.

**The fix** is one line before the existing pair:

    ALTER TABLE trades MODIFY COLUMN state VARCHAR(20);

After the first successful run the constraint is TABLE-level, which `DROP CONSTRAINT` *can* remove,
so the pair is genuinely idempotent from then on and the MODIFY is a no-op.

**No wipe.** The recipe recorded above destroys the volume; it was never needed. On the rig the fix
applied in place and the data survived two consecutive restarts: 25 EOD price sessions, 11 accounts,
25 trades, with the second restart logging `migrations applied` instead of 1826.

**Blast radius, for the next reader:** eod-price-db down takes account-service (MariaDB connect
timeout) and reference-data (`ECONNREFUSED :3306`) into CrashLoopBackOff with it. The visible
symptom is a console whose Account and Ticker dropdowns are empty and whose demo preset selects
nothing — three services away from the actual fault.

**Latent:** eight other inline CHECKs in `001-initialSchema.sql` (`trades.side`, `trades.quantity`,
`orderbook.status`, `eod_price_session.status`, `eod_price.quality`, ...) carry the identical trap.
Any future migration that re-adds one of them by name needs the MODIFY first.

**Propagation:** YU16 carries the same `database-init-configmap.yaml` and is still unfixed.
