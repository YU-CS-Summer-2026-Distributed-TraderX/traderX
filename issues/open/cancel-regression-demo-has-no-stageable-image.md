# `yu13-cancel-ingress` has no image that can stage its regression demonstration

> **The values below are a record, not a rig you can query.** Order refs (`1-66`), trade ids
> (`4060-S`), trace ids, security ids, pod names and run counts come from the epoch this was
> measured on. That epoch has been rolled and will be rolled again — order refs restart at 1, the
> symbol table is renumbered, trace ids follow the client order ids of a run that no longer exists.
> Read them as a worked example of the SHAPE. Do not look them up, and do not treat their absence
> on a current rig as evidence about this issue.

**Filed** 2026-08-18 by the coordinator, after trying and failing to restore it. **Open.**
Low severity: the proof's **forward claim runs in full** and passes. What is missing is the
before/after half that shows the pre-fix build could not cancel.

## How it broke

The default `IMAGE_PRE` was `traderx/cluster-node:yu15`. On 2026-08-17 that tag was retagged away by
me — it turned out to hold a YU16-intermediate build rather than a YU15 one, so the name was a lie
(`issues/mislabeled-cluster-node-images`). After that the regression half skipped on every run while
the suite summary line still read **PASS**, which is the coverage-loss-behind-a-green-verdict shape.

## Why it could not simply be repointed

Three requirements, and no local image meets all three. Measured 2026-08-18, each with a control:

| tag | `/cancel` in `ClusterGatewayMain` | probe server (18111) | committed ack |
|---|---|---|---|
| `yu12` | absent | present | **NO** |
| `yu13` | absent | **absent** (crash-loops the kubelet) | - |
| `yu14` | absent | **absent** (crash-loops the kubelet) | - |
| `yu15-pre` and later | **present** (not pre-cancel) | absent / present | - |

**`:yu12` was tried as the default and made things worse.** It rolls and comes up, then step 2 fails
with `{"error":"no committed ack"}` — it is far enough back that its gateway cannot get a committed
ack from today's members. A missing `/cancel` route and a gateway that cannot reach the engine are
indistinguishable from the probe, so the demonstration would be ambiguous even if it did not fail.
It turned a proof that passed its forward half into a red one, and was reverted.

**Marker note, because this cost time twice.** Grep the CLASS, not the image: `OrderController.class`
carries the string `/cancel` in every build back to `:yu12`, so an image-wide grep reads positive
everywhere and discriminates nothing. Scoping to `ClusterGatewayMain.class` makes it a real test —
`:yu17-fx` positive, `:yu12` negative.

## Current state

The default is deliberately a name that **cannot resolve** (`traderx/cluster-node:precancel-BUILD-ME`),
which routes to the existing skip branch rather than to a failure. That is better than a real-but-
mutable tag: a mutable tag can be rebuilt into something other than what its name says and then stage
a demonstration against the wrong build while looking correct, which is exactly what happened. A name
that cannot resolve can only skip, loudly, carrying its own remedy.

## The fix

Build a pre-cancel image from the commit before the cancel route landed in `ClusterGatewayMain`, tag
it immutably (a date-stamped name, not a state name), and set it as the default here. Verify all
three properties against controls before trusting it — the route absent from the gateway class, the
probe server present, and a committed ack obtainable against current members.

## The wider gap this sits on

The suite summary has only PASS / SKIP / FAIL. A proof whose forward half passes while a documented
half cannot stage reports **PASS**, and only its log says otherwise. That is legible to someone
reading the log and invisible to someone reading the roll-up — the same who-reads-it problem booked
in `vacuous-pass-audit`. Worth considering a partial verdict, though not urgent while this is the
only known instance.

## And now the FIX image is gone too (2026-08-27) — the proof fails outright

`traderx/cluster-node:yu15-cancel`, the image this proof rolls to for its forward half, was
**pruned from the local Docker store** during a disk reclaim on 2026-08-27 (the host had filled to
431 MiB free and taken the kind rig down with it). The proof no longer reports a partial pass; it
fails at preflight:

```
=== 0. preflight ===
[FAIL] fixed image traderx/cluster-node:yu15-cancel not present locally
```

**This is worth distinguishing from a regression**, because the failure text names an image and not
a behaviour, and a reader coming to it cold has no way to tell that the engine is fine and the
artifact is missing. Both halves of this proof are now unstageable: `IMAGE_PRE` was already the
placeholder `precancel-BUILD-ME`, and `IMAGE_FIX` has joined it.

Restoring it means building from the commit where the cancel route landed, the same exercise this
issue already describes for `IMAGE_PRE` — so the two are now one job rather than two, which is the
only good news here.

**The general point, which outlives this proof:** a proof that depends on a historical image
depends on a *local Docker artifact that nothing tracks and any reclaim can delete*. The images are
not in the registry, not in git, and not rebuilt by any script under `scripts/` except
`build-stp-boundary-images.sh` (which is exactly why the stp boundary pair survived the same
reclaim — it can regenerate them, and did, in under a minute). **The durable fix for this class is
a build script per historical image**, not a note asking people not to prune.


## CORRECTION 2026-08-27: `yu15-cancel` was not lost, and the FIX half is one line from running

**The image survives on all three kind nodes.** Measured after the reclaim:

    traderx-yu12-cluster-worker{,2,3}: yu15-cancel present (with 13 other historical cluster-node tags)
    docker images: yu15-cancel ABSENT

The prune took **Docker's** copy. It did not touch the **nodes' containerd store**, which is where a
pod actually reads from — so the artifact a pod needs was never gone.

**What is broken is the preflight, not the availability**:

    337  docker image inspect "${IMAGE_PRE}" ...
    341  docker image inspect "${IMAGE_FIX}" ... || fail "fixed image not present locally"
    350  kind load docker-image "${_img}" ...

**It tests Docker's store, then loads to the nodes.** When the node already holds the image, the load
is redundant and the check fails on a precondition that is already satisfied by another route — **a
check testing the MECHANISM (is it in Docker?) rather than the PROPERTY (can a pod run it?)**, which
is this project's most-repeated defect shape, here in a preflight.

**Fix**: treat presence on the nodes as satisfying the precondition — probe
`crictl images` on a node and skip the `kind load` when it is already there. That restores the FIX
half immediately, with no rebuild.

**The IMAGE_PRE half is unaffected by this correction** — it was already the `precancel-BUILD-ME`
placeholder and remains genuinely absent, so the proof stays partial for the reason this issue
originally described.

**And the general point survives intact and is strengthened**: a proof depending on a historical
image depends on a local artifact nothing tracks. It happened to survive on the nodes this time. **A
build script per historical image is still the durable fix; "it was still on the node" is luck.**

## CORRECTION (2026-08-27, later the same day): the section above named the wrong image

**`traderx/cluster-node:yu15-cancel` was never lost.** It is on both kind nodes and always was; the
disk reclaim pruned it from *local Docker* only. Measured:

```
traderx/cluster-node:yu15-cancel          local docker: absent   kind nodes: PRESENT (both)
traderx/cluster-node:precancel-BUILD-ME   local docker: absent   kind nodes: absent
```

So the proof did not fail because its fix image was gone. **It failed because its preflight
inspected local Docker, which is not the surface that matters** — the members are rolled onto an
image on the *nodes*, and `kind load` two lines below the guard is deliberately tolerant of a
failed load for exactly that reason (*"assuming it is already on the nodes"*). **The guard was
stricter than the mechanism it guarded**, and refused over an image the proof could have used.

Fixed: the preflight now accepts either surface, via an `image_available` helper that checks local
Docker and then every `${CLUSTER}-` node's `crictl images`. Self-tested against four tags including
a known-absent negative control, so the helper is demonstrably able to say no.

**What actually remains missing is `IMAGE_PRE`** — `precancel-BUILD-ME` is a placeholder name that
nobody ever built, exactly as the comment above it in the proof has always said. **That is what
this issue is about and it is unchanged**: the regression demonstration still cannot be staged, and
restoring it still means building from the commit before the cancel route landed. The proof's
forward half, which needs no pre-image, should now run.

**The lesson is the one the day kept repeating**, and this instance is the cheapest to state:
`docker images` answered honestly about local Docker and I read it as an answer about the rig.
**A confident absence from the wrong surface is indistinguishable from a real one** — and it cost a
filed conclusion that would have sent someone to rebuild an artifact that already existed.

## The missing image also makes the strip/restore path UNTESTABLE, which is a second cost

Noticed 2026-08-27 when the proof passed for the first time since the reclaim. The passing run left
the gateway's probes intact — and **that is not evidence the restore works.** `IMAGE_PRE` was
absent, so the regression arm skipped, so the deployment was never patched, so the probes were
never stripped. Finding them intact afterwards is a pass with nothing behind it.

That matters more than it sounds, because **the strip/restore path is the one that has already
latched damage**: on 2026-08-14 an aborted hand-run left the probes stripped, and the two full
suites afterwards faithfully restored them *stripped*, each reporting a successful restore, until
`yu16-liveness-restarts-wedge` failed three proofs earlier with a true statement about a rig the
proofs themselves had broken. A guard was added (a capture missing either probe is read as evidence
of that abort, and the manifest form is the floor) — **and that guard has never been exercised,
because exercising it requires the roll that requires `IMAGE_PRE`.**

So building the pre-cancel image buys two things, not one:

1. the regression demonstration this issue is named for, and
2. **the only route to exercising the probe strip/restore path and its anti-latch guard.**

Until then, every green run of this proof is green on the forward half alone, and the restore path
is carried on a 2026-08-14 fix that no run since has been able to test.
