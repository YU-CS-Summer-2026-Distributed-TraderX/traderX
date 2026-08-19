# An instrument added to the CDM catalog never reaches an already-deployed environment

**Filed** 2026-08-18 by the coordinator, root-caused from a suite failure.
**Resolved 2026-08-18**: startup now reconciles the `stocks` table against the CSV catalog through
the outbox-writing path, and the proof that was eating the rows restores them itself. Both fixes
rig-verified — see "Resolution", below. The pre-fix analysis is kept as filed.

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

## MEASURED A THIRD TIME 2026-08-18 — the true eater, and both prior diagnoses were wrong

The open question above has an answer, and it is neither a fresh-seed truncation nor a DB restart:

- The deployed dist's `loadCsvData({})` returns **533 rows including all 19 bond keys**. Both env
  filters (`REFERENCE_DATA_SUPPORTED_TICKERS`, `REFERENCE_DATA_MAX_TICKERS`) are empty on the rig.
  A fresh seed does NOT omit the bills. The truncation hypothesis is dead.
- The DB pod had **not restarted** across the second eating. The ephemeral-DB observation is real
  (it has no PVC) but it was not the mechanism either.

The eater is **`scripts/proofs/yu15-option-persistence.sh` step 1**: as setup for the migration
proof it runs `DELETE FROM stocks WHERE CHAR_LENGTH(ticker) > 16` (and the same for the other
instrument-identifier tables), then widens the schema back via 900-migrations — but **never
restores the deleted rows**. Every catalog member above 16 characters is exactly the missing set:
the 4 bills and 2 later corporates at 17, the 4 STRIPS at 18 — the measured 9-present/10-missing
split, character for character. Every suite run re-ate the rows, which is why the repair "did not
survive" and why `yu16-bond-position` failed two proofs later in the same suite, far from the cause.

## Resolution (2026-08-18, rig-verified on kind-traderx-yu12-cluster)

Two independent fixes, either of which prevents the recurrence; both landed:

1. **Reconcile-on-startup** (`specs/YU04-durable-control-feeds/.../stocks.service.ts`, YU16+YU17):
   `onModuleInit` now diffs the CSV catalog against the `stocks` table and inserts every missing
   row **with its outbox row in one transaction** — the same ADR-021 path as `create()`, never raw
   SQL, so the durable control feed cannot diverge. Rows in the table but not the CSV are left
   alone (the table is the mutable universe; the CSV is only its floor). Verified live: a boot
   against the eaten table logged `Reconciled 10 catalog rows from CSV (531 already present)` and
   restored exactly the 10 keys with 10 outbox rows; an immediate second boot inserted nothing and
   wrote no duplicate outbox rows. This also closes the original filing: a catalog addition now
   reaches every deployed environment on its next reference-data restart.
2. **The proof restores what it deletes** (`scripts/proofs/yu15-option-persistence.sh`, new step
   3b, carried to YU15/YU16/YU17): the doomed rows are captured before the DELETE and re-created
   after the widen through in-cluster `POST /stocks` (201 asserted per row) — again the outbox
   path, not SQL. The step then WAITS for the gateway to apply its highest outbox version into
   consensus before exiting: each restore POST queues a control-feed event that is applied into
   the cluster asynchronously AFTER the proof would otherwise exit, and `yu16-bond-position`
   step 1 asserts the applied sequence is still — an unflushed tail is a real race against the
   next proof in a suite. (The back-to-back failures that prompted the wait turned out to be a
   different cause — the rig was still on YU15's cluster build, where FR-CDM16 boundary
   validation does not exist, so the two 422 probes themselves reached consensus. The wait stays:
   the tail race is real even if it was not that day's bullet.) Verified: option-persistence PASS
   with `10 long-ticker universe rows restored`, followed back-to-back by `yu16-bond-position`
   with no manual repair in between, on the YU16 build.

**Ancestor gap, recorded:** the seed-if-empty defect exists on every YU04+ branch, but the
reconcile is carried only to the catalog-bearing branches (YU16, YU17). Ancestors have no CDM
catalog and no >16-char tickers in their CSV, so the failure this issue documents cannot occur
there; carrying anyway would mean rebuilding reference-data images on branches with nothing to
reconcile.

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
