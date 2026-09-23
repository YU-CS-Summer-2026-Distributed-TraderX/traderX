# RI-05 — Market inputs, provenance and supported coverage

Updated: 2026-09-18. Status: partial foundations; fresh-start FX gap observed. Owner: unassigned. Dependencies: RI-04 capability/profile agreement and RI-09 instrument research.

Existing market packaging and assumed-curve Treasury acceptance are foundations. The September 18 EUR/GBP/JPY swap submissions returned PRICE_MISSING; source traces that refusal to missing USD conversion rates for credit admission. This is distinct from curve-based swap valuation.

- [ ] Define explicit FX sources or clearly labeled demo fixtures and load/verify them during setup.
- [ ] Expose readiness per currency/instrument and a precise missing-FX message; never silently invent production inputs.
- [ ] Supply equity spot/FX and curves through the engine's accepted input schema, with timestamps and units.
- [ ] Carry observation time separately from arrival/availability time; reconcile the existing observation-time proposal before assigning implementation.
- [ ] Agree supported settlement/calendar/accrual and overnight-rate conventions; add capability/refusal tests.
- [ ] Publish a current instrument × calculation matrix, separating booking, model marks, accepted valuation and portfolio risk.
- [ ] Keep tape, modeled, simulated and carried prices distinct; keep assumed inputs visible in results and UI.

Next: scope FX readiness for the next demo and review the current input contract. Done requires missing/stale/unsupported-input tests and explicit coverage in successful partial results. Treasury acceptance does not certify equities, options, swaps, corporate bonds or full portfolio risk.
