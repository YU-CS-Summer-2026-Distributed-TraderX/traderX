# `rebuild_fresh_epoch` reports success after a rollout that timed out

**Filed** 2026-08-17 by the coordinator, from a live bring-up. **Open.** Small fix, real consequence.

## What happened

Minting a fresh epoch on a newly built image (`traderx/cluster-node:yu17-fx`), `run-proofs.sh` printed:

```
[baseline] cluster is on traderx/cluster-node:yu17-ackfix; rebuilding on traderx/cluster-node:yu17-fx at a fresh epoch
error: timed out waiting for the condition
error: timed out waiting for the condition
[baseline] cluster now on traderx/cluster-node:yu17-fx, fresh epoch
```

**The last line is false.** At that moment all three members were in `ImagePullBackOff`: the image
existed only in the local Docker daemon and had never been `kind load`ed, so the nodes tried Docker Hub
and got `pull access denied`. Zero members were running. The PVCs had already been wiped, so the old
epoch was gone too — the rig was down, and the message said the epoch was up.

## Mechanism

In `scripts/yu15/run-proofs.sh`, `rebuild_fresh_epoch()` runs:

```bash
${K} rollout status statefulset/order-matcher-cluster --timeout=600s >/dev/null
...
${K} rollout status deployment/cluster-gateway --timeout=600s >/dev/null
```

Only **stdout** is redirected, so the failure text reaches the terminal (which is how this was caught),
but the **exit status is discarded** — the function keeps going and the caller prints its success line
unconditionally. The two stray `error:` lines are the only evidence, and they appear *above* a
reassuring message, which is the worst possible ordering for a reader skimming a long run.

## Why it matters more than a cosmetic message

This is the vacuous-pass shape applied to bring-up rather than to a proof. Every proof that follows
would run against whatever the cluster happens to be, while the run's own log asserts a fresh epoch on
a named image. Here it failed loudly and immediately because nothing was running at all. The dangerous
variant is a **partial** rollout: two members up, one wedged, `rollout status` times out, the message
still says fresh epoch, and the proofs go on to describe a two-member cluster truthfully.

Note the related asymmetry: the harness *does* check the gateway's and risk-extract's images
separately, and those checks are well commented precisely because a stale one silently poisoned a whole
run before. The member rollout has the same failure mode and no check.

## Fix

Gate on the fact rather than on the command returning:

1. Let `rollout status` fail the function (`|| fail ...`), rather than discarding its status.
2. Better, additionally assert the end state the way `prove-cluster-engine-change` §1 already
   prescribes: every member pod on the target image **and** ready, before anything downstream runs.
   That check exists in the skill and is not wired into `rebuild_fresh_epoch`.
3. Print `[baseline] ... fresh epoch` **only after** that assertion passes.

## Second, smaller gap found in the same run

`run-proofs.sh` repins images but never `kind load`s, while `start-cluster-kind.sh` does. Naming a
`CLUSTER_IMAGE` that exists only in the local daemon is therefore an easy and silent mistake — the
operator gets `ImagePullBackOff` several minutes later rather than an immediate, actionable refusal.

A preflight in `run-proofs.sh` would close it: when `CLUSTER_IMAGE` differs from the running image,
check the image is present on the kind nodes (or just `kind load` it, which is idempotent) **before**
scaling to zero and wiping the PVCs. Ordering matters — the current failure destroys the existing epoch
first and discovers the problem afterwards, so there is nothing to fall back to.

## Provenance

Found while minting the epoch that first exercised `scripts/proofs/yu17-fx-credit.sh` (which then
passed, all eight arms). Both gaps are in the harness, not in the FX change.
