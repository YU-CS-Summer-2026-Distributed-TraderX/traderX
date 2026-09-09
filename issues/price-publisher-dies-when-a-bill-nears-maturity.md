# price-publisher crashes on the day before a Treasury bill matures

**Status:** OPEN (worked around live 2026-09-09; self-heals once the bill is matured)

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
