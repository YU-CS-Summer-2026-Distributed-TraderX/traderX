# An instrument added to the CDM catalog never reaches an already-deployed environment

**Filed** 2026-08-18 by the coordinator, root-caused from a suite failure. **Open.**
This is a product/deployment defect, not rig drift. It will behave identically on GKE.

## The rule that causes it

`reference-data`'s CDM view is explicitly a **view over the `stocks` table**, not over the seed
catalog (`instruments.service.ts`, class comment): *"Membership is the DB `stocks` table — one store,
two views — so a key created at runtime through `POST /stocks` appears here too, and the two views can
only 404 together."* `cdm-catalog.ts` only **enriches** a key that is already a member.

And the seeder runs once (`stocks.service.ts:23-28`):

> *One-time idempotent seed from the CSV (**only runs if `stocks` is empty**)*

So on any environment whose `stocks` table is already populated, **every instrument added to the
catalog afterwards is invisible forever.** Nothing reconciles the two.

## What it cost, measured

On the kind rig 2026-08-18 the catalog held **19** treasury/corporate seeds and the `stocks` table
held **9** — the 7 coupon Treasuries and 2 corporates present when the DB was first seeded. Missing:
all 4 bills, all 4 STRIPS, and 2 later corporates.

`yu16-bond-position` step 6 failed as a result, and **the failure shape is the dangerous part**:

- the order was **accepted** (HTTP 200) — the engine's book grid derives from the ticker prefix, so
  `UST-BILL-*` crossed exactly as ADR-060 claims it would
- the **trades reached SQL** — two rows, correct price
- only the **position** silently failed to book, because `trade-processor` correctly **fails closed**:
  `Treasury metadata resolution failed for UST-BILL-20270812: 404` →
  `Bond booking rejected (fail closed): reason=Bond reference metadata is unavailable`

An instrument that trades, settles into the trade blotter, and then has no position is worse than one
that is rejected outright. Every component behaved as designed; the composition is what fails.

## The rig was fixed; the defect was not

The 10 missing keys were added through the supported runtime path (`POST /stocks`, 201 each), chosen
over a raw SQL insert because **the seeder writes an outbox row per seed** and a direct insert would
skip it and diverge the control feed. `yu16-bond-position` then passed in full, including step 7,
which had never been reached before.

That is a rig repair. Any environment brought up before a catalog addition still has the gap.

## MEASURED AGAIN 2026-08-18, and the diagnosis was too narrow

The B lane hit the identical failure four hours after the repair: `yu16-bond-position` step 6 failed
again, with 527 stocks and **zero bills or STRIPS**. It re-repaired the same 10 keys the same way.

Investigating that, the missing piece: **`eod-price-db` has NO persistent volume.** Its only volume is
the `database-init-sql` configMap; the namespace's only PVCs belong to the cluster members. So the
database is **ephemeral** — every restart wipes it and re-initialises, `stocks` comes back empty, and
the one-time seed runs afresh.

**So this is not "environments brought up before a catalog addition".** It is **every DB restart,
forever**: a fresh seed reliably produces a `stocks` population with no bills and no STRIPS, and any
runtime `POST /stocks` repair is erased with it. Two independent instances in one day, four hours apart.

That also settles the alternative below: **documenting a manual `POST /stocks` per environment is not
merely fragile, it is non-viable** — the repair does not survive a pod restart, so the instruction would
have to be "re-apply after every restart, forever", which nobody will do and nothing checks.

**Open question, named rather than guessed:** `YU16_TREASURY_ROWS` in `load-csv-data.ts` maps ALL of
`TREASURY_SEEDS`, including the bills, so a fresh seed *should* contain them. It does not. Worth
establishing why before designing the fix — `loadCsvData` takes `supportedTickers`/`maxTickers`, and a
cap that truncates after the S&P rows would explain it exactly. Do not assume; measure.

## The durable fix

Make startup **reconcile** rather than seed-only-if-empty: for every key in the CDM catalog, ensure a
`stocks` row exists, creating the missing ones through the same path that writes the outbox row. That
is idempotent, cheap, and turns "add an instrument to the catalog" into a complete operation.

The narrower alternative — document that catalog additions require a manual `POST /stocks` per
environment — is **ruled out by the measurement above**: the DB is ephemeral, so the repair does not
survive a restart. It has already been forgotten once and erased once, and both times the symptom
surfaced two services away as a missing position.

## A second, smaller defect found alongside it

`scripts/proofs/yu16-bond-position.sh` step 6 fails with **"the bill did not book"**. The bill *had*
booked — the trades were in SQL at the correct price. What had not happened was the **position**
write. That message sent the first investigation to the wrong subsystem. It should distinguish
"no trade" from "trade without a position", which are different failures with different owners.

## Related

The same class is recorded in `feedback_additive_payload_strict_consumer`: an additive change on the
producer side is fatal to a strict consumer, and it surfaces far from its cause.
