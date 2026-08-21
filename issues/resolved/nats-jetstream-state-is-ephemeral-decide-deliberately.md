# NATS JetStream state is ephemeral on kind — a decision, not yet a defect

> **The values below are a record, not a rig you can query.** Order refs (`1-66`), trade ids
> (`4060-S`), trace ids, security ids, pod names and run counts come from the epoch this was
> measured on. That epoch has been rolled and will be rolled again — order refs restart at 1, the
> symbol table is renumbered, trace ids follow the client order ids of a run that no longer exists.
> Read them as a worked example of the SHAPE. Do not look them up, and do not treat their absence
> on a current rig as evidence about this issue.

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

## Resolved 2026-08-21 — the observability half shipped (`529c20cc`)

`AlgoEventStore` no longer logs one line for every replay. The replay is now classified against
what the stream says about itself (`getStreamState()` — message count and last sequence) plus the
one thing only this consumer knows: how many events it has already applied off that stream.

| verdict | what it means | level |
|---|---|---|
| `REPLAYED` | the log was there and this consumer read it: `replayed N of M … (last sequence S)` | INFO |
| `STREAM_EMPTY` | 0 messages, last sequence 0. Nothing was missed *by this consumer* — and the line SAYS a first boot and a wiped log are not separable from this side, instead of implying either | WARN |
| `LOG_LOST` | 0 messages, but this process had already applied N events off it, or the stream's own last sequence is > 0. Definite loss, not an inference | WARN |
| `CONSUMER_REPLAYED_NONE` | the stream still holds the log and this consumer read none of it — the gap is this subscription, not the broker's storage | WARN |
| `UNDETERMINED` | the stream could not be inspected. Carries the broker's own error and rules out neither of the above, rather than quietly rendering as one of them | WARN |

Verdict and operator-facing sentence are rendered from one place, so the classification and its
description cannot drift apart.

`STREAM_EMPTY` is a WARN deliberately. `replayed 0` is not a steady state — once any parent order
exists the stream is non-empty for good — so this warns on a genuinely cold first boot and
otherwise only when there is something to look at. It is the one branch that stays honestly
ambiguous, and it says so in the log rather than reading as an all-clear.

### What was proven, and how

- Six tests in `AlgoEventStoreReplayTest` (`execution-algo-engine` 37 → 43). Each branch was
  detonated in turn — `LOG_LOST`, `CONSUMER_REPLAYED_NONE`, `UNDETERMINED` each made unreachable —
  and each failed **exactly its own tests** while the module's other 40 stayed green with the
  defect in. No pre-existing test covered any of this.
- Exercised end to end against a **real JetStream broker of the image the rigs run**
  (`nats:2.14-alpine`), including a real wipe. Cold boot on an unused stream printed
  `STREAM_EMPTY`; three appended parents replayed as `replayed 3 of 3 … (last sequence 3)`; the
  broker was then destroyed and replaced at the same address, and the reconnect rebuild printed
  `STATE LOST: … reports 0 messages and this process had already applied 3 events off it`. That is
  the scenario this issue was filed about — and before the change all three of those printed the
  same `replayed 0 algo-engine events from TRADERX_ALGO_ENGINE`.
- `pipeline/generate-state.sh YU17-otc-rates`, then `engine-tests.sh hosted`, `service-tests.sh`,
  `assert-suites-executed.sh` and its `--selftest`: all rc=0, 564 tests across 6 modules, no module
  at zero. (Baseline 555; +6 here, +3 from concurrent trade-processor work by another lane.)

### Verified on the kind rig 2026-08-21 — and it falsified one of these lines

Run on `kind-traderx-yu12-cluster` against the image built from `529c20cc`
(`execution-algo-engine:yu17-recoverylog`). A true A/B, because the pre-fix line was captured off
the same rig from the running pod first.

```
BEFORE  :yu17-jsrebind    2026-08-20T16:31:49Z
  INFO  replayed 0 algo-engine events from TRADERX_ALGO_ENGINE

AFTER   :yu17-recoverylog 2026-08-21T20:55:46Z
  WARN  replayed 0 algo-engine events: TRADERX_ALGO_ENGINE reports 0 messages and last sequence 0
        ... NOT KNOWABLE from this side — a first boot and a wiped log look identical here.
```

Then a TWAP parent (600 AAPL, 900s, 5 buckets) was put in flight, the broker confirmed holding 3
messages via `/jsz`, and NATS was wiped. Same process, `restartCount` still 0:

```
  WARN  could not list consumers on TRADERX_ALGO_ENGINE after a NATS reconnect ... [10059]
  WARN  algo-engine event-store consumer is gone after a NATS reconnect ...; rebuilding
  WARN  STATE LOST: TRADERX_ALGO_ENGINE reports 0 messages and this process had already applied
        3 events off it ...
```

`applied 3` and the pre-wipe `/jsz` count of 3 agree from two independent sources. **`LOG_LOST` has
now fired with live algo parents actually in flight** — the gap this record listed as unproven.

**And the run falsified the line's own claim, which is the more valuable half.** The message said
*"every parent order those events carried is gone and no replay will bring it back."* The parent was
not gone: `GET /algo/orders/<id>` returned 200 `RUNNING`, and bucket 1 was submitted at 20:59:26Z,
**after** the 20:58:14Z wipe. It kept slicing.

The mechanism, confirmed in the source and not only in the observation: `AlgoOrderService` calls
`replayAndSubscribe(this::applyAndIndex)` and nothing resets that state before a replay, so a wipe
destroys the *broker's* copy of the log while the process keeps its own in-memory schedule. What
dies is recoverability, not the parents. Reworded in `65993103` to say **UNRECOVERABLE**, naming
both halves — this process still holds them and keeps running them, nothing will rebuild them if it
restarts. `CONSUMER_REPLAYED_NONE` carried the same over-claim and was reworded with it.

This is the `prose-has-no-test` failure in its purest form: a verdict derived from remembered
plumbing rather than from what the surface can observe. Unit tests could not have caught it — the
off-rig probe had no live parent to contradict it. Only a rig with something actually in flight did.

### What was NOT proven

- **The reworded line has never been printed.** The rig ran `:yu17-recoverylog`, which carries the
  *false* "is gone" sentence. The corrected UNRECOVERABLE wording (`65993103`) is unit-tested and
  generated, and no build carrying it has run anywhere. The classification it prints is unchanged —
  only the sentence moved — but that is a claim about the diff, not an observation.
- **`STREAM_EMPTY` and `LOG_LOST` fired; `CONSUMER_REPLAYED_NONE` and `UNDETERMINED` did not.**
  Neither has been seen on a rig. Both are covered by unit tests and by the off-rig probe only.
- **The state-014 tier was never touched**, and neither was GKE. This is a cluster-tier result only.
- **The torn-log case was created and not examined.** After the wipe the engine kept appending to
  the recreated stream, so it now holds a tail whose parent-created events are missing. A later
  restart would replay that partial history. `AlgoOrderState.applySubmitted`/`applyFillObserved`
  return early when the bucket is unknown, so it degrades quietly rather than crashing — read from
  the source, not exercised. Nobody has restarted the engine from a torn log.
- **The storage decision is unchanged.** Still no PVC. Live algo parents in flight at the moment of
  a wipe are still unrecoverable; that was the accepted cost, and this line makes it visible, not
  smaller.
