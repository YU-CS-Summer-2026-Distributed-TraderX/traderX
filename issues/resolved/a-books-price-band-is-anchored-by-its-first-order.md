# A book's price band is anchored by its first order, so a security can be permanently untradeable

**Lifted 2026-08-21** out of `issues/resolved/an-epoch-roll-silently-drops-instrument-classes.md`,
where it was filed 2026-08-19 as a related section. **Resolved 2026-08-23** — the band now follows
the market (ADR-066, `specs/YU17-otc-rates/system/adr-066-price-band-follows-the-market.md`). See
*Resolution* at the end. The mechanism and the diagnostic rule below are kept as written: the
diagnostic is still how to read a refusal table, and the mechanism is what the fix replaced.

## Resolution (2026-08-23)

yaakov chose the full re-anchoring collar in the engine over two smaller options — in particular
over crossing the demo equities at live prices in the seeder, which would have left the mechanism
intact (a stray order could still poison a book mid-epoch) and forced every IBM-at-200 proof to move.

**What changed.** `LimitBook` can `rebase()` — re-index every occupied slot to a new `baseLevel`
(cold path, O(open orders)). `MatchingEngine.bandSlot()` replaces the bare `slotFor()` on the new-
order and replace paths: a new book anchors on the security's *reference* (the sequenced feed price
in `BlpRiskState.lastPrice[]`, else the mark, else — only when neither exists — the first limit, the
old rule kept as the floor); a limit the band refuses is re-judged against a band centred on the
reference, and if that admits it the book re-anchors there, cancelling first any resting order the
new band cannot hold, through the STP unsolicited-cancel path (`FLAG_CANCEL | FLAG_RESTING_UPDATE`,
reason `PRICE_COLLAR` on the ack). A replace may not strand the order it is replacing (refused, the
order stands). Lazy on purpose: a tick never touches a book, and the band moves only when moving it
changes a refusal into an acceptance — so it cannot thrash. Counters `traderx_band_reanchors` and
`traderx_band_stranded_cancels` per member. `SNAPSHOT_FORMAT` stays 7 (reasoned at the constant).

**Measured on the cluster rig, the same sequence one image apart** (`scripts/proofs/yu17-band-follows-market.sh`,
fresh ticker, `/seed` @180 → BUY @180 rests → `/seed` @388 → SELL @385 → SELL @480 → BUY @385):

| build | SELL @385 | SELL @480 | BUY @385 | trades | stray 180 bid |
|---|---|---|---|---|---|
| `yu17-otcaudit` (pre) | REFUSED PRICE_COLLAR | REFUSED PRICE_COLLAR | REFUSED PRICE_COLLAR | +0 | still resting |
| `yu17-band` (post) | ACCEPTED | REFUSED PRICE_COLLAR | ACCEPTED, crossed | +1 | cancelled (stranded=1) |

Three members agreed on book digest and counters both times. The falsification arm (480, $92 off the
market) refuses on both builds: the collar still collars. The MSFT shape itself was re-run on the
new epoch: MSFT seeded at the feed (388.15) → order at 388.15 ACCEPTED (re-anchor from a stray 180
that had got in first), stray 180 now REFUSED.

**Carried.** `yu03-risk-proof` seeded BAC at 200 and traded it at 40 — it passed before only because
the band ignored the seed; it now seeds each ticker at the price it trades. `yu13-otel-trace-join`
posted AAPL at a hardcoded 150 and `curl -s` without `-f` swallowed the 422, so it "passed" over
rejections; it now quotes at the live price and fails on a refusal. Engine + service suites 581/6
modules green; six new tests in `LimitOrderBookTest`, four of which fail on the old behaviour
(the other two are anti-thrash guards and pass on both by design).

**Left open, in its own file:** `issues/open/the-cluster-rig-sequences-no-live-ticks.md` — on this
rig only `/seed` puts a `PRICE_TICK` into the log (the ADR-045 feed adapter is not deployed), so the
band follows the seeded reference, not the publisher's walk. NVDA seeded at 200 refuses 893.

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
