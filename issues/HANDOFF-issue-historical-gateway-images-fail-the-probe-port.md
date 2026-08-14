# Issue: two proofs cannot roll a pre-YU16 gateway image, because the probes moved ports

**Found** 2026-08-13 while running the suite against YU17. **Independent of YU17** — it is a
consequence of YU16's probe change meeting historical images, and it will affect every state from
YU16 onward until one of the fixes below is taken.

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

## What it breaks

Two proofs roll the gateway to a historical build as part of what they prove, and both now fail at
that step with `error: timed out waiting for the condition` — a message that says nothing about the
cause:

- **`scripts/proofs/yu13-cancel-ingress.sh`** — step 3 rolls the gateway forward to
  `traderx/cluster-node:yu15-cancel`.
- **`scripts/proofs/yu13-stp-and-replace.sh`** — the runner's `[stp-prep]` block mints a fresh
  epoch on `traderx/cluster-node:yu15-pre`, which repins the gateway too.

Both images are present on the kind nodes, so this does not look like a missing image; the pod
starts and then crash-loops on a probe.

## Fixes, in preference order

1. **Rebuild the historical tags from a build that has the probe server**, if what the proof needs
   from them is the pre-change *engine* behaviour rather than the pre-change gateway. `yu15-pre`
   and `yu15-stp` exist for `yu13-stp-and-replace`'s deterministic-core before/after, which is a
   MEMBER-side property — the gateway does not need to be historical at all there.
2. **Repoint the probes to 18110 for the duration of the roll** in the two proofs, and back
   afterwards. Cheap, but it weakens exactly the property YU16 added the separate server for.
3. **Give the historical images a probe alias**: the current build already registers `/ready`,
   `/health` and `/live` on the main port as well as the probe port, precisely so existing scripts
   that curl `:18110/ready` keep working. A rebuilt historical tag inherits that for free.

## The general lesson

A probe port is part of the deployment contract between a manifest and an image, and changing it
silently invalidates every older image the manifests are ever pointed at — including the ones kept
deliberately, for proofs whose entire purpose is to run an older build. The failure surfaces as a
rollout timeout, which reads as "slow" rather than "incompatible", and the pod's own log is
completely clean: it says `GATEWAY up` and is killed anyway.

When moving a probe, grep for every image tag the proofs roll to and decide, per tag, whether it
still satisfies the new contract.
