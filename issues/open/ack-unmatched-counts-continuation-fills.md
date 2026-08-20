# `ack_unmatched` counts the continuation fill of a crossing order, not a failure

**Root-caused 2026-08-20** on the GKE rig, closing the mechanism question the console lane left open
after establishing that the counter grows on an undisturbed rig and is zero for one submitter.

## What it actually counts

`ClusterGatewayMain.java:706-712`. A crossing order emits **ACCEPTED and then per-match-step FILL,
all under one apply, all carrying the one request id the gateway stamped on the offer**. The first
ack finds the pending, completes it, and **removes** it. The continuation fill for the same id then
finds the map empty, and:

```java
final PendingOrder p = inflight.onDirectAck(buffer.getLong(offset + 24));
if (p != null)                                   completePipelinedHead(p, buffer, offset, kind);
else if (buffer.getLong(offset + 24) != 0)       pipelineAcksUnmatched++;
```

The comment directly above says continuation fills "find the entry already gone and **are ignored**."
They are not ignored. They are counted, in the same bucket as genuinely foreign and stale-epoch acks.
The resting side of the same cross never lands here — its update carries the resting-class byte at
offset 21 and is filtered out one branch earlier — so the counter increments **once per aggressive
fill**, not once per trade.

## Why "needs concurrent submitters" was a confound

The lane's evidence — zero for a single actor, twice — is real but the variable is wrong.
Self-trade prevention stops one account crossing itself, so a single actor never produces an
aggressive fill and therefore never increments the counter. Concurrency was standing in for
*two different accounts*.

Separated on the rig with **one sequential submitter and two accounts**, 2s apart, no concurrency
anywhere:

| test | completed | unmatched | trades |
|---|---|---|---|
| same account, crossing pair | +2 | **+0** | +0 (STP) |
| two accounts, crossing pair | +2 | **+1** | +2 |
| two accounts, 3 crossing pairs | +6 | **+3** | — |

The third was run as a stated prediction before execution (`completed 1080, unmatched 624`) and hit
both numbers exactly.

## So the ratios in the lane's table were never mysterious

`unmatched / completed` is just the **share of orders that were the aggressor**. 0.500 means every
order paired into a cross; 0.333 means a third of them did; 0.000 means nothing crossed. Nothing is
being lost, misrouted, or stranded — and my earlier guess that the scale-out stranded acks was
wrong, as the lane established before I did.

## The defect is real but gateway-side only — it reaches no screen

**Corrected 2026-08-20, same day.** This file first argued the counter was dangerous *because* it sat
on a demo screen reading 0.5 to an audience. That premise was never checked, and it is false. The
console lane checked it; I then verified independently against the live rig:

- console source references exactly one pipeline stage, `ack_completed`, for the throughput rate
  (`metrics-panel.ts:160`) — `ack_unmatched` appears nowhere in it
- the metrics panel's raw-text block renders `/order-matcher/latency`, which carries only
  `latency_us` / `latency_count` segments
- the string `ack_unmatched` appears **zero** times across *every* JS chunk the page loads, not just
  the main bundle

So no audience sees this counter through the console. It is visible only to someone reading
`/metrics` directly — which is exactly what I was doing when I raised it.

That drops the severity to **housekeeping**: a misleading name and a wrong comment on an internal
endpoint. Worth fixing, not worth interrupting anything for.

1. **Fix the comment.** It asserts the opposite of the line below it, and that is the whole reason
   this cost a rig session instead of a read.
2. **Split or rename the bucket** when the file is next open. Continuation fills are expected flow;
   foreign and stale-epoch acks are the ones worth watching. Summed, neither question is answerable.

### How the overstatement happened

I reasoned from the counter's *shape* — an alarming name at a healthy 0.5 ratio — to a consequence
that required an exposure path I never looked for. The mechanism work was checked to the point of a
stated numeric prediction; the impact claim attached to it was not checked at all, and rode in on the
credibility of the part that was. **The evidence for a finding does not transfer to the claim about
what it costs.** Those are two claims and they need two checks.

## The rule

**A counter that sums an expected term with an exceptional one can only ever answer the question the
expected term dominates.** The name promised anomalies; the arithmetic delivered a fill rate. Same
family as the scope-vs-identity gap filed the same day: the number was correct and the label was the
defect.

Related: [[constant-scope-does-not-mean-comparable-series]]
