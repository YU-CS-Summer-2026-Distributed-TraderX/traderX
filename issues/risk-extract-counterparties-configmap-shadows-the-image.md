# A hand-applied ConfigMap shadows the risk-extract's counterparty reference data

**Status:** rig incident resolved 2026-08-28; recurrence guard added 2026-09-16 for tracked
manifests. Whether the unmounted ConfigMap still exists on a live cluster was not re-checked.

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

## Authoritative source (decided 2026-09-16 from the tracked delivery path)

The spec pack's CSV is the only source, and the image is its only carrier:

1. `specs/YU15-eod-risk-extract/reference-data/counterparties.csv` for YU15 and every later
   state (YU16, YU17 and YU18 carry no copy and inherit it). YU14's copy governs YU14 only.
2. `pipeline/render-state-YU15-eod-risk-extract.sh` copies it into
   `order-matcher/src/main/resources/reference-data/`.
3. `Dockerfile.cluster` copies the built classes to `/opt/app/classes`.
4. `RiskExtractMain` reads `/opt/app/classes/reference-data/counterparties.csv` (and
   `instruments.csv` from the same directory).

No tracked manifest mounts anything there or sets `RISK_EXTRACT_REFERENCE_DATA` at
04403104. To change counterparties, edit the spec CSV, re-render, rebuild the
cluster-node image and roll it. Do not mount a ConfigMap over the file or the directory.
A tracked ConfigMap generated from the CSV was the alternative: it would change
counterparties without a rebuild, at the cost of a second delivery path that has to stay in
step with the image. It was not adopted. Adopting it means changing this guard, not getting
around it.

## Guard

`scripts/ci/check-counterparty-reference-not-shadowed.py` parses manifests with PyYAML (YAML or
JSON) and walks the objects. It fails on a mountPath at, inside or above
`/opt/app/classes/reference-data`, on `RISK_EXTRACT_REFERENCE_DATA` being set, and on a
`counterparties.csv` ConfigMap key or kustomize generator source. Kustomize patches are parsed
as well, inline (`patch:`, `patchesStrategicMerge`) or by file (`patches[].path`,
`patchesJson6902[].path`). A patch that cannot be parsed or found fails, and so do `replacements`
or `vars` that touch a mountPath, since their effect needs a render. Empty, malformed or
object-free input fails instead of passing. With `--rendered`/`--state` it also requires the rendered
CSV to match the lineage's spec copy byte for byte. The `counterparty-reference` job in
`engine-tests.yml` runs the self-test and scans `specs/*/generation/kubernetes`.

    python3 scripts/ci/check-counterparty-reference-not-shadowed.py specs/*/generation/kubernetes
    python3 scripts/ci/check-counterparty-reference-not-shadowed.py \
      generated/code/target-generated/*/runtime/kubernetes \
      --rendered generated/code/target-generated/order-matcher/src/main/resources/reference-data/counterparties.csv \
      --state YU18-risk-integration

The 2026-08-21 incident was an object applied by hand, which no repository check can see.
Only a live read catches that, and live mode fails unless the stream actually contains that
Deployment with containers:

    kubectl -n traderx get deploy risk-extract -o json \
      | python3 scripts/ci/check-counterparty-reference-not-shadowed.py --live-deployment risk-extract -

It reads the Deployment only; a mount injected by a webhook shows on the Pods, not here.

Bring-up step 3f in `scripts/yu15/bring-up-gke.sh` reads the file the pod serves, so it
reports what a shadowing mount serves and cannot tell that apart from the image copy.

## Still open

- **The ConfigMap object** was left on the rig on 2026-08-28, unmounted and unreferenced.
  The live cluster was not inspected for this update.
- **The live read above has never run against a cluster.** It was exercised on `kubectl
  kustomize` output of the YU17 gke tier and on a copy of its risk-extract manifest with the
  incident mount re-inserted (fails), not on a live object.
- A fresh bring-up from the tracked manifests was never broken: it has no such mount.
