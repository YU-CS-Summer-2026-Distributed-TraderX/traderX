# The console's extract source is ephemeral on kind, and the "real read endpoint" gap is kind-only

**Found 2026-08-19 by the coordinator**, checking the console lane's recorded OPEN item ("the sink is
only reachable through a dev-proxy `kubectl exec` — a deployed console needs a real read endpoint")
before it gets handed to someone as a work item. Two facts change what that work item should say.

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
