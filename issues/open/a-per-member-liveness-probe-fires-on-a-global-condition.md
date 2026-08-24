# A per-member liveness probe that fires on a global condition takes out quorum

> A record of a guard-interaction class, not just of the 2026-08-24 incident. The specific probe is
> fixed; the class outlives the fix and applies to every per-member guard added to this cluster.

**Filed 2026-08-24**, from the overnight liveness-kill rounds on the kind rig
(`order-matcher-cluster-0/1/2` at restarts 9/10/11, all three containers exiting within one second
of each other, at least four rounds in one night).

## What happened

The cluster StatefulSet's liveness probe was `httpGet /health` with the Kubernetes default
`timeoutSeconds: 1`. Under node-wide CPU contention (kind workers at 105–148% with the full
observability stack co-resident), `/health` answers **slowly rather than not at all** — measured
under flood at up to 3.6 s total time while the member was demonstrably alive and converging. To a
1-second-timeout probe, a slow answer and a dead process are the same observation. Sixty seconds of
slowness (failureThreshold 6 × periodSeconds 10) reads as death.

The guard is per-member; the condition it actually fires on — contention — is **node/host-global**.
So a probe designed to restart ONE wedged member killed **all three members simultaneously**,
destroying the quorum it exists to protect. The cluster's own redundancy (Raft voting out a wedged
member, readiness shedding its traffic) never got the chance to handle it the cheap way.

A second-order guard interaction made each kill cost 2–3 restarts instead of 1: the restarted
container raced its own Aeron mark files (heartbeat not yet stale at relaunch: `active mark file
detected`, seen on the archive mark file on members 0/1 and the consensus mark file on member 2),
crashed again, and — because a throw out of `main` does not exit a JVM holding non-daemon Aeron
threads — sometimes left a zombie the liveness probe then had to clear too, with the health port
answering `connection refused` the whole way down.

## The general finding (the part that outlives the fix)

For any per-member guard, ask **what population the firing condition actually spans**:

- A guard whose trigger is member-local (this JVM exited, this socket closed) may take local
  action (restart this member) safely.
- A guard whose trigger correlates across members (CPU contention, network softirq starvation, a
  flood, a snapshot barrier, clock jumps, image-pull storms) must NOT take an action that is
  destructive when taken by every member at once. Restart-one is self-healing; restart-all is an
  outage — and the correlated condition guarantees restart-all.

The probe fired correctly, per its own definition, on every member. No single guard was wrong; the
composition was. That is `guard-interaction-audit`'s territory, and this is its sharpest cluster
instance: the eviction/repair action is fine at member scope and catastrophic at quorum scope,
selected between by nothing but whether the trigger condition is local or global.

## What was changed (2026-08-24)

- Liveness switched to the `tcpSocket` form already proven on the GKE flood tier
  (`gke/statefulset-emptydir.yaml`): answered by the kernel from the accept backlog, so
  contended-but-alive passes while a dead JVM (socket closed) still fails instantly;
  `timeoutSeconds: 5`, `failureThreshold: 20`, `periodSeconds: 15` (~5 min tolerated saturation).
  Readiness got an explicit `timeoutSeconds: 2`. Carried to the cluster and gke statefulset
  variants at the operative layer on YU12–YU17 (emptydir variants already had it).
- `ClusterNodeMain` gained a bounded (60 s) retry of the cluster launch on
  `active mark file detected`, modelled on `awaitDns`, with a halt-not-throw terminal path.
  Carried to the YU12, YU13 and YU15 layer copies.

## What is still open

- The liveness probe still cannot distinguish "zombie on THIS member" from "sockets closed on ALL
  members at once" — tcpSocket merely makes the global condition (contention) invisible to it. A
  condition that closes every member's socket simultaneously would still be answered with a
  simultaneous 3-way restart. The durable shape from `guard-interaction-audit`: a guard's action on
  a correlated signal should be rate-limited across the quorum (e.g. PodDisruptionBudget applies to
  evictions but NOT to liveness restarts — kubelet consults nobody). Nothing in stock Kubernetes
  expresses "restart at most one member of this StatefulSet per window"; if this class recurs, that
  mechanism has to be built (a leader-aware sidecar, or probe logic that reads peer health before
  reporting failure — each with its own guard-interaction hazards).
- Restart counts on the members are asserted against a preflight baseline in some proofs; none of
  the proofs asserts "no simultaneous multi-member restart happened", which is the actual quorum
  hazard. The suite is structurally blind to it unless a proof runs while contention happens to
  fire (`vacuous-pass-audit` rule 17's shape: needs a separate instrument, not a fixed proof).
