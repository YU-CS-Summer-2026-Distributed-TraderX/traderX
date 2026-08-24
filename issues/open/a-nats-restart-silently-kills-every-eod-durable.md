# A NATS restart silently and permanently kills the EOD chain's durable consumers

> **The values below are a record, not a rig you can query.** Order refs (`1-66`), trade ids
> (`4060-S`), trace ids, security ids, pod names and run counts come from the epoch this was
> measured on. That epoch has been rolled and will be rolled again — order refs restart at 1, the
> symbol table is renumbered, trace ids follow the client order ids of a run that no longer exists.
> Read them as a worked example of the SHAPE. Do not look them up, and do not treat their absence
> on a current rig as evidence about this issue.

**Found 2026-08-19 by the coordinator**, while trying to produce a cut so the UI lane's provenance
panel would have data. The chain had been dead for roughly ten hours with every pod reporting
healthy.

## What was broken

`eod.pnl.done` had not been published since **2026-08-18T23:43:13Z**. The EOD → risk-extract chain —
the path that produces the external risk-extract deliverable — was producing nothing, and
`/data/risk-extracts` on the extract pod was **completely empty**.

Every pod involved reported `Running 1/1`, `RESTARTS 0`.

## Root cause

The NATS pod restarted at **2026-08-19T14:25:11Z**, which recreated JetStream state — stream
`TRADERX_EOD` was created fresh at **14:39:32Z**. Both EOD durables are established once, at their
own pod's startup, and both pods predated that:

| Consumer | Durable | Subject | Pod started | Outcome |
|---|---|---|---|---|
| position-service `EodPnlConsumer` | `eod-pnl` | `eod.prices.ready` | 2026-08-17T14:02:58Z | durable gone, never re-created |
| risk-extract `RiskExtractMain` | `risk-extract` | `eod.pnl.done` | 2026-08-19T02:53:47Z | durable gone, never re-created |

The NATS client reconnects the *connection* — so neither service saw a fatal error. What it does not
do is re-create a push consumer whose server-side durable was destroyed underneath it. risk-extract
logged exactly one line about it (`IOException: Read channel closed`) and then nothing for ten hours.
position-service logged nothing at all.

Measured state before the fix:

```
TRADERX_EOD: msgs=3  consumer_count=0        <- nothing consuming either subject
```

`execution-algo-engine` was fine only because it happened to start at 19:53Z, *after* the NATS
restart. **Whether it would survive the same event is untested** — it almost certainly has the same
shape, it just was not running when the wipe happened.

## Why nothing caught it

`RiskExtractMain` already contains a guard for the *startup* version of this exact failure — a bind
deadline that gives up so the pod dies visibly, with a comment saying why:

> "this Deployment carries no readiness probe — so an unbounded retry leaves the pod Running and
> Ready with the producer permanently absent and the EOD extract silently never running. That is
> exactly the state the halt() exists to prevent."

**A mid-life NATS restart reaches that identical state by a different road, and there is no guard on
that road.** The file's own comment about the earlier bug uses the same phrase — "the bug this class
was just fixed for, reached by a different road" — which is what makes this worth writing down
rather than just restarting the pods.

## Remediation applied

`kubectl rollout restart` on `deploy/risk-extract` and `deploy/position-service`. Both re-subscribed,
drained the backlog immediately, and the chain came back:

```
TRADERX_EOD: msgs=6  consumers=2
   risk-extract  filter=eod.pnl.done      pending=0
   eod-pnl       filter=eod.prices.ready  pending=0
```

`scripts/proofs/yu17-swap-netting.sh` then passed end to end (exit 0): cut at N=19906, 13 contracts,
identical sha `75008ec6011a…` from all three members, both artifacts reproducing from `seq-19906.cut`
alone, and a member destroyed to an empty disk re-rendering the same sha.

## RESOLVED on YU17 2026-08-19 — option 2 (self-heal), verified on the kind rig

**The defect class was THREE services, not two.** The brief's open question about
`execution-algo-engine` is now settled by measurement rather than left as a guess — see below.

### What landed

A NATS reconnect is the only notice a client gets that JetStream state may have been recreated, so
it is the trigger. Each consumer now asks the server whether its consumer still exists and, if not,
re-creates it. Three files, one shape:

| Service | File (operative layer on YU17) | Consumer | Repair |
|---|---|---|---|
| position-service | `specs/YU15-eod-risk-extract/…/eod/EodPnlConsumer.java` | durable `eod-pnl` | `rebindIfDurableGone()` on RECONNECTED |
| risk-extract | `specs/YU17-otc-rates/…/cluster/RiskExtractMain.java` | durable `risk-extract` | one `bind` routine, run at startup and on RECONNECTED |
| execution-algo-engine | `specs/YU08-execution-algo-engine/…/eventstore/AlgoEventStore.java` | **ephemeral** pull consumer | `rebuildIfConsumerGone()` → re-create + replay |

**Option 1 (a readiness probe) was taken only where it is free and correct, and deliberately
refused where it is not:**

- `execution-algo-engine` — **taken.** `healthy()` already fed the readiness group and already said
  "a wedged/dead subscriber must unready the pod"; it returned true for a destroyed consumer
  (connection CONNECTED, thread alive, fetching from nothing). It now also requires a live
  subscription, so the probe finally means what its own comment claims.
- `risk-extract` — **not applicable, and this is the issue's own finding**: no Service, no container
  ports, no probe. Instead the file's existing mechanism was reused rather than competed with: a
  failed re-bind calls `halt(1)`, exactly as `main()` does, because process exit is the only
  liveness signal this Deployment has. Loud and dead beats quiet and dead.
- `position-service` — **deliberately NOT wired into `/actuator/health`.** This service also answers
  live position queries; unreadying it out of rotation because an overnight batch consumer lost its
  durable trades a silent batch failure for a loud outage of everything else. It exports
  **`traderx_eod_pnl_subscribed`** (1/0) instead — the alert without the self-inflicted outage, and
  the same number `consumer_count` gives, without having to fish it out of `jsz` by hand.

Option 3 was not needed as a standing workaround, but the mechanism is now written into
`cluster/nats.yaml`'s own comment, which previously said only that "consumers recreate their
streams on demand" — true of the streams, and the exact half-truth that made this look survivable.

### Measured on `kind-traderx-yu12-cluster`, 2026-08-19

**Negative control first, on the deployed unfixed build** (`cluster-node:yu17-ackB`,
`position-service:yu16`, `execution-algo-engine:yu17-fills`). NATS restarted at 02:55:28Z. Three and
a half minutes later:

```
streams = 0    consumers = 0    messages = 0
num_connections = 27
```

**Twenty-seven clients reconnected and not one rebuilt anything.** risk-extract logged the same
single `IOException: Read channel closed` named at the top of this file and then nothing;
position-service logged nothing at all; every pod stayed `Running 1/1`, `RESTARTS 0`. The incident
reproduced exactly, on demand, in under four minutes.

**`TRADERX_ALGO_ENGINE` went with them.** That settles the open question: `execution-algo-engine`
survived the 2026-08-19 incident *only* because it had started after the restart. Its consumer is
ephemeral rather than durable, which changes nothing — the client does not re-create either kind.
The defect class is three services.

**Positive test, on the fixed build** (`:yu17-jsrebind` everywhere). NATS restarted at 03:12:22Z;
**no consumer was restarted by hand.** Streams and consumers were back within 40s and stationary
across fourteen readings over four minutes:

```
TRADERX_EOD = 2   TRADERX_ALGO_ENGINE = 1
```

And the repair was observed FIRING, not merely inferred from the symptom clearing — the distinction
this project pays for elsewhere. Same pods throughout, `RESTARTS 0`, unchanged `startTime`:

```
risk-extract    RISK-EXTRACT created stream TRADERX_EOD subjects=[eod.prices.ready, eod.pnl.done]
                RISK-EXTRACT bound durable='risk-extract' subject=eod.pnl.done after 0 retries (re-bind)
position-svc    EOD durable 'eod-pnl' is gone after a NATS reconnect ...; re-subscribing
                eod consumer subscribed subject=eod.prices.ready durable=eod-pnl stream=TRADERX_EOD
algo-engine     algo-engine event-store consumer is gone after a NATS reconnect ...; rebuilding
                created algo-engine event stream: TRADERX_ALGO_ENGINE
                replayed 0 algo-engine events from TRADERX_ALGO_ENGINE
```

That last line is a **2026-08-19 capture and no longer exists**. `replayed 0` was correct against
an empty stream and was also exactly what state loss looked like, so the algo engine now says which
(`STREAM_EMPTY` / `LOG_LOST` / `CONSUMER_REPLAYED_NONE` / `UNDETERMINED`) — see
`issues/resolved/nats-jetstream-state-is-ephemeral-decide-deliberately.md`. Do not grep a current
rig for the string above.

`scripts/proofs/yu17-swap-netting.sh` then passed end to end, **exit 0**: cut at N=20209, 17
contracts, identical sha from all three members, both artifacts reproducing from the cut alone, and
a member destroyed to an empty disk re-rendering the same sha.

### The build, and why no fresh epoch was needed

`traderx/cluster-node:yu17-jsrebind` differs from `:yu17-ackB` by **exactly one class**. Measured on
the artifacts, not inferred: a class-by-class md5 inside both images gives 265 classes each and one
difference, `RiskExtractMain`. The deterministic core, the gateway and every member class are
byte-identical, so the tier rolled without minting a fresh epoch. Wire rule unchanged — still a
post-B build, still never mixed with a pre-B one.

### Known minor legibility item, not a defect

The algo engine's repair logs `WARN could not list consumers … stream not found [10059]` before
rebuilding. The outcome is correct and the next line states it, but "stream not found" is in fact a
*definitive* answer that the consumer is gone, not the uncertainty the wording implies —
`RiskExtractMain.durableExists` already treats 10059 that way. Worth aligning when someone is next
in the file; deliberately not changed after the images were built and verified, so that the
committed source matches the image the measurements above were taken on.

## Why this one matters more than its size

The risk extract is the **external deliverable** — the artifact another team's engine consumes. Its
failure mode here is silence: no error, no restart, no alert, healthy pods, and an empty output
directory. Everything about this incident was invisible until someone went looking for a file that
should have existed.

**A diagnostic that would have caught it in one command:** `consumer_count` on `TRADERX_EOD` should
be 2. It was 0.

## Not every `consumers=0` is this bug

Checked 2026-08-19, because the diagnostic above will be run by someone who then sees this and
raises a false alarm: **`TRADERX_CONTROL_ACCOUNT` sits at `consumers=0` normally.** Its property is
`risk.bootstrap.account-stream` — the order-matcher replays it **at bootstrap** to rebuild risk
state, with a consumer created on demand and gone afterwards. A standing consumer is not expected.

The distinction that matters: `TRADERX_EOD` drives an **event chain** and needs standing consumers,
so `consumers=0` there means the chain is dead. A replay-at-bootstrap source at `consumers=0` means
nothing is booting right now. Same number, opposite verdict — read the stream's role before the count.

## Independently reproduced by the coordinator, 2026-08-20

The fix lane's result was not taken on report. I re-ran the experiment myself on the fixed build,
after it handed the rig back.

**NATS deleted at 03:30:57Z.** No consumer restarted by hand at any point.

```
t+30s   TRADERX_EOD=2   TRADERX_ALGO_ENGINE=1   streams=2
```

Both rebound inside half a minute, and the streams were recreated too — this was a genuine wipe, not
a reconnect (NATS's own `/data` is an `emptyDir`, which is why the restart destroys JetStream state
and why the experiment is valid at all; a PVC-backed broker would have made it vacuous).

**Consumer counts alone were NOT accepted as the verdict**, because two bound consumers is exactly
what the rig showed during the original ten-hour outage's healthy-looking phase. The chain was then
required to *produce*: `scripts/proofs/yu17-swap-netting.sh` exit **0** — cut at N=20222, 19
contracts, identical sha across all three members, both artifacts reproducing from `seq-20222.cut`
alone, and a member destroyed to an empty disk re-rendering the same sha. Artifacts on disk went
6 → 9.

**Problem 2 re-tested separately**: `delete pod -l app=risk-extract`, replacement confirmed a
genuinely different pod (`…-zcbq7` → `…-dcx67`), and all **9 artifacts byte-identical by sha256**
across the replacement.

### A self-inflicted vacuous pass, caught, in the check written to catch one

The first version of that durability comparison used `C="kubectl …"` and then `$C` — the zsh
scalar-word-splitting trap. Every command failed with "command not found", both sha manifests came
back **empty**, and `diff` compared nothing to nothing and printed
**"ALL ARTIFACTS BYTE-IDENTICAL"**. A confident green verdict from a test in which the cluster was
never contacted.

Two rules, both already written down here and both worth the repetition because they were violated in
the same three lines:

- **Build the command as an array** (`C=(kubectl …)`, `"${C[@]}"`), never a scalar.
- **Guard the baseline for non-emptiness before comparing.** The re-run refuses to proceed unless the
  before-manifest has at least 6 files, so "identical" can never again mean "identically absent".

### The empty-comparison trap, twice in one session, from unrelated causes

The false green above was not a one-off. The console lane shipped the same class independently, from a
different cause: a verification wrapped in `timeout`, which **does not exist on macOS**, so the command
silently did nothing and the check reported green. Two sessions, two causes — a zsh scalar splitting
into "command not found", and a missing binary — **one shape**:

> **A comparison whose inputs can be empty must assert they are not.**

The uncomfortable part: this trap is *already named* in the `vacuous-pass-audit` skill's own
description ("agreement on no-data reads"), and it was still shipped, by a session that had that skill
loaded. So the lesson is not "write the rule down" — the rule was written down. It is that **the guard
has to be executable, in the script, not remembered**. Both fixed versions now assert a minimum input
count before comparing, so "identical" can never mean "identically absent".

---

## Re-measured on a rig 2026-08-21 — the central symptom is FIXED, the residue is not

A NATS wipe was driven deliberately on the cluster kind rig (delete the `nats` pod; its `data` volume
is `emptyDir`, so JetStream goes with it). Both EOD durables came back on their own.

**What this issue said would happen, and did not:**

| Consumer | Durable | 2026-08-19 outcome | 2026-08-21 outcome |
|---|---|---|---|
| position-service `EodPnlConsumer` | `eod-pnl` | durable gone, never re-created, **logged nothing at all** | detected and re-subscribed, **with a log line** |
| risk-extract `RiskExtractMain` | `risk-extract` | durable gone, never re-created | re-bound, logged `(re-bind)` |

Post-wipe the broker reported `TRADERX_EOD consumers=2`, both durables present by name. The
position-service line now reads:

```
WARN  EOD durable 'eod-pnl' is gone after a NATS reconnect (a broker restart recreates
      JetStream state); re-subscribing
```

**This was already fixed before today, on a deployed image, and the issue never caught up.** The pod
was running `:yu17-jsrebind` — started the previous day, not rolled for this test, and the tag names
the fix. So the "never re-created" claim above describes a build that is no longer deployed.

**What is still true, and is now the whole issue:** the re-bind restores the *subscription*, not the
*messages*. `TRADERX_EOD` went from 2 messages to 0 across the wipe and nothing brought them back. A
consumer that re-binds to an empty stream is a healthy consumer of nothing, which is a quieter
failure than the original and still loses whatever `eod.prices.ready`/`eod.pnl.done` carried.

**Do not close this on the re-bind.** Re-scope it to the message loss, or make the deliberate call
that JetStream state is disposable here and close it as accepted — that decision belongs with the
storage decision recorded in `issues/resolved/nats-jetstream-state-is-ephemeral-decide-deliberately.md`,
which chose no PVC.

**Method note.** The stream and consumer counts were read from the broker's own monitoring endpoint,
`/jsz?streams=1&consumers=1`, reached on the **nats pod IP** — the `nats` Service publishes only
`4222` and `8081`, so port `8222` is not reachable through it. Read the counts before *and* after; a
post-wipe reading alone cannot tell a restored durable from one that was never lost.
