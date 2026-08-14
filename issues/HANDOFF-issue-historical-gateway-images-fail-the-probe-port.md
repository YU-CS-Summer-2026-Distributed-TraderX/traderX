# Issue: two proofs cannot roll a pre-YU16 gateway image, because the probes moved ports

**Found** 2026-08-13 while running the suite against YU17. **Fixed** 2026-08-14 on YU15, YU16 and
YU17 — see *Resolution* below. **Independent of YU17** — a consequence of YU16's probe change
meeting historical images, and it affected every state from YU16 onward.

## The fact

YU16 gave the gateway's probes their own HTTP server on their own thread, on
`GATEWAY_PROBE_PORT` (default **18111**), because the order path's 64-thread pool is exhaustible
and a wedged gateway stopped answering `/ready` on 18110 entirely. The manifest's readiness,
liveness **and startup** probes were repointed to 18111 at the same time.

Every gateway image built before that change serves `/ready`, `/live` and `/health` on **18110
only**. Rolled against the current manifest, such a pod comes up, logs `GATEWAY up: http=18110
…`, and is then killed by the kubelet on the startup probe, forever:

```
$ kubectl exec <yu15-cancel gateway pod> -- sh -c 'curl ... localhost:18110/ready; curl ... localhost:18111/ready'
18110/ready=200
18111/ready=000

$ kubectl describe pod <same>
Warning  Unhealthy  (x61)  kubelet  Startup probe failed: Get "http://10.244.2.225:18111/live":
                                    dial tcp 10.244.2.225:18111: connect: connection refused
Normal   Killing    (x4)   kubelet  Container gateway failed startup probe, will be restarted
```

Confirmed per image on 2026-08-14 by grepping `ClusterGatewayMain.class` in each tag (there is no
`javap` in the runtime image — see *The general lesson*). `yu15-pre`, `yu15-cancel` and `yu15-stp`
contain neither `/live` nor `GATEWAY_PROBE_PORT`; `yu15`, `yu17` and `yu17p2` contain both. The
grep was run with a string that cannot exist as its negative control, so it discriminates.

## What it broke

Two proofs roll the gateway to a historical build, and both failed at that step with
`error: timed out waiting for the condition` — a message that says nothing about the cause. Both
images are present on the kind nodes, so it did not look like a missing image; the pod started and
then crash-looped on a probe.

- **`scripts/proofs/yu13-cancel-ingress.sh`** — step 3 rolls the gateway forward to
  `traderx/cluster-node:yu15-cancel`.
- **`scripts/proofs/yu13-stp-and-replace.sh`** — step 1 rolls to `yu15-pre` and step 4 to
  `yu15-stp`, via `roll_to` in the proof and `rebuild_fresh_epoch` in `scripts/yu15/run-proofs.sh`,
  which both repinned the gateway alongside the StatefulSet.

**Both proofs need a historical gateway, and that is not obvious from either one's name.** It was
first read as "cancel-ingress is about the gateway, stp is about the members, so stp's gateway roll
is incidental" — and that is wrong. `yu13-stp-and-replace` proves a **bundle**: its own header says
it "rolls the members *and gateway* forward", and step 3 asserts `POST /replace` **404s on the
pre-change gateway**, which is a statement about the gateway's build, not the engine's. Measured
2026-08-14 by decoupling them and running it: with historical members under a current gateway,
step 3 got `504 {"error":"no committed ack"}` — the route exists, offers the command, and no old
member ever acks it. A timeout is *no answer*, and no answer is not the refusal the step asserts.

So the gateway/member coupling is asserted, not architectural. Checked separately, and the
architectural half is genuinely independent: on `yu15-pre` members, a `yu17p2` gateway seeds
`200 {"seeded":true}` and so does a `yu15-pre` gateway. All the tags carry Aeron 1.51.0, and the
codec's operative layer is YU15, so the symbol-register wire shape is shared.

## Resolution (2026-08-14)

**1. The RUNNER stops repinning the gateway; each PROOF owns its own.** `rebuild_fresh_epoch` in
`scripts/yu15/run-proofs.sh` dropped its `set image deployment/cluster-gateway`. It still
`rollout restart`s the gateway, which is the part that was load-bearing (a fresh epoch needs a fresh
cluster session). Which build the gateway runs is a decision the proof makes, not a side effect of
minting an epoch — and doing it in the runner is what made the failure mystifying, since the
`[stp-prep]` line that dragged the gateway historical says only "fresh epoch minted ON …". The
runner's baseline block already pins the gateway to `${BASELINE_IMAGE}`; `rebuild_fresh_epoch` had
no business undoing it.

`roll_to` in `yu13-stp-and-replace` therefore now rolls the gateway **unconditionally**, not only
when the members move: the runner mints this proof's epoch on `IMAGE_PRE` while leaving the gateway
on the baseline build, so at step 1 the members do not move and the gateway still must.

**2. Both proofs patch the probes for the duration and restore them on the way out.** While a proof
owns the deployment it probes the one endpoint every build has served — `/ready` on **18110**,
exactly what the manifest declared before the probe server existed — and drops startup and liveness,
which on these builds have no endpoint to ask. Readiness carries `failureThreshold: 24` for the
reason the manifest's own comment gives: without a startup probe, a gateway needs ~2 minutes of
slack for JVM + media driver + `awaitConnected`.

Image and probes move in **one** strategic-merge patch, so one rollout does both, and both are
restored in the EXIT trap — an abort part-way can no longer leave the deployment describing a build
it is not running. Nothing under proof is weakened: neither proof asserts on a probe verdict.
`yu13-cancel-ingress` asserts on `/cancel` and the replicated book; `yu13-stp-and-replace` on the
engine's trade counter, the book digest, and `/replace`'s status code.

Kept as two local copies rather than a shared lib: every proof in `scripts/proofs/` is standalone
and readable on its own, and there are two callers. If a third needs it, extract then.

**3. `yu13-cancel-ingress` now restores the gateway IMAGE too.** It never did: it left the rig on
`yu15-cancel` and relied on the runner's baseline repin, which runs *before* the proof loop — so
every proof after this one in the same suite talked to a `yu15-cancel` gateway. It survived only
because `yu13-stp-and-replace` ran next and repinned the gateway as a side effect of rolling the
members. Doing (1) without this would have exposed it. `yu13-stp-and-replace` restores the gateway
to what the **gateway** was, which is no longer the same as the StatefulSet's original image now
that the runner mints its epoch on `IMAGE_PRE` without repinning the gateway.

**4. `yu13-stp-and-replace`'s seed failure now reports what it SAW.** `seed failed for 42422 after
5 attempts` was true and useless: `-o /dev/null` threw the body away and `curl -sf` collapsed
`000` (no answer — a dead tunnel, or a gateway not listening) and `500` (an answer, from a gateway
that could not resolve the symbol) into the same nonzero exit. Those are different faults. The
retry loop now prints each attempt's HTTP code and body.

**Rejected: rebuilding the historical tags with a probe server grafted on.** It means reconstructing
old builds, and it muddies what "historical" means — the tag would no longer be the artifact it
claims to be.

**5. `initialDelaySeconds: 5` belongs in the historical probe form, and leaving it out broke a
proof.** The first version of this fix omitted it. YU15's own gateway manifest — the one these
builds were actually deployed against — carries it, and it is load-bearing rather than decoration:
on a pre-YU16 build `/ready` reports `connected:true`, which means "my session opened", not "I can
commit". Without the delay the kubelet marks the pod Ready at t≈0, `rollout status` returns, and
`roll_to` seeds immediately into a session that cannot yet resolve a symbol.

Measured: `yu13-stp-and-replace` then failed all five seed attempts with `422 {"seeded":false}`
whenever `yu13-cancel-ingress` ran before it, and passed standalone. **The isolation experiment run
to exonerate the gateway build could not reproduce it, because that script slept ~7s between the
rollout and the seed — accidentally supplying the very delay the patch had removed.** A control that
silently controls for the variable under test proves nothing; the giveaway was that it disagreed
with a reproducible in-suite failure.

Reinstating a build that predates a fix means reinstating the settings it shipped with, not just its
image tag.

## The general lesson

Two, and the second is the expensive one.

**A probe port is part of the deployment contract between a manifest and an image.** Changing it
silently invalidates every older image the manifests are ever pointed at — including ones kept
deliberately, for proofs whose entire purpose is to run an older build. The failure surfaces as a
rollout timeout, which reads as "slow" rather than "incompatible", above a pod log that is
completely clean: it says `GATEWAY up` and is killed anyway. When moving a probe, grep every image
tag the proofs roll to and decide, per tag, whether it still satisfies the new contract.

**A change to a manifest that EVERY proof inherits cannot be verified by the proofs that test the
change.** The probe work was verified by its own two proofs — which pass, because they run the
current image — and the suite was not re-run afterwards. The reported "21 passed, 0 failed" on YU16
predates the change. The same probes were wired into five GKE manifests on the same evidence. A
shared manifest is a dependency of the whole suite, so the whole suite is its test; the proofs that
exercise the changed behaviour are the ones *least* able to detect the collateral damage, because
they are the ones guaranteed to be running the build that satisfies it.

**Corollary for the runtime image**: it carries a JRE and no JDK tools — no `javap`, no `strings`.
A staleness or capability check built on them does not report "absent", it *refuses every image*,
including the correct ones. Use `grep -a` on the class file, and give it a negative control.
