# A constant aggregation scope does not mean the counters are the same series

**Found 2026-08-20** on the GKE rig, closing the console lane's "landed but not exercised" item for
the gateway-scope fix (`9fc3f071`). The fix is correct. This is the case one layer under it.

## What was being closed

`server.mjs` discovers gateway pods at runtime and sums whatever answers, so a partial answer used
to yield a total over a subset under a label that said the whole system — and because `throughput`
differences the previous acks, a gateway dropping out made the counter FALL and rendered a
**negative acks/s**. The fix exposes the scope in `X-Traderx-Gateways-Aggregated`, surfaces it to
the client, suppresses the rate, and states the narrowed scope.

Driven for real rather than simulated: `kubectl scale deploy/cluster-gateway 3 -> 2 -> 3`.
Header reported `3`, then `2` across two consecutive polls, then `3` again. The scope seam is closed.

## The gap the restore exposed

The third gateway came back under a **new pod name** (`…-m6lfn` -> `…-v59hp`) and its counters
came back at **zero**:

```
gw0: ack_completed=465   gw1: ack_completed=473   gw2: ack_completed=0
sum 938, x-traderx-gateways-aggregated: 3
```

Scope is 3 of 3 — full, by the only measure the header reports — but **the sum is not the same sum**.
One of its three terms restarted. Had that pod carried 400 acks before it was replaced, the total
would have dropped by 400 with the scope indicator saying nothing was wrong.

This is not exotic. Pod replacement is the normal outcome of a crash, an eviction, a node repair, a
rollout, or an HPA scale event — every one of them resets a member of the sum **without changing its
cardinality**.

## What still holds, and what does not

**The defence holds.** The rate suppression triggers on the counter decreasing, not on the scope
narrowing, so this renders a dash rather than a negative number. Nothing false gets displayed.

**The diagnosis does not.** With scope at 3 of 3 the panel has no narrowed-scope story to tell, so
the user gets a dash with no reason — or, worse, reaches for the scope explanation that is sitting
right there and is wrong. A suppression whose stated cause is wrong teaches the wrong lesson about
the rig.

## The cheap discriminator, if it is worth fixing

Count is the wrong identity. `/gateways` already returns pod **names**; comparing the name set
between polls distinguishes the two cases exactly:

- set shrank -> narrowed scope, existing message is right
- set changed at constant size -> a series restarted, the difference is not a rate of anything
- set identical, counter fell -> a real anomaly, and the only one that deserves alarm

## The rule

**A guard on the size of a set does not guard its identity.** Cardinality was the visible property,
so it became the check; the property that actually made the numbers comparable was *which* members,
and nothing was watching it. Any time a total is differenced across polls, the question is not "did
I sum the same number of things" but "did I sum the same things".

Sibling of the counter-identity work resolved the same day: there, the invariant was displayed but
not asserted. Here, the invariant is asserted — against the wrong quantity.

## Unattributed observation, recorded so it is not lost

At the same reading, `ack_unmatched` was 274 + 299 = 573 against 938 `ack_completed`. Some of that is
plausibly my own scale event stranding acks, which is the known correlation-offset behaviour. I have
no before-baseline and am **not** claiming a defect — recorded only so the ratio is on file if it
shows up again on an undisturbed rig.
