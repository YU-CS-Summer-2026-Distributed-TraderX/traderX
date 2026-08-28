# A hand-applied ConfigMap shadows the risk-extract's counterparty reference data

**Found** 2026-08-28, on the GKE bench rig, while diagnosing why publishing an EOD session left
chain stages 3 and 4 grey.

## What happens

`RiskExtractMain` reads `counterparties.csv` from `RISK_EXTRACT_REFERENCE_DATA`
(default `/opt/app/classes/reference-data`), which is **baked into the cluster-node image**. On the
rig a ConfigMap `risk-extract-counterparties` was mounted *directly over that single file*:

```yaml
volumeMounts:
  - name: counterparties
    mountPath: /opt/app/classes/reference-data/counterparties.csv
```

The ConfigMap was applied by hand on 2026-08-21 and appears in **no tracked manifest** — not
`cluster/risk-extract.yaml`, not the gke overlay, not any script. It carried the 8 original
accounts. `specs/YU15-eod-risk-extract/reference-data/counterparties.csv` has since gained the three
tape-replay accounts (900001–900003), and the image carries all 11.

So the extract failed closed on every publish:

```
RISK-EXTRACT trigger sessionDate=2026-08-28 version=24
RISK-EXTRACT-FAILED java.lang.IllegalStateException:
  risk extract: account 900001 has no counterparty mapping in reference data
```

Replayed TAQ flow (ADR-072) trades on accounts 9000xx, so once any of it reaches the live venue
every subsequent EOD extract fails until those accounts are mapped.

## Why it took a while to see

Three separate things each looked like something else:

1. **The console reported it as waiting.** Stage 3 said `remote` and stage 4 said `pending` — a cut
   was never produced, so both were literally true and both read as "still going". The bucket and
   the volume can only say whether an artifact EXISTS; neither can tell a run that failed from one
   that never happened. Fixed: the chain endpoint now reads the extract's own log verdict, the same
   way the PnL stage already reads position-service's, and reports `failed`.
2. **Rolling the image did nothing**, because a file-level ConfigMap mount shadows the image's copy.
   The pod's image digest matched a locally-inspected image whose CSV had all 11 rows — the pod
   still served 8. Two identical digests, two different file contents, and the mount is the only
   thing that explains it.
3. **`kubectl apply` would not remove the mount.** Strategic-merge patches merge lists by name, so
   dropping an entry that was never in `last-applied-configuration` is a no-op — apply reported
   "configured" and rolled nothing. It needed `kubectl patch --type=json` with an index removal.

## Resolution on the rig

The mount and volume were removed (`--type=json`), so the pod now reads the image's CSV, which is
the tracked source of truth and already correct. JetStream had never acked the failed delivery, so
it redelivered on its own and the cut succeeded with no operator action:
`gs://traderx-505400-risk-extracts/2026-08-28/v24/seq-50052.{csv,cut}` + contracts, 70 rows.

## Still open

- **The ConfigMap object itself still exists** on the rig, now unmounted and unreferenced. Left in
  place rather than deleted, since nothing reads it and deleting it is not this change's business.
- **Decide where counterparty data should live.** A ConfigMap is a reasonable way to change
  counterparties without rebuilding an image — but only if it is *tracked*, so it moves when the
  CSV moves. Untracked, it is a second source of truth that silently goes stale. Either add it to
  `cluster/risk-extract.yaml` generated from the CSV, or keep the image as the only source and
  never mount over it again.
- A fresh bring-up from the tracked manifests was never broken: it has no such mount.
