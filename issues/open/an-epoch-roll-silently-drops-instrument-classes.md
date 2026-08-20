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
