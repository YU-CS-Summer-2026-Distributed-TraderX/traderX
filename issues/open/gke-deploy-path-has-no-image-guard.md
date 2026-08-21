# The GKE deploy path has no image guard — and four bring-up preconditions that do not exist yet

> **The values below are a record, not a rig you can query.** Order refs (`1-66`), trade ids
> (`4060-S`), trace ids, security ids, pod names and run counts come from the epoch this was
> measured on. That epoch has been rolled and will be rolled again — order refs restart at 1, the
> symbol table is renumbered, trace ids follow the client order ids of a run that no longer exists.
> Read them as a worked example of the SHAPE. Do not look them up, and do not treat their absence
> on a current rig as evidence about this issue.

**Status: open, deliberately parked with the next GKE bring-up.** Not unowned by oversight — see
"Why this is parked" below. Opened 2026-08-17 by the coordinator
(`local_9cb195ed-56f3-4dad-b87e-ac19060f971a`) from the instrument lane's handover notes.

---

## 1. The defect

The derive-or-refuse machinery (`scripts/yu15/lib-state-image.sh`, landed `cc9fea80`/`98db8366` and
carried) resolves the cluster image from the state's own spec-pack rank, and **refuses** rather than
silently running an ancestor's build. It is consumed by exactly two callers:

- `scripts/yu15/start-cluster-kind.sh`
- `scripts/yu15/run-proofs.sh`

**Both are kind-only.** A GKE deploy goes through `kubectl kustomize` / the `deploy-gke` skill and has
**no equivalent guard**, so the identical silent-wrong-build hole is open there — and from a worse
starting position, because the `gke/` layer's five `cluster-node` refs name **`:yu15-idempfix`** today.

A YU16/YU17 tier on a YU15 core comes up **healthy** and prices bonds on the equity grid, because
ADR-060's ticker-derived grid is the one real engine change bonds needed. Nothing errors; the numbers
are just wrong. That is strictly worse than an `ImagePullBackOff`, which at least announces itself.

## 2. Design notes from the instrument lane (the lane that built the kind guard)

Recorded verbatim in substance, because they are the expensive part:

- **The kind guard works because `start-cluster-kind.sh` is a chokepoint every bring-up goes through.**
  The GKE path's equivalent chokepoint is **`deploy-gke`, not `kustomize`** — kustomize can be invoked
  directly and always will be. So the guard belongs in the deploy script.
- **It must check the *rendered* image refs, not the manifest text**, since the rendered output is what
  reaches the cluster. `kubectl kustomize | grep -oE 'us-east1-docker[^ ]+'` is the whole input.
- **The hard part is deciding what the correct answer IS**, for a tier that deliberately runs an
  ancestor's core — which is exactly the situation those five refs are in. **A naive "must match the
  state tag" guard would refuse the correct configuration.**

## 3. Why this is parked rather than assigned

Two reasons, and the second is the real one:

1. **It cannot be exercised.** GKE is scaled to zero and blocked on the 64-vCPU quota request. A guard
   landed now would sit in the "landed but not exercised" bucket, which is where this project's
   regressions hide.
2. **Its specification depends on a decision that does not exist yet.** "What should the correct image
   be for a tier deliberately running an ancestor's core?" is the *same* question as whether the five
   `cluster-node` refs move off `:yu15-idempfix` — and that is an **engine roll** (mixed-version windows
   diverge members permanently; safe change = scale to zero, wipe PVCs, fresh epoch), so it belongs to
   whoever performs the bring-up. Writing the guard first would mean guessing its own acceptance
   criterion.

## 4. Bring-up preconditions measured 2026-08-17 — none of these exist on `traderx-505400`

Found while resolving the bucket move (`22c44637` / `e801c36d`). The deploy will fail at write time
without them, and the failures are late and confusing rather than early and loud.

| precondition | state on `traderx-505400` |
|---|---|
| `gs://traderx-505400-risk-extracts` | **exists** (created 2026-08-17, holds the replicated cuts) |
| `gs://traderx-505400-order-matcher-journal-archive` | **exists** (created 2026-08-17, empty) |
| A service account for the sink | only the default `848490922808-compute@…` |
| **HMAC keys** for that SA | **none — `gcloud storage hmac list` is empty** |
| k8s secret `order-matcher-journal-gcs-hmac` | not created |
| `objectCreator` (+`objectViewer`) on both buckets for that SA | not bound |

**The sink authenticates with an HMAC key pair, not workload identity** — secret
`order-matcher-journal-gcs-hmac`, keys `access-key-id` / `secret-access-key`, shared by the
journal-archive backup cronjob, the restore init path, and `RiskExtractGcsSink`. An earlier coordinator
note said "workload identity"; that was wrong and this table replaces it.

## 5. What is NOT true any more, and must not be quoted

The YU15 write-once guarantee **does not hold on 505400 yet**. On 501015 the bucket granted the HMAC's
SA `objectCreator`+`objectViewer` only — no delete — so overwrite was impossible *even with those
creds*. On 505400 the buckets exist but nothing is bound, so **only the client-side half survives**:
`RiskExtractGcsSink`'s `x-goog-if-generation-match: 0` no-clobber precondition, which is a
correctly-behaving-writer guarantee, **not** an IAM one. `gke/risk-extract.yaml` states this at the sink
itself, deliberately, because that is the line a reader checks. Re-proving 403-on-overwrite goes with
the bring-up.
