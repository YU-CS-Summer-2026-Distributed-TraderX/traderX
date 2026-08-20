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

**What this does NOT change:** the "real read endpoint" gap of §2. The console still reaches the
sink through the dev-proxy `kubectl exec` bridge on kind, and still reads GCS directly on a deployed
console. The cuts merely now survive long enough to be worth reading — which was the live demo risk,
and is what §1 was actually about.
