# A proof killed mid-run leaves its prep stranded on the rig, and the next suite blames the network

**Found 2026-08-27** by paying for it: a suite interrupted by the host disk filling left **three**
separate mutations behind, and every subsequent suite died at the readiness gate with a message
that named something else entirely.

## What was stranded, and by what

`yu13-stp-and-replace`'s prep block in `scripts/yu15/run-proofs.sh` makes three changes and undoes
all three **in a block at the very end of the proof**:

| mutation | restored at | what it looked like afterwards |
|---|---|---|
| members repinned to `traderx/cluster-node:stp-boundary-pre` (`STP_IMAGE_PRE`, line 356) | end block | **a mixed-version cluster** — members on the deliberately-reverted build, gateway/risk-extract/feed-adapter on `yu17-adr072`, three members Ready, consensus forming, nothing reporting it |
| `grafana loki tempo prometheus otel-collector` scaled to **0** ("stp needs a quiet box") | `STP_RESTORE_OBS` block | every later suite: `[fail] forwards never all became reachable` |
| `CONTROL_FEED_SUBSCRIBER=0` on `cluster-gateway` | end block | silent — control-feed proofs would fail or pass vacuously |

The proof never reached its restore block, so none of it came back.

## Why this is worth a file

**The failure message points at the network, hours downstream of the cause.** The readiness gate
waits on six endpoints, two of which are Tempo and Loki. With those Deployments at zero the gate
cannot be satisfied, and it reports *"forwards never all became reachable"* — which reads as a
port-forward problem, or a flaky API server, or a busy box. It took a full diagnosis pass
(apiserver latency, etcd logs, endpoint checks, a manual port-forward that worked fine) to get from
that message back to a Deployment a different proof had scaled to zero **three hours earlier**.

**And the mixed-version one is worse, because it has no message at all.** A cluster whose members
run a reverted build and whose clients run the tip is Ready, forms consensus, and answers
everything. It was found by inspection, not by a failure. Any suite run in that window is suspect
and cannot be shown to be sound after the fact — dating it requires reconstructing which run died
where.

## The class, which is the point

**`restore-at-the-end` is not crash-safe, and more than one proof uses it.** Any interruption —
an outage, a `Ctrl-C`, a `kill`, a laptop lid — converts a self-cleaning proof into a rig-wide
config change nobody made deliberately and nobody is told about. The blast radius is every
subsequent run on that rig, including other lanes'.

Worth considering, roughly in order of cost:

1. **A prep manifest** — the suite records what it mutated to a file before mutating it, and a
   startup check reconciles the rig against it. This is the only option that survives `kill -9`.
2. **`trap ... EXIT`** around each prep block, so an interrupt still runs the restore. Cheap;
   covers everything except a hard kill.
3. **A rig-state precondition** in `start_forwards`' gate: if a Deployment it waits on is scaled to
   **0**, say *that*, rather than reporting an unreachable forward. This does not prevent the
   stranding but it collapses the diagnosis from hours to one line.

(3) is worth doing regardless of (1) and (2), because the same confusion recurs whenever anything
scales a dependency down — the gate's own comment already notes that `stp` deliberately does so and
that waiting on a service just switched off is *"an unsatisfiable condition"*.

## Related

- `issues/open/the-rig-was-left-mixed-version-on-the-stp-revert-build.md` — the end state, filed
  separately before the cause was known; this issue is the mechanism behind it
- `scripts/yu15/run-proofs.sh` — the prep block (~line 990) and its restore block (~line 1123)
