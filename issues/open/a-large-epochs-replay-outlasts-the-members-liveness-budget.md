# A large epoch's replay outlasts the members' liveness budget, and the restart it causes re-incurs it

**Measured 2026-08-25** during the format-8 mint (chip 4), on `kind-traderx-yu12-cluster` running
`traderx/cluster-node:yu17-markwait2` at epoch sequence ~4,079,561. Filed rather than fixed: the fix
is a manifest change to every layer's StatefulSet and is not mint scope.

> The rig this was measured on no longer exists — the mint wiped it. The numbers below are the
> record. Reproduce by letting an epoch grow past ~4M sequences.

## What was observed

All three members `1/1 Running` and in agreement, consensus applying normally, and yet:

| pod | restarts | last exit | ran for |
|---|---|---|---|
| `order-matcher-cluster-0` | 63 | **70** | 82s |
| `order-matcher-cluster-1` | 59 | 137 | ~6.5m |
| `order-matcher-cluster-2` | 63 | **70** | 79s |
| `cluster-gateway` | 38 | 255 | ~9m |
| `feed-adapter` | 97 | 1 | 6s |

Cumulative events on the members: 1408–2069 readiness failures, 423–586 liveness failures, and
23/32/23 × `Killing: Container cluster-node failed liveness probe, will be restarted`.

## THE TELL, and it is the thing that was got wrong

**Exit 70 mixed with exit 137 — not 137 alone.** The initial characterisation read one member's
`lastState` (member-1, exit 137, with a liveness event) and concluded "the liveness probe is killing
a member whose state machine is healthy" — a guard-interaction defect. Reading *all three* refutes
it: two of the three last exited **70**, and 70 is the application's own die-on-error path:

```
io.aeron.exceptions.DriverTimeoutException: FATAL - MediaDriver (/dev/shm/aeron-cluster)
    keepalive: age=29052ms > timeout=10000ms
    at io.aeron.ClientConductor.checkLiveness(ClientConductor.java:1898)
*** Media Driver timeout - is it running? exiting client...
Consensus module terminated; exiting for pod restart
```

The Aeron **driver conductor did not tick for 29 seconds**. If you see only 137 you go looking at
the probe; if you see 70 you go looking at what starved the JVM. Check every member's exit code
before attributing this shape.

## The root cause: replay monopolises the JVM

Timestamps from one live instance's own log:

```
ELECTION-PHASE state=CANVASS     atMs=1787669635679
ELECTION-PHASE state=NOMINATE    atMs=1787669695606     (+59.9s stuck in CANVASS)
ELECTION-PHASE state=CLOSED      atMs=1787669696891
FIRST-APPLY                      atMs=1787669967446     (+270.6s after CLOSED)
```

**~331s from container start to first apply**, replaying a 4.08M-sequence log. Against that:

| bound | value | source |
|---|---|---|
| liveness budget | `initialDelaySeconds 45` + `failureThreshold 20 × periodSeconds 15` = **345s** | `statefulset.yaml:160-172` |
| Aeron driver keepalive | **10s** | `DriverTimeoutException` above |

So the two killers share one root and fire independently:

1. the HTTP health server on 8080 does not answer for the whole replay → `tcpSocket` liveness
   exhausts 345s → SIGKILL → **exit 137**;
2. the driver conductor is starved during the same replay → keepalive age 29s > 10s → the app
   exits itself → **exit 70**.

Either way the pod restarts **and must replay the whole log again**. Nothing bounds the loop; it is
self-sustaining, and it ran for at least 13 hours. `feed-adapter`'s 97 restarts are purely
downstream (`cluster connect timeout state=AWAIT_PUBLICATION_CONNECTED` against members that are all
crash-looping) — a consequence, not an independent fault.

Not memory or disk: heap was `-Xms256m -Xmx512m`, `/dev/shm` 512M at 10% used.

## The fix direction: the members have no `startupProbe`, and the gateway does

Verified live 2026-08-25 on the minted rig:

```
order-matcher-cluster  startupProbe=            <- absent
                       livenessProbe={"tcpSocket":{"port":8080},"initialDelaySeconds":45,
                                      "periodSeconds":15,"failureThreshold":20}
cluster-gateway        startupProbe={"httpGet":{"path":"/live","port":18111},
                                     "periodSeconds":5,"failureThreshold":24}
```

A `startupProbe` is the native Kubernetes mechanism for exactly this: while it is failing, the
liveness probe is **suspended**, and only once it succeeds does liveness begin. A member given a
`startupProbe` on 8080 with a failureThreshold sized for the worst replay cannot be liveness-killed
mid-replay at all, which breaks the self-sustaining loop.

**Do not build a snapshot-aware probe or a replay-progress endpoint for this.** The platform feature
covers it, the gateway already demonstrates the pattern in this very tier, and the durable question
is only how large a threshold to allow — which is a `size-a-configuration-bound` exercise against
the largest epoch the rig is expected to carry.

It does not address the driver keepalive (exit 70), which is an in-JVM starvation bound and not a
Kubernetes concern; a member can still self-exit during a long enough replay. But it removes the
probe half, and the probe half is what turns one slow start into an unbounded loop.

## The bound

An epoch past **~4M sequences** reproduces this. The mint dissolved it by wiping — restarts went
63/59/63 → **0/0/0** and gateway 38 → 0, with zero liveness kills in the 20 minutes after, under
full suite load. That is a reset, not a fix: the next epoch to grow that large brings it back.

## Related

- `.claude/skills/guard-interaction-audit` — this looked like that class and is not; the
  discriminator was reading every member's exit code rather than one.
- `issues/open/the-manifests-pin-a-build-the-rig-no-longer-runs.md` — the 2026-08-24 liveness-probe
  fix (tcpSocket, timeouts) lives in the manifest and survives a re-apply; a `startupProbe` added
  there would too.
