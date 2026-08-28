# The eod-price-db schema migration cannot run a second time

**Status:** open
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
