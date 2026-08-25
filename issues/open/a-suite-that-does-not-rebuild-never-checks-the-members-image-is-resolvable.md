# A suite that does not rebuild never checks the members' image is resolvable

**Found 2026-08-25** on `kind-traderx-yu12-cluster`, after a node-level tag cleanup left the
members' image content present on every node under **no name**. Filed rather than fixed: the
remedy touches `run-proofs.sh`, and that file was being executed by another lane's suite at the
time of writing.

> **The gap this issue names is** *"a suite that does not rebuild never verifies the members' image
> is resolvable"*. It is **NOT** *"this check would have caught the incident below"* — it would
> not have, because nothing rebuilt after the removal. The distinction is the whole point: an issue
> that overclaims its own fix gets the fix wrong.

## What happened

`kind load docker-image` imports content under kind's own reference and any tag it is given, and
containerd **dedupes by content** — so two tags on the same bits are two names on one image record,
not two images. A throwaway tag created for an arm check (`:yu17-format8-armcheck`, a `docker tag`
of `:yu17-format8`, then `kind load`) therefore shared a record with the tag the rig actually runs.

Tidying the throwaway tag away at node level took the real tag with it. The content stayed —
running containers held it — but the name did not:

- members 0 and 1 kept running, because their containers already existed and **the tag only matters
  when a pod starts**;
- member 2 restarted into `ImagePullBackOff` — *"pull access denied, repository does not exist"* —
  against an image whose bytes were sitting on that very node;
- four proofs failed downstream of it.

*(The pod-level symptoms, the `repoTags: []` reading and the recovery are as reported by the lane
that hit and repaired them; the commands and outputs below are first-hand.)*

## The mechanism: `docker rmi <name>` and `crictl rmi <name>` are not the same verb

Same intent, same object, one command per tool, both naming a **tag** and neither naming an image
ID or passing `-f`:

```
$ docker rmi traderx/cluster-node:yu17-format8-armcheck
Untagged: traderx/cluster-node:yu17-format8-armcheck            <- and NO "Deleted:" line

$ docker exec <node> crictl rmi docker.io/traderx/cluster-node:yu17-format8-armcheck
Deleted: docker.io/traderx/cluster-node:yu17-format8-armcheck   <- x4, one per node
```

**That asymmetry is the durable artefact here, more than the incident.** `docker` untagged and kept
the content, correctly name-scoped, because `:yu17-format8` still referenced it. `crictl` reported
a *deletion* — it has no untag concept at all. So the reflex "remove by name, never by ID" is not
protective here: **the removal was by name, and it still took every name.**

Stating the claim so it can be killed: `ctr images tag` creates a **separate image record per
name** pointing at the same target (containerd has no multi-name record); the CRI plugin aggregates
those by image **ID** (config digest); and `RemoveImage` resolves the given ref to that ID and then
deletes **every reference carrying it**.

## Not verified — this is a hypothesis with a test, not a finding

The mechanism above is **inferred from the two outputs and from CRI semantics, and has not been
executed.** The test below has no blast radius: it uses content that is *already* unnamed, so the
worst case leaves it unnamed, and removing a name cannot reclaim content a running container holds.

```bash
N=traderx-yu12-cluster-worker2
B=sha256:<one of the already-unnamed blobs listed under Corroboration>

docker exec $N ctr -n k8s.io images tag $B tmp/nametest:a
docker exec $N ctr -n k8s.io images tag $B tmp/nametest:b

# PRECONDITION -- crictl must see BOTH, or the removal below proves nothing
docker exec $N crictl images | grep nametest        # expect 2 rows; 0 or 1 -> STOP, inconclusive

docker exec $N crictl rmi tmp/nametest:a
docker exec $N crictl images | grep nametest
#   b present              -> name-scoped; the mechanism above is WRONG
#   b gone                 -> record-scoped; confirmed
#   a still there / ENOENT -> crictl never resolved it; THE TEST DID NOT RUN

docker exec $N ctr -n k8s.io images rm tmp/nametest:a tmp/nametest:b 2>/dev/null || true
```

**The third branch is not a nicety.** The CRI image store is the plugin's own view, populated at
startup and from containerd events; a blob unnamed for days is not in it, and a `ctr images tag`
may not surface in `crictl` before the removal runs. Without the precondition assertion, "no such
image" is indistinguishable from "b survived", and the test reports a **refutation it did not
earn** — the same defect as a pass that was not earned, and harder to notice, because a refutation
feels like rigour.

**One observation the hypothesis does not yet explain**, recorded rather than smoothed over: the
content was left resolvable under kind's own import reference (`docker.io/library/import-2026-08-25`,
which the members' pods already carried as their `imageID` *before* any cleanup) while the
`traderx/...` tag was gone. If `RemoveImage` deletes every reference for an ID, that survivor needs
an account — a repo *digest* rather than a repo tag is the obvious candidate. The test above does
not settle it.

## The ruling: leave node-level tags alone

**They cost a name and no bytes.** On this rig `docker tag` + `kind load` *guarantees* content
sharing, so a throwaway tag is never safely removable at node level, and no amount of care in
choosing the reference form changes that. Daemon-side `docker rmi <name>` remains fine — it is
genuinely name-scoped, as the output above shows.

Re-running `kind load` for the real tag afterwards restores the name, but that is a **recovery
step, not the rule**: repairs get forgotten, and a rule that can be half-followed is not one.

## Corroboration: it has happened before, silently

`traderx-yu12-cluster-worker2` carries **three further pieces of unnamed content** —
`cd116ff5345cf`, `b69bc29a9e385`, `1cea996d7fe7e` (reported by the lane that inspected the node).
Nothing noticed, because those three are unused: **name-stripping is invisible until the stripped
name is the one a pod needs**, which is exactly why the live one sat unnamed until a member
happened to restart.

## The gap, and why it is a separate fact

`ensure_image_on_nodes` is called from **exactly one place**:

```
scripts/yu15/run-proofs.sh:367   ensure_image_on_nodes() { ... }
scripts/yu15/run-proofs.sh:520     ensure_image_on_nodes "${image}"   <- inside rebuild_fresh_epoch, and nowhere else
```

So the only moment the runner asks "is the members' image actually resolvable on these nodes?" is
while it is minting a fresh epoch. A suite that finds the rig already on the baseline image mints
nothing, checks nothing, and runs to completion against a rig whose image name may not resolve —
discovering it only when something restarts a member, which on this suite is late and destructive.

Again: **this would not have caught the removal above**, because the removal happened after the
last rebuild. What it explains is why the next lane inherited an unrunnable rig **silently** rather
than at the first thing that looked.

## Remedy, not taken here

Hoist the resolvability check out of `rebuild_fresh_epoch` and run it in the baseline block beside
the existing image pins for the gateway, `risk-extract` and `feed-adapter` — the block whose stated
lesson is already *"check every Deployment that runs the cluster-node image, not the StatefulSet
alone"*. The reading has to be that the **name resolves on every node**, not that the spec carries
the right tag: the spec was right throughout this incident.

## Related

- [`the-manifests-pin-a-build-the-rig-no-longer-runs`](the-manifests-pin-a-build-the-rig-no-longer-runs.md)
  — the sibling failure in the other direction: there the tag was wrong and the content fine; here
  the tag was right and the name absent.
- [`../resolved/a-fresh-epoch-strands-the-feed-adapter-and-only-a-new-pod-recovers-it`](../resolved/a-fresh-epoch-strands-the-feed-adapter-and-only-a-new-pod-recovers-it.md)
  — same family of invisibility: a rig defective in a way every ordinary reading reports as healthy.
