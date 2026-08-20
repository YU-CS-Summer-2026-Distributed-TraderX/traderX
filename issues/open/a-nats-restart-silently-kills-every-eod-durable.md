# A NATS restart silently and permanently kills the EOD chain's durable consumers

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

## What still needs deciding

The restart is a remedy, not a fix. Candidates, cheapest first:

1. **A readiness probe that fails when the durable is not bound.** Both Deployments lack one, and the
   risk-extract comment says so explicitly. This is the smallest change that converts a silent death
   into a visible one.
2. **Detect consumer-deleted and re-subscribe.** The NATS client surfaces this; neither service acts
   on it.
3. **Accept it and document that any NATS restart requires restarting both consumers** — the honest
   minimum, and worth writing into the bring-up runbook regardless of which of the above lands.

## Why this one matters more than its size

The risk extract is the **external deliverable** — the artifact another team's engine consumes. Its
failure mode here is silence: no error, no restart, no alert, healthy pods, and an empty output
directory. Everything about this incident was invisible until someone went looking for a file that
should have existed.

**A diagnostic that would have caught it in one command:** `consumer_count` on `TRADERX_EOD` should
be 2. It was 0.
