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

## The actual defect is the name, and it is on a demo screen

The counter is benign by construction, but `ack_unmatched` next to `ack_completed` reads as a
failure count, and at a healthy 0.5 ratio it reads as **a third of all acks failing to match**. On a
screen shown to an audience that is the worst possible framing of "the book is crossing normally."

Two fixes, both cheap, neither yet made:

1. **Fix the comment** — it asserts the opposite of what the line below it does, and it is the
   reason this took a rig to settle rather than a read.
2. **Split the bucket or rename it.** Continuation fills are expected flow; foreign and stale-epoch
   acks are the ones worth watching. Summed together, neither question can be answered — and the
   large, boring term hides the small, interesting one.

## The rule

**A counter that sums an expected term with an exceptional one can only ever answer the question the
expected term dominates.** The name promised anomalies; the arithmetic delivered a fill rate. Same
family as the scope-vs-identity gap filed the same day: the number was correct and the label was the
defect.

Related: [[constant-scope-does-not-mean-comparable-series]]
