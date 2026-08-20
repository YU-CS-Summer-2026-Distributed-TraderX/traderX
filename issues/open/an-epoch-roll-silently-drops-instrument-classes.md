# A fresh epoch silently drops whole instrument classes from the tradeable set

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
