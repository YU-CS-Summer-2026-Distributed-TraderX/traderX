# NATS JetStream state is ephemeral on kind — a decision, not yet a defect

**Raised 2026-08-20.** Filed so this is decided rather than settled by omission. **No work is assigned
here**, and it was deliberately kept out of the scope of the EOD re-bind fix
(`a-nats-restart-silently-kills-every-eod-durable.md`).

## The fact

The NATS Deployment mounts its `store_dir` on an `emptyDir`:

```
volumes: [{"emptyDir":{},"name":"data"}]   mountPath: /data     store_dir: /data/jetstream
```

So **every NATS restart destroys every stream, every durable, and every unconsumed message.** This is
the mechanism behind the ten-hour EOD outage, and it is also what made that incident's repro valid —
a PVC-backed broker would have made the negative control vacuous.

## Why the answer is not simply "give it a PVC"

The re-bind fix **materially reduces what a PVC would buy.** Before it, a NATS restart meant permanent
chain death. After it, the same restart means a self-healing gap of a few seconds — measured at under
30s for both EOD durables and the algo engine, twice, by two sessions independently.

What a PVC would still protect is narrower than it first looks:

1. **Unconsumed messages in flight at the moment of restart.** The manifest comment already states
   these are lost, so this is a known and accepted property, not a discovery.
2. **The algo engine's event log — and this is the part that deserves judgement.** Its whole state
   *is* that log; it does not keep a separate store. So a NATS wipe is not a gap for it, it is
   permanent state loss.

## The tell that makes (2) worth someone's attention

On the fixed build the algo engine came back logging `rebuilding … replayed 0 events`. That is
**correct behaviour against an empty stream** — and it is also **exactly what permanent state loss
looks like**. The two are indistinguishable from the log line alone.

On this rig there was nothing to lose, so nothing was lost. On a rig with live algo parents mid-flight
there would be, and the same reassuring line would be printed either way. Compare
`issue_algo_parent_retries_forever` — algo parents already replay from JetStream on restart, which is
precisely the state this would drop.

## What is NOT in question

Consumers must self-heal from a state loss **however it was caused** — a broker restart, a failover, a
network partition, a cloud provider's maintenance. That is true with or without a PVC, in production
as much as on kind. So the re-bind fix is orthogonal to this decision and is not weakened by leaving
NATS on an `emptyDir`.

## Related scoping note on the risk-extract PVC

The `extracts` PVC uses kind's default `standard` class — `rancher.io/local-path`, `WaitForFirstConsumer`,
reclaim `Delete`. That is a **hostPath-backed directory pinned to one node**, not a network volume. So
"cuts now survive a pod restart" is exactly the claim to make; "cuts are durable" is broader than the
storage supports. Node loss still takes them.

**And there is no `lost+found`** — the local-path provisioner hands out a plain directory rather than a
formatted filesystem. Anyone expecting one on a fresh PVC (a reasonable ext4 assumption, and one this
project briefly held) should not read its absence as evidence the mount is wrong.

## DECIDED 2026-08-21 by yaakov: stay ephemeral, and fix the tell

No PVC. The re-bind fix already reduces a NATS restart to a self-healing gap measured under 30s,
and consumers must self-heal from state loss however caused — so the storage is not the lever.

**The work this creates is the observability half, not the storage half.** The execution-algo-engine
logs `rebuilding … replayed 0 events` on restart, which is correct against an empty stream and is
also exactly what permanent state loss looks like. Those two must stop being indistinguishable: the
engine should say which one happened — an empty stream is a fact about the broker, a lost cursor is
a fact about this consumer.

Accepted, explicitly, as the cost of this choice: live algo parents in flight at the moment of a
NATS wipe are lost. That is now a decision with a name on it rather than a silence.
