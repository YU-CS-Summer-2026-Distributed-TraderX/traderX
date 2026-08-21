# A book's price band is anchored by its first order, so a security can be permanently untradeable

**Lifted 2026-08-21** out of `issues/resolved/an-epoch-roll-silently-drops-instrument-classes.md`,
where it was filed 2026-08-19 as a related section. That issue is now resolved; this is not, and
nobody reads `issues/resolved/` looking for open work. **Open. Nothing here was re-measured today** —
the values are the 2026-08-19 record, on an epoch that has since been rolled twice.

## The mechanism

Established by reading `MatchingEngine`, not by inference, because two sessions have now reasoned
about "moving the anchor" from a wrong model:

- **A `/seed` price tick cannot move a mark once a trade has printed** (ADR-051). After the first
  print the mark is the last trade price, full stop. Re-seeding to "fix" a mark is a no-op.
- **The price collar is not anchored on the mark.** `slotFor()` anchors the band on the security's
  *first limit into that book* — engine state, per epoch, order-dependent on whichever order
  arrived first. So a security's tradeable price range depends on execution order, and `/seed` never
  touches it.

This contradicts the model in `issues/open/HANDOFF-collar-price-sourcing.md`, which describes the
collar reference as "the random walk" (i.e. the feed). It is not the feed. Whoever picks that
handoff up should reconcile it against this file first.

## MSFT is effectively untradeable at realistic prices

Measured 2026-08-19 from the regulatory report over the whole journal, after the console lane
observed MSFT refusing orders during a demo session and classified it (correctly, as a code
judgement) as "not a fault":

```
security   accepted limit prices     rejected limit prices
MSFT       180.0 only                384.11 .. 386.695   (12 rejections)
```

Every MSFT order accepted on that epoch was at **180.00**; every order at a realistic MSFT price was
refused. An early demo order put the anchor at 180 and nothing since could move it:

- `/seed` at 388.50 cannot move the **mark** — ADR-051, and MSFT had printed at 180.
- The band is not derived from the mark **at all**, so even a movable mark would not repair it.

The seeder's MSFT re-seed is inert twice over, and the security stays unusable for the rest of the
epoch. **This is a demo hazard, not a cosmetic one**: a demo driver who types a plausible MSFT order
gets a refusal, and the honest explanation ("the band is anchored where an unrelated order landed
hours ago") is not one anybody wants to give on stage.

## What was deliberately NOT done about it

`scripts/yu15/seed-proof-fixtures.sh` still crosses IBM, NVDA and AAPL at a hardcoded `PRICE=200`
via `hold()`, which anchors those three books at 200 on every fresh epoch regardless of where the
feed has walked. The 2026-08-19 filing said whatever closes the option gap "should anchor each demo
security's band at a sane price in the same pass". **It did not**, on purpose: several committed
proofs are written against IBM at 200 and say so —
`yu10-fix-session.sh` (*"IBM at 200 is what seed-proof-fixtures.sh crosses and books reliably"*) and
`yu13-cancel-ingress.sh` — so moving those crossings to live prices changes the anchor those proofs
depend on. That is a suite-wide change with its own verification, not a rider on an enablement fix.

The option chain is **not** affected: the fix seeds the contracts and deliberately does not cross
them, so no option book gets anchored by the seeder at all. The first real order sets each band.

## Unchecked, same class

`scripts/proofs/yu13-otel-trace-join.sh` still submits AAPL at a hardcoded `limitPrice 150.0`. Its
sibling `yu13-otel-reject-trace-log-join.sh` was already fixed for exactly this (moved to a fresh
per-run ticker) with a comment explaining that a hardcoded price against a traded security is not
survivable. The trace-join sibling did not get that fix. **Not observed failing** — to be checked
rather than assumed.

## Diagnosing it: the containment rule, and the two guards it needs

A band problem shows as refusals that all sit **outside** the range the book has already accepted.
A refusal from *inside* that range means the band cannot be the cause. From the 2026-08-19 sample:

```
security              accepted (orders / distinct px)     rejected
A                     NONE ACCEPTED EVER                  1  @ 0.0
AAPL260918P00220000   1 order,    1 px,  3.0              1  @ 0.42
AAPL261218C00260000   4 orders,   4 px,  3.0..9.907       3  @ 8.8..8.86
BAC                   2 orders,   1 px,  40.0             1  @ 40.0
IBM                   2573 orders, 53 px, 180.607..200.0  10 @ 183.44..900.0
MSFT                  2 orders,   1 px,  180.0            12 @ 384.11..386.695
```

Six securities besides MSFT showed rejections and the tempting read was "several books are
mis-anchored". They are not — the IBM sample carries `accountId 99999`, the proofs' deliberate
unknown-account arm, refused from *inside* the accepted range.

> **Superseded, 2026-08-21.** This rule first read *"a band problem produces a disjoint
> accepted/rejected split; an overlap means another cause"*, and that **inverts the answer for the
> commonest case**. A collar refuses on BOTH sides of its band, so the refused min/max straddles the
> accepted prices and the two ranges overlap *even when the band is exactly the cause* —
> disjointness then reports a band's own signature as its absence. Measured on the cloud rig: EXC
> accepted at 150 and refused at 100 and across 180–210, which the disjointness rule called "some
> other cause". Test **containment** — no refused price inside the accepted range — which handles
> one-sided and two-sided bands alike. Kept rather than deleted because the wrong rule is the
> intuitive one and will be re-derived by the next reader otherwise.

Two guards, both found by the console lane building this into a screen, both cases the bare rule
gets WRONG:

1. **Zero accepted orders supports no verdict.** `A` has never been accepted at all — one refusal at
   0.0 and nothing else. "Every refusal is outside the accepted range" is trivially true of an empty
   range, so the bare rule condemns it as mis-anchored; the lane's first pass did exactly that.
   Report "never accepted" and stop.
2. **Count the samples before believing the split.** `AAPL260918P00220000` separates at **1 accepted
   vs 1 refused**. That is two data points, not evidence: a lone refusal can fall outside a lone
   accepted price by luck. Below roughly three either side, mark the finding thin rather than
   confident — it can be flagged and uncertain at once.

Usable rule, three parts in order: *no accepted orders → no verdict; too few samples → thin; only
then — every refusal outside the accepted range → band; any refusal inside it → other cause.*

### Read the column headings before quoting a number

The first version of that table printed `(n=53)` for IBM, meaning **53 distinct price levels**. It
was quoted onward as "53 accepted orders", wrong by a factor of ~49 — IBM has **2573 accepted
orders** across those 53 prices. Distinct-value counts and event counts answer different questions
and look identical in a summary table. Label which one is on the page.
