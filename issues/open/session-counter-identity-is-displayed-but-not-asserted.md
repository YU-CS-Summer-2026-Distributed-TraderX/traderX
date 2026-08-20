# The session's counter identity is displayed but never asserted

**Raised 2026-08-20** by the console lane, at its own request, after the reading it caused.

## The identity

In the console's live trading session:

```
sent = accepted + rejected + skipped
```

`fire()` increments `sent` unconditionally at the top, then takes one of three exits: an accepted
order, a rejected one, or a `noPrice` skip when the chosen instrument has no live tick at that
instant. The three buckets are exhaustive **by construction of that one function** — and by nothing
else.

## What it cost when the identity was invisible

A 2-minute session reported *"1200 orders sent, 1195 accepted"*. The 5 were `noPrice` skips and were
already counted, but the count was rendered only as fallback text in the "last reject" cell — so it
appeared when there were no rejections and **vanished the moment an actor had one**. The counter was
least visible exactly when a session was most interesting.

Server-side there was no loss at all: gateway `offer_attempt 1380 = offer_success = ack_completed`,
and `1346 accepted + 34 rejected = 1380`. The five never left the browser. But **the arithmetic did
not close on screen**, so the only available reading was that five orders had disappeared — which is
the reading a demo audience would reach too.

Now fixed: `skipped` has its own column and total, and a tally line states the identity. Verified
live with a deliberately priceless symbol in the pool, so the skips were real rather than simulated:
`20 sent = 9 accepted + 0 rejected + 11 skipped`, summing across the per-actor column.

## What is still open, and why it is worth a line

**The identity is enforced in one function and displayed on one page. It is asserted nowhere.** A
future edit that increments `sent` on a fourth path — a retry, a batch pre-check, an early return
before the exit branches — silently reopens exactly the reading above, and the only symptom is a
tally that no longer adds up on a screen nobody is checking arithmetic on.

The lane left it deliberately: testing it properly needs HTTP mocking around `fire()`, which is more
apparatus than the fix warranted at the time. That is a reasonable call and this is the record of it,
not a complaint.

The cheap version, if anyone wants it before the full test: assert the identity **at render time** —
the tally line already computes both sides, so it could refuse to display a total it cannot balance
rather than printing one that silently does not. A panel that says "these do not add up" is worth
more than one that quietly prints numbers which do not.

## The general shape

This is the counting twin of the rule the console already carries: *a result is not evidence unless
something in it proves the check ran.* Here the buckets are exhaustive only because one function
happens to be written that way, and nothing anywhere would notice if it stopped being true.
