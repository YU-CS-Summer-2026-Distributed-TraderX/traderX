# The console's extract source is ephemeral on kind, and the "real read endpoint" gap is kind-only

**Found 2026-08-19 by the coordinator**, checking the console lane's recorded OPEN item ("the sink is
only reachable through a dev-proxy `kubectl exec` — a deployed console needs a real read endpoint")
before it gets handed to someone as a work item. Two facts change what that work item should say.

> **§1 RESOLVED on YU17 2026-08-19; §2 still open and deliberately so.** The kind sink is now a
> PVC, so a reschedule no longer erases the epoch's cuts. §2 — no Service, no ports, nothing to
> route to — is unchanged, and the ruling at the bottom of this file stands: do not give a
> deliberately-headless component an ingress to solve a kind-only problem. See "What changed" below.

## 1. The cut artifacts are on an `emptyDir`. A pod restart erases every cut.

`specs/*/generation/kubernetes/cluster/risk-extract.yaml`:

```yaml
volumes:
  - name: extracts
    emptyDir: {}
```

This is **deliberate**, and the manifest says so one field above:

> `file://` on kind; the GKE overlay sets a `gs://` URI plus the HMAC secret pair and nothing else
> changes.

So durability of the artifact is GCS's job in the real deployment, and on kind there is none. Not a
defect — but a **live demo risk**, because the failure is silent: reschedule the risk-extract pod and
the provenance panel goes empty with no error anywhere. Every cut currently on the rig
(`2026-08-20/v1..v3` plus `2026-08-18/v33`) postdates the restart that fixed the NATS durable loss;
nothing older survived.

**Before a demo: take a fresh cut, and do not restart `deploy/risk-extract` afterwards.**

## 2. There is nothing to route to. risk-extract has no Service and no container ports.

```
kubectl get svc      -> no risk-extract service
kubectl get deploy risk-extract -o jsonpath='{...containers[0].ports}'   -> empty
```

It is a headless worker: NATS in, files out. So "add an edge-proxy route" — the cheap move that
worked for the member `/health` endpoints — **does not apply here**. Serving the sink over HTTP means
adding an HTTP surface (a server in the service, or a sidecar), which is a real change to a component
that currently has no ingress at all.

## Which reframes the work item

**On GKE the gap does not exist.** The sink is `gs://traderx-…-risk-extracts`, so a deployed console
reads object storage directly — already a URL, already durable, already the transport the external
deliverable uses. The read-endpoint problem is an artifact of the kind sink being a local directory.

So the honest work item is *not* "build a file-serving endpoint for risk-extract". It is:

- **deployed console → read the GCS bucket**, the same source the external consumer uses; and
- **local/kind console → keep the dev-proxy `kubectl exec` bridge**, which is the right shape for a
  dev bridge and costs nothing.

Building an HTTP file server into risk-extract would solve only the local case, at the cost of giving
a deliberately-headless component an ingress. Worth not doing by default.

---

## What changed, 2026-08-19 — §1 only

`specs/YU17-otc-rates/generation/kubernetes/cluster/risk-extract.yaml` now backs the `extracts`
volume with a `PersistentVolumeClaim` (`risk-extract-extracts`, 1Gi, RWO) instead of an `emptyDir`.
Volume name and mountPath are unchanged, so nothing else in the manifest or the service moved. The
Deployment's existing `strategy: Recreate` is what makes ReadWriteOnce safe here — no rollout ever
overlaps two pods on the claim, which is the same reason the strategy was there in the first place.

**The `gke/` overlay was not touched, and could not have been:** `specs/YU17-otc-rates/` carries no
`gke/` variant at all, so YU16's `gke/risk-extract.yaml` remains the operative GKE layer. Its
`gs://traderx-505400-risk-extracts` sink is untouched and `kubectl kustomize` still returns rc=0 on
the YU15 and YU16 gke overlays. Durability there is still GCS's job, as designed.

**Verified on `kind-traderx-yu12-cluster`.** Two fresh cuts taken (`2026-08-20/v4/seq-20196`,
`v5/seq-20209`), sha256 recorded for all six artifacts, `kubectl delete pod -l app=risk-extract`,
replacement pod confirmed to be a different pod (`…-4pzd9` → `…-zcbq7`), and every one of the six
artifacts present afterwards **byte-identical**. On an `emptyDir` all six would have been gone.

The four cuts that predated the change were destroyed once, in the transition, as expected when the
volume type changes. That was the last time a reschedule can do it.

### Confirmed from a second, independent read path

The console lane re-verified through its own dev-proxy `kubectl exec` bridge and node code — a
different route and different code from the in-pod `sha256sum` used above. Both cut shas matched
(`68ec031c583bce88…`, `64c0b2ec176e1656…`), and a third cut produced afterwards by another lane's
proof run reproduced green too, so the reproducibility check passed on data neither party generated
for the other. It repeated the durability test independently — nine artifact shas, `delete pod`,
genuinely different replacement pod, nine shas identical — with a **non-empty guard on both
manifests**, because an empty-vs-empty diff prints PASS and means nothing when the failure mode is
silence.

Its `emptyDir` warning banner was **repointed rather than retired**, which is the better outcome and
worth recording as the general shape: the banner's job was explaining an empty sink, and what empty
*means* changed. It used to mean someone had rescheduled the pod; it now means the EOD chain has not
produced a cut. **Empty was ambiguous before and is diagnostic now.**

State the fix no wider than it was measured: **cuts survive a reschedule of `risk-extract`** — not
"cuts are durable". They do not survive the PVC being deleted, and GKE was never affected.

### The four destroyed cuts were archived, and deliberately NOT restored

They were copied out before the transition and are kept only as an archive. Restoring them into the
PVC was offered and **refused by the console lane, correctly**: a provenance store you can inject
artifacts into is not a provenance store. Every row of that panel asserts "the members rendered this
file at this consensus sequence on this volume", and cuts copied in from a scratchpad would render
identically to ones that are true, with nothing on screen able to distinguish them. Their loss is
honest history — they were written to an `emptyDir`, and the transition that destroyed them is the
same one that means it cannot happen again. **Do not repopulate this sink by hand.**

**What this does NOT change:** the "real read endpoint" gap of §2. The console still reaches the
sink through the dev-proxy `kubectl exec` bridge on kind, and still reads GCS directly on a deployed
console. The cuts merely now survive long enough to be worth reading — which was the live demo risk,
and is what §1 was actually about.

## Resolved for the reschedule case — and a principle that came out of it

The `emptyDir` is now a PVC (`risk-extract-extracts`, kind's `standard`/local-path class), mountPath
unchanged at `/data/risk-extracts`. Verified three independent ways: the fix lane's (`jsz` + in-pod
sha), the coordinator's (`delete pod`, 9 artifacts byte-identical), and the console's own read path
from outside the pod, whose shas match the in-pod ones.

Scope the claim precisely: **cuts survive a reschedule of risk-extract.** They do not survive deletion
of the PVC, the volume is node-pinned local-path, and GKE was never affected — its `gs://` sink was
always durable.

### The four pre-transition cuts were deliberately NOT restored

The `emptyDir`→PVC transition destroyed the four cuts that existed before it. They were archived, and
an offer to copy them back into the PVC was **declined by the console lane**, correctly:

> A provenance store you can inject artifacts into is not a provenance store.

Every row in that store asserts *"the three members rendered this, at this sequence, on this volume"*.
Four files copied in from a scratchpad would render identically to ones that are true, and nothing
downstream could tell them apart — including the console's own sha-agreement check, which would go
green on them. **Their loss is honest history.** The archive is the right home for them; the store is
not.

This generalises to anything else fed from the cut artifacts, the external risk-extract deliverable
included: the value of that store is that its contents can only have arrived one way.

### The banner was repointed, not retired

Its job was explaining an empty sink, and what an empty sink *means* changed rather than went away:
before it meant "someone rescheduled the pod"; now it means "the EOD chain has not produced a cut".
Ambiguous before, diagnostic now. The README bullet is struck through and dated rather than deleted,
so the history of the condition stays readable.

## DECIDED 2026-08-21 by yaakov: read GCS when deployed, keep the exec bridge on kind

Confirms the reframing above. NO HTTP file server in risk-extract — that would solve only the local
case, at the cost of giving a deliberately-headless component an ingress.

- **Deployed console** reads `gs://traderx-…-risk-extracts` directly: the same source the external
  consumer uses, already durable, already a URL.
- **Local/kind console** keeps the dev-proxy `kubectl exec` bridge, which is the right shape for a
  dev bridge and costs nothing.
