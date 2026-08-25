# A large epoch's replay outlasts the members' liveness budget, and the restart it causes re-incurs it

**Measured 2026-08-25** during the format-8 mint (chip 4), on `kind-traderx-yu12-cluster` running
`traderx/cluster-node:yu17-markwait2` at epoch sequence ~4,079,561. **Fixed 2026-08-25** in the
YU17 layer's StatefulSet — see *Resolution*.

> **WHAT IS AND IS NOT EXERCISED.** Both halves of the fix are verified LIVE on the rig: the
> `startupProbe` is evaluated and enforced by the kubelet (shown by breaking it deliberately), and
> `-Daeron.driver.timeout` is read by Aeron out of `JAVA_TOOL_OPTIONS`. **The 4M-sequence replay
> they are sized for was NOT reproduced** — the mint dissolved that epoch and growing another past
> ~4M takes days of feed. So: the mechanism is measured, the scenario is not. Treat the sizing as
> derived-and-argued (below), not as observed under load.

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


---

## Resolution

Two changes, both in `specs/YU17-otc-rates/generation/kubernetes/cluster/statefulset.yaml`, one per
killer. The full reasoning lives beside each stanza in the manifest; this is the summary.

### 1. `startupProbe` — removes the probe half (exit 137)

```yaml
startupProbe:
  tcpSocket: { port: 8080 }
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 180
```

The platform feature this issue asked for, and nothing built. While it is failing, liveness and
readiness are both suspended; liveness only begins after it has succeeded once. `tcpSocket:8080` is
the right predicate because the member's HTTP server does not accept until replay is done — the
same property that made the old liveness probe fire is what makes this probe turn green at exactly
the right moment.

**Sizing, from the bound.** The archive is on a 1Gi PVC and a framed log entry is 96 bytes, so no
epoch this rig can carry exceeds ~11M entries. Replay ran 4.08M in ~270s (~15k/s), so the LARGEST
epoch the PVC can physically hold replays in ~12 minutes. `10s x 180 = 1800s` is 2.5x that. It
bounds STARTUP only: after the first success the unchanged 345s liveness budget applies, and a
member that never starts at all is still restarted — at 30 minutes instead of never.

**Guard-interaction check** (`a-per-member-liveness-probe-fires-on-a-global-condition.md`): the
firing condition (replay duration) DOES correlate across members, which by that issue's rule
forbids a per-member destructive action. It is safe here for two reasons — the probe's action while
failing is to SUPPRESS other guards rather than to kill, and the only destructive outcome
(exhausting 1800s) is 2.5x above the physical ceiling of the thing being waited for, so a
correlated exhaustion means the cluster is genuinely dead rather than merely busy.

### 2. `-Daeron.driver.timeout=300000` — the other half (exit 70), which no probe can reach

The issue notes the startupProbe "does not address the driver keepalive (exit 70)", and exit 70
alone re-incurs the replay, so the loop would still be self-sustaining. Two of the three members
last exited 70. It is fixed here rather than filed, per yaakov's standing instruction on related
sub-issues.

`aeron.driver.timeout` (default 10s) is what `ClientConductor.checkLiveness` compares the driver
keepalive age against — it is the `timeout=10000ms` printed in the FATAL line above. The driver on
a member is EMBEDDED (`ClusteredMediaDriver`, same JVM as the consensus module and the service
container), so this timeout can only ever detect the JVM starving itself; the out-of-process driver
death it was designed for cannot happen. 300s is 10x the largest starvation gap measured (29s) and
well inside the 1800s startup window.

### The arms, and what they can still catch

* **`startupProbe` is enforced, not decoration** — verified 2026-08-25 by pointing it at port 8079
  (nothing listens) with `failureThreshold: 2`. `order-matcher-cluster-2` never became Ready and
  the kubelet killed it repeatedly, naming the probe:

  ```
  Warning  Unhealthy  (x12 over 2m45s)  Startup probe failed: dial tcp 10.244.1.32:8079: connection refused
  Normal   Killing    (x6  over 2m40s)  Container cluster-node failed startup probe, will be restarted
  ```

  Members 0 and 1 stayed 1/1 throughout (the StatefulSet rolls in order), which is also the
  blast-radius reading. Reverting the manifest brought member-2 back to 1/1. Note what those
  events do NOT say: no liveness event fired in that window, which is the suspension working.
* **`aeron.driver.timeout` is honoured** — verified 2026-08-25 by launching a throwaway
  `io.aeron.driver.MediaDriver` with `-Daeron.print.configuration=true` inside two pods running the
  SAME image, and reading back the value Aeron resolved:

  ```
  member-0        (JAVA_TOOL_OPTIONS carries the property)  driverTimeoutMs=300000
  cluster-gateway (JAVA_TOOL_OPTIONS does not)              driverTimeoutMs=10000
  ```

  Same image, same command, two different readings — so the value tracks the env var rather than
  the build.
* **Still catches:** a member whose JVM never opens 8080 at all is killed at 1800s; a member that
  exits is restarted regardless of any probe; a driver that is genuinely gone still surfaces, at
  300s instead of 10s.
* **Does NOT catch, by design:** a member that is up, listening and wedged. That remains Raft's job
  (vote it out) and readiness's (shed its traffic), exactly as the liveness stanza already argues.
* **Not exercised:** the 4M-sequence replay itself. The next epoch to grow that large is the real
  test, and the reading that would confirm the fix is `restarts` staying at 0 across a startup
  longer than 345s.

## Not done here

`gke/statefulset.yaml` in this layer carries neither change. The GKE rig is deliberately down
(yaakov's decision) so nothing could be verified against it, and this chip does not touch it.
Ancestor layers (YU13-YU16) also keep their own `statefulset.yaml` without the probe — that follows
this branch's existing practice, not an oversight: the 2026-08-24 `tcpSocket` liveness fix was
landed on the YU17 layer alone too, and YU16's file still carries the old `httpGet /health` form.

### The full suite, 2026-08-25

`DESTRUCTIVE=1 bash scripts/yu15/run-proofs.sh` on `kind-traderx-yu12-cluster` /
`traderx/cluster-node:yu17-format8`: **38 passed, 0 skipped, 1 failed**. The one failure is
`yu17-session-opens-from-close`, a proof landed by a concurrent chip an hour earlier whose
`/eod/session/previous` route is committed in source but not yet on the running trade-processor
image — it fails at step 1 with a 404, has nothing to do with the epoch procedure, and is not this
chip's to make green. Everything the procedure change touches ran and passed, including the whole
rolling tail (`yu13-stp-and-replace`, `yu13-cancel-ingress`, `yu16-book-grid`,
`yu16-liveness-restarts-wedge`, `yu17-halt-survives-failover`, `yu17-closed-survives-restart`).
