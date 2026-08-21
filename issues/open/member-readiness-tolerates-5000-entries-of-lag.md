# A cluster member reports Ready while up to 5000 entries behind — and while alone

> The values below are a record, not a rig you can query. Applied sequences, order hashes and refs
> come from the epoch this was measured on; that epoch has been rolled and will be rolled again. Read
> them as a worked example of the shape, and re-derive from the rig in front of you.

Surfaced 2026-08-21 by the `yu16-book-grid` rebuild fix — the first proof to wipe a member's disk
rather than just its pod. A tail replay closes the gap too fast to see this; only a from-nothing
rebuild holds it open long enough.

**Observed:** a rebuilt member reported `condition=Ready` roughly **90 seconds before** its book
matched its peers. During that window it served a different order hash and a lower `nextOrderRef`
than the other two, then converged exactly.

## The mechanism — it is a deliberate tolerance, not an oversight

`ClusterNodeMain`'s readiness sampler:

```java
final long maxLag = Long.parseLong(env("CLUSTER_READY_MAX_LAG", "5000"));
...
final boolean ready = maxPeer < 0 || mine >= maxPeer - maxLag;
```

**`CLUSTER_READY_MAX_LAG` defaults to 5000.** A member is Ready while it is within five thousand
applied entries of its fastest peer. That is a sensible anti-flap tolerance for load shedding — it
stops a member dropping out of service every time it falls momentarily behind under flood. It is not
a statement that the member holds the same state.

So `Ready` means *"not catastrophically behind"*. It is routinely read as *"converged"*. Those are
different claims and the gap between them is up to 5000 entries wide.

## The second edge, in the same expression

`maxPeer < 0 ||` — `maxPeer` stays `-1` when no peer can be reached. **A member that cannot reach any
of its peers reports Ready.** It is the arm that makes a single-member or bootstrapping cluster work,
and it also means an isolated member on the minority side of a partition advertises itself as
healthy, having compared itself against nothing. Readiness cannot distinguish *"no one to compare
against"* from *"I compare well"*.

## Why this matters more than the 90 seconds

The 90-second window is the symptom. The property is that **readiness is not a convergence signal and
nothing says so at the point of use.**

- Any check that reads a member's book once at `Ready` can observe a false divergence. The book-grid
  proof survives only because its consensus poll has a budget that absorbs the window — it passes for
  a reason unrelated to the thing it asserts, which is the same shape as the defect that proof was
  just fixed for.
- Anything that routes to members by readiness would route to a member holding a stale book. Today
  the members' Service is headless and client reads reach the leader through the gateway, so this is
  not currently serving stale reads to clients. That is a property of the present topology, not a
  guarantee — a follower-read path would inherit the problem silently.

## Prior art in this repo, one component over

The gateway had the same class of defect and it was fixed by *narrowing what readiness means*:
`/ready` was socket-based, so a wedged gateway advertised healthy and Kubernetes kept routing a public
IP into it. It now means "I can commit", and clears only on a demonstrated commit — see
`scripts/proofs/yu16-ready-tracks-commit.sh` and the gateway-wedge issue. **This is the member-side
instance of the same question**, and worth resolving the same way: decide what the signal asserts, then
make it assert that.

## Directions, not a decision

1. **Leave the tolerance, name it.** Keep `CLUSTER_READY_MAX_LAG` for service routing, and give
   convergence its own surface (`/ready` already reports `applied` and `maxPeerApplied`, so a caller
   can compute it) — then fix the *callers* that conflate them.
2. **Two signals.** Kubernetes readiness keeps the tolerance; a separate `converged` flag requires
   `mine >= maxPeer`. Proofs and any follower-read path gate on the second.
3. **Tighten the default.** Cheapest, and the most likely to cause flapping under flood — the
   tolerance exists for a reason and 5000 was presumably chosen against real load. Do not change it
   without a bench run.

The isolated-member arm should be decided separately from the lag tolerance; they are independent.

## Lineage warning for whoever takes this

**Three layers carry `ClusterNodeMain.java` — YU12, YU13 and YU15 — and `YU15` is the operative one**
(verified by diffing each against the generated tree). A fix applied to the YU12 or YU13 copy is
inert and will look exactly like a fix that did not work. Confirm against `generated/` before and
after, and see `.claude/skills/propagate-spec-fix`.

## What was and was not established

**Measured:** the ~90s Ready-before-converged window on a from-nothing rebuild, on the cluster kind
rig, during two independent runs of the fixed book-grid proof; and that all three members expose
`applied` and `maxPeerApplied` on `/ready`.

**Read from source, not exercised:** the `maxLag` default of 5000 and the `maxPeer < 0` arm. The
isolated-member case has NOT been reproduced — no partition was induced. Do that before acting on it.

**Not established:** whether 5000 is load-justified, what the window looks like on the single-BLP
tier, and whether anything downstream currently reads a member at `Ready` and trusts the book.
