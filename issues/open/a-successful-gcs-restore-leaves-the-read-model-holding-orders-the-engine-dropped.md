# A successful GCS restore leaves the read model holding orders the engine dropped

**Filed 2026-08-27** on YU17, out of the ADR-072 proof-repair work. **This is a gap in the DR story,
not a defect in a proof** — it was found while deciding which instrument `yu12-gke-restore-from-gcs`
should use, and it is filed separately because its audience is whoever owns disaster recovery, not
the next proof author.

## The claim

`yu12-gke-restore-from-gcs` proves the cluster comes back at exactly the last backup point: the
post-backup orders (state `S+`) are gone from the engine, correctly, and are the honestly-stated RPO
loss window.

**Nothing restores the read model alongside it.** `trade-processor`'s database is a separate
artifact with its own lifecycle and is untouched by `RESTORE_FROM_GCS=1`. Read from the manifest
rather than assumed — `gke/statefulset-emptydir.yaml`, the `restore-from-gcs` init container:

* it mounts **only** the member's `data` volume (`volumeMounts: [{name: data, mountPath: /data}]`);
* it exits immediately unless `RESTORE_FROM_GCS=1` **and** the ordinal is `0` ("member $ORD rejoins
  fresh (only member-0 restores)");
* its whole body is a `download_file(... 'yu12-cluster-snapshots/latest.tgz' ...)` followed by
  `tar -xzSf /tmp/latest.tgz -C /data`.

There is no step that touches the read model, and no other workload participates. So after a
*successful* restore:

```
engine      has dropped the S+ orders     (correct — that IS the restore)
read model  still shows the S+ orders OPEN (nothing told it otherwise)
```

A client enumerating its open orders is told about orders the venue does not hold and will never
fill or cancel. **The divergence is silent, permanent until someone reconciles, and it is the
outcome of the DR path WORKING**, not of it failing.

## Why it was not visible before

The RPO window has always been stated as a count of lost orders — "the N post-backup orders are the
loss window" — which is a statement about the ENGINE. Read as a whole-system claim it quietly
implies the rest of the system agrees, and no proof or runbook step checked that. The proof could not
have caught it either: it reads the engine's own snapshotted counters, which is the correct
instrument precisely *because* the read model does not move with the restore.

That inversion is the thing worth remembering. `lib-consensus-readings.sh` ranks "ask the order"
(the read model) as the FIRST repair to reach for, and for this one proof it is the WRONG instrument
— an identity claim read from it would report the `S+` orders present and call a correct restore a
failure. **The same fact that disqualifies the read model as an instrument is the gap**: it does not
move with the restore. One is a methodological note, the other is a DR defect, and the first was
written down weeks before anyone stated the second.

## Scope, honestly

**Unverified on any tier**, because the DR path itself is unexercised: `yu12-snapshot-backup` is not
deployed on the yu17 bench cluster, and the restore also needs the GCS HMAC secret and bucket from
the 2026-07-19 drill. This is read from the manifests and the restore path, not from a run.

The 2026-07-19 drill would have had the same divergence and would not have surfaced it — it verified
the four engine quantities, which is exactly the half that is correct.

## What would close it

Not decided here, and the choice is a real one:

- **Reconcile after restore.** `ClusterRecon` already exists and already speaks this tier's ids
  (`<epoch>-<orderRef>`, `<tradeSeq>-<B|S>`); a post-restore reconciliation pass is the smallest
  change that makes the read model agree.
- **Wipe the read model with the cluster and let it rebuild** from the restored engine's egress —
  clean, and only correct if the projection can be rebuilt from a restored log, which needs checking
  rather than assuming.
- **Or state it as accepted risk in the runbook**, with the reconciliation left manual — legitimate,
  but only once it is written down, which is the minimum this file asks for.

Whichever, the RPO statement should stop being a single number: **the engine's loss window and the
read model's stale window are different quantities**, and today only one of them is quoted.

## Related

- `scripts/proofs/yu12-gke-restore-from-gcs.sh` — its `[PASS]` banner now names this, so a reader
  citing the green sees it; and its header explains why the read model must not be its instrument
- `issues/open/five-gke-proofs-read-a-global-counter-that-replayed-flow-now-moves.md` — the work this
  came out of
- `docs/handoff/PROOF-yu12-gcs-backup-restore-2026-07-19.md` — the drill that predates the question
