# A fresh epoch silently drops whole instrument classes from the tradeable set

> **The values below are a record, not a rig you can query.** Order refs (`1-66`), trade ids
> (`4060-S`), trace ids, security ids, pod names and run counts come from the epoch this was
> measured on. That epoch has been rolled and will be rolled again — order refs restart at 1, the
> symbol table is renumbered, trace ids follow the client order ids of a run that no longer exists.
> Read them as a worked example of the SHAPE. Do not look them up, and do not treat their absence
> on a current rig as evidence about this issue.

**Filed** 2026-08-19 by the coordinator, from the UI lane's finding, verified. **Open.**
Not a code defect — a bring-up gap with a demo-shaped consequence.

## What happens

`BlpRiskState` enables securities per epoch. A fresh epoch starts with none, and
`scripts/yu15/seed-proof-fixtures.sh` — which `run-proofs.sh` re-runs on every fresh epoch — seeds
**equities and bonds only**. It contains no option contract at all.

So after any epoch roll, **listed options are untradeable** and every option order returns
`UNKNOWN_SECURITY` — a reason code that reads like a bad symbol, not like a missing enablement. The
whole YU14 instrument class silently leaves the demo.

**Measured 2026-08-19** after the roll to `:yu17-ackB`: `AAPL261218C00260000` resolved fine
(`/resolve` → securityId 606) and the price publisher was marking it (~8.53), yet orders `1-2583`,
`1-2584`, `1-2585` were all **REJECTED**. The same contract had booked successfully in the *previous*
epoch. After a `/seed` of the two contracts, `1-2586` onward were accepted.

## Why it is not obvious

- `/resolve` **succeeds** — reference data knows the contract, so the symbol looks valid.
- The price publisher **marks it** — a price is streaming, so the instrument looks live.
- Only the *enablement* is missing, and its failure surfaces as `UNKNOWN_SECURITY`, which everybody
  reads as "wrong ticker".

The option-enabling script exists — `scripts/proofs/seed-option-chain.sh`, whose own header says *"the
engine silently rejects orders whose security is not enabled or has no price tick, and those rejects
surface nowhere on some paths"* — but it is **referenced by neither `run-proofs.sh` nor
`seed-proof-fixtures.sh`**. It only runs if somebody runs it by hand.

## The fix

Call `seed-option-chain.sh` (or fold its enablement into `seed-proof-fixtures.sh`) on the fresh-epoch
path, so the seeded universe matches the instrument classes the tier actually supports. Anything the
tier can trade should be enabled by the same step that enables equities and bonds — otherwise the
tradeable set depends on which proofs happened to run.

**When seeding, pass the instrument's live price, not a round number.** `POST /seed` sends a
`PRICE_TICK` at the price passed and that becomes the risk anchor — this is how
`yu08-algo-slicing.sh`, which passes 200 unconditionally, left IBM's anchor at 200 while the feed had
walked to ~185.

## A near-miss worth copying the caution from

Verifying this, the coordinator probed with an option order and it was **accepted** — which looked like
a refutation of the whole finding. It was not: the lane's `/seed` had landed between the report and the
probe, so the probe measured the *repaired* state. The order ids settled it — `2583/2584/2585`
REJECTED, `2586+` NEW. **When checking a reported defect, establish whether a fix has already landed
before concluding the defect never existed**; a timestamp or an id ordering will usually say.

---

## Second, independent way the option class disappears: a suite run

Found 2026-08-19 while answering the UI lane's question about seeding side effects. This is a
different mechanism from the epoch roll above, with the same symptom, so it belongs here.

`scripts/proofs/yu15-option-persistence.sh` deletes the long-ticker rows to exercise the
`VARCHAR(15)`/`VARCHAR(16)` widen regression:

```
DELETE FROM trades    WHERE CHAR_LENGTH(security) > 15;
DELETE FROM positions WHERE CHAR_LENGTH(security) > 15;
DELETE FROM stocks    WHERE CHAR_LENGTH(ticker)   > 16;
```

Step 3b restores **only the `stocks` catalog rows**, through `POST /stocks`. `trades` and
`positions` are never restored. Every OCC symbol is longer than 15 characters, so **any full suite
run empties option trade and position history**.

The contracts stay *tradeable* — engine-side enablement lives in the cluster's deterministic state,
not the DB — so this is invisible on the order path and visible only on read surfaces. A UI option
blotter goes empty with no error anywhere.

Measured at the time of writing: `AAPL260918C00240000` held **one** 5-lot cross at 3.80 (the
`seed-option-chain.sh` smoke test). That is what a suite run removes.

CORRECTED 2026-08-19 — this first read "two 5-lot prints ... run twice". `trades` records **one row
per side**: ids are suffixed `-S`/`-B`, so a single 5-lot cross is two rows. The query behind the
wrong count projected `security, price, quantity` and dropped `id`, `accountid` and `side` — the
three columns that make a two-sided print self-evident. Reading a row count as an event count off a
projection that discarded the discriminator is the same error shape as filtering `status='NEW'` and
missing partially-filled depth: the arithmetic does not close, and nothing complains. The UI lane's
independent position count (+5 / -5 across the two accounts) is what falsified it.

Whether the deletion is right is arguable — it is the regression the proof exists to exercise. What
is not arguable is that it is an unrestored mutation of shared state on a rig other lanes demo from,
which is the discipline `vacuous-pass-audit` already names.

## Two collar facts that this class keeps being misdiagnosed against

Both established by reading `MatchingEngine`, not by inference, because two sessions have now
reasoned about "moving the anchor" from a wrong model:

- **A `/seed` price tick cannot move a mark once a trade has printed** (ADR-051). After the first
  print the mark is the last trade price, full stop. Re-seeding to "fix" a mark is a no-op.
- **The price collar is not anchored on the mark.** `slotFor()` anchors the band on the security's
  *first limit into that book* — engine state, per epoch, order-dependent on whichever proof
  submitted first. So a security's tradeable price range depends on proof execution order, and
  `/seed` never touches it.

Consequence for the suite: `scripts/proofs/yu13-otel-trace-join.sh` still submits AAPL at a
hardcoded `limitPrice 150.0`. Its sibling `yu13-otel-reject-trace-log-join.sh` was already fixed for
exactly this (moved to a fresh per-run ticker) with a comment explaining that a hardcoded price
against a traded security is not survivable. The trace-join sibling did not get that fix. Not
observed failing — filed as the same class, to be checked rather than assumed.

## MSFT is effectively untradeable at realistic prices on this rig

Measured 2026-08-19 from the regulatory report over the whole journal, after the console lane
observed MSFT refusing orders during a demo session and classified it (correctly, as a code
judgement) as "not a fault":

```
security   accepted limit prices     rejected limit prices
MSFT       180.0 only                384.11 .. 386.695   (12 rejections)
```

Every MSFT order ever accepted on this epoch is at **180.00**; every order at a realistic MSFT price
is refused. The book's band is anchored by `slotFor()` on the security's **first limit into that
book**, and an early demo order put that anchor at 180. Nothing since can move it:

- `/seed` at 388.50 cannot move the **mark** — ADR-051, a price tick seeds the mark only while no
  trade has printed, and MSFT printed at 180.
- The band is not derived from the mark **at all**, so even a movable mark would not repair it.

So the seeder's MSFT re-seed is inert twice over, and the security stays unusable for the rest of the
epoch. **This is a demo hazard, not a cosmetic one**: a demo driver who types a plausible MSFT order
gets a refusal, and the honest explanation ("the band is anchored where an unrelated order landed
hours ago") is not one anybody wants to give on stage.

Same root as the option-chain gap above — the fixture seeder decides what is usable, and it does not
deliberately anchor the band for the securities a demo will actually touch. Whatever closes the
option gap should anchor each demo security's band at a sane price in the same pass.

### The check that stops this being over-claimed

Six other securities also showed rejections, and the tempting read was "several books are
mis-anchored". They are not. Comparing accepted against rejected price ranges per security refutes it
for every one but MSFT:

```
security              accepted (orders / distinct px)     rejected
A                     NONE ACCEPTED EVER                  1  @ 0.0
AAPL260918P00220000   1 order,    1 px,  3.0              1  @ 0.42
AAPL261218C00260000   4 orders,   4 px,  3.0..9.907       3  @ 8.8..8.86
BAC                   2 orders,   1 px,  40.0             1  @ 40.0
IBM                   2573 orders, 53 px, 180.607..200.0  10 @ 183.44..900.0
MSFT                  2 orders,   1 px,  180.0            12 @ 384.11..386.695
UST-BILL-20270812     5 orders,   1 px,  0.96             1  @ 0.96
```

A band problem shows as refusals that all sit **outside** the range the book has already accepted.
A refusal from *inside* that range means the band cannot be the cause (the IBM sample carries
`accountId 99999` — the proofs' deliberate unknown-account arm). **Check for a refusal inside the
accepted range before blaming the band.**

> **Superseded, 2026-08-21.** This section first said *"a band problem produces a disjoint
> accepted/rejected split; an overlap means another cause"*, and that rule **inverts the answer for
> the commonest case**. A collar refuses on BOTH sides of its band, so the refused min/max straddles
> the accepted prices and the two ranges overlap *even when the band is exactly the cause* —
> disjointness then reports a band's own signature as its absence. Measured on the cloud rig: EXC
> accepted at 150 and refused at 100 and across 180–210, which the disjointness rule called "some
> other cause". The console now tests **containment** — no refused price inside the accepted range —
> which handles one-sided and two-sided bands alike. Kept rather than deleted because the wrong rule
> is the intuitive one and will be re-derived by the next reader otherwise.

### Two guards the containment rule needs, or it over-claims on its own

Both found by the console lane building this into a screen, and both are cases the rule as first
written gets WRONG:

1. **Zero accepted orders supports no verdict.** `A` has never been accepted at all — one refusal at
   price 0.0 and nothing else. "Every refusal is outside the accepted range" is trivially true of an
   empty range, so the bare rule condemns it as mis-anchored; the lane's first pass did exactly that.
   Report "never accepted" and stop.
2. **Count the samples before believing the split.** `AAPL260918P00220000` separates at **1 accepted
   vs 1 refused**. That is two data points, not evidence: a lone refusal can fall outside a lone
   accepted price by luck rather than by a band. Below roughly three either side, mark the finding
   thin rather than confident — it can be flagged and uncertain at once.

So the usable rule is three-part, in order: *no accepted orders → no verdict; too few samples → thin;
only then — every refusal outside the accepted range → band; any refusal inside it → other cause.*

### Read the column headings before quoting a number

The first version of this table printed `(n=53)` for IBM, meaning **53 distinct price levels**. It
was quoted onward as "53 accepted orders", which is wrong by a factor of ~49 — IBM has **2573
accepted orders** across those 53 prices. Distinct-value counts and event counts answer different
questions and look identical in a summary table. Label which one is on the page.
