# price-publisher crashes on the day before a Treasury bill matures

**Status:** FIXED IN SOURCE 2026-09-15 on branch `platform-fixes` (base `789df72a`), not yet
deployed and not yet propagated to other branches. Verified before the fix: the operative
`treasury-pricing.js` was byte-identical across all six worktrees (sha prefix `d10ef9bf3f54`) and
every copy still threw, so this was uniformly unfixed rather than partly propagated.

The report below was one defect short. There were two, and the second is the reason a single bond
silenced all 44 instruments:

1. **The yield solve threw** for a clean price no yield can reproduce. `ytmPercent` now returns
   `null` — the absent-yield contract FR-CDM20 already defines at maturity and every consumer
   already types as nullable. `yieldFromCleanPrice` stays strict for callers that want the error,
   and a non-positive price still throws rather than degrading to `null`.
2. **The publish loop had no per-instrument guard.** The batch body runs inside a `setTimeout`
   callback, so an escaping throw is an uncaught exception that ends the process, and the next tick
   was scheduled only *after* the body, so nothing rescheduled the feed either. A failing
   instrument is now skipped for that round, reported once per instrument, with the reschedule
   moved into a `finally`.

   Only a **pricing** failure leaves state untouched. The first version of this fix claimed that of
   every failure, which was wrong: the tick is committed to `state.prices` before the payload is
   built or published. The three cases are now distinguished, and each reports what is actually
   true:

   | Failure | State | Published |
   |---|---|---|
   | Pricing (the walk or curve threw) | unchanged | nothing |
   | Preparation (payload or encoding threw) | new tick already committed | nothing |
   | Transport (a publish threw) | new tick already committed | possibly partial: the JSON envelope and the binary tick are separate messages |

   No rollback is attempted in any case. State is a walk, not a ledger, so re-deriving a previous
   tick would invent a price; the next round republishes from committed state.

Measured on the seeded `UST-BILL-20261112` (98.969), which shows the yield diverging into the
failure rather than a cliff:

| Date | Matured | Yield before | Yield after |
|---|---|---|---|
| 2026-11-10 | no | 1146.419799 | 1146.419799 |
| 2026-11-11 | no | **throws** | `null` |
| 2026-11-12 | yes | `null` | `null` |
| 2026-11-13 | yes | `null` | `null` |

Verification: 103/103 tests pass in the generated tree (`generated/code/target-generated/price-publisher`),
which is byte-identical to the source layer. The two new tests fail against the pre-fix module.
A stand-in for the old loop, using the pre-fix module, exits non-zero having published nothing and
without rescheduling.

Not covered: no live deployment was made under this fix, so the running cluster still carries the
old image and the live `PRICE_TICKERS` workaround from 2026-09-09.

## Original report follows

## Symptom

`price-publisher` crashloops at startup with

    Error: clean price 99.681 is not attainable for 2026-09-10
        at yieldFromCleanPrice (src/treasury-pricing.js:386)

Every other instrument stops quoting with it: the throw is uncaught in `toPayload`
(`ytmPercent` → `yieldFromCleanPrice`), so one bad bond takes the whole feed down.

## Cause

`UST-BILL-20260910` has a seeded clean price of 99.681 and, with hours left to maturity,
no yield in the solver's bracket reproduces it. `isMatured` only trips at `ts >= maturity`,
so the day before maturity is priced and unsolvable.

## Live workaround (2026-09-09, demo)

`kubectl set env deploy/price-publisher PRICE_TICKERS=<list without UST-BILL-20260910>`.
Live-only; the manifest still lists the bill. After 2026-09-10 the bill is matured and the
manifest value works again.

## Fix

`ytmPercent` should return `null` (quote without a yield) when the price is unattainable
instead of letting the throw escape — one instrument's bad math must not stop the feed.
Operative layer: `specs/YU16-cdm-instruments/generation/runtime-overrides/price-publisher/src/treasury-pricing.js`.
