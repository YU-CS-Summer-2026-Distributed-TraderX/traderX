# RI-05 — Market inputs, provenance and supported coverage

Updated: 2026-10-07. Status: in progress: market-input foundations exist; broader FX/spot/curve readiness, observation provenance and conventions remain. Maintainer: coordinator; original delivery notes below retain their dates.

Existing market packaging and assumed-curve Treasury acceptance are foundations. The September 18 EUR/GBP/JPY swap submissions returned PRICE_MISSING; source traces that refusal to missing USD conversion rates for credit admission. This is distinct from curve-based swap valuation.

- [ ] Define explicit FX sources or clearly labeled demo fixtures and load/verify them during setup.
- [ ] Expose readiness per currency/instrument and a precise missing-FX message; never silently invent production inputs.
- [ ] Supply equity spot/FX and curves through the engine's accepted input schema, with timestamps and units.
- [ ] Carry observation time separately from arrival/availability time; reconcile the existing observation-time proposal before assigning implementation.
- [ ] Agree supported settlement/calendar/accrual and overnight-rate conventions; add capability/refusal tests.
- [ ] Publish a current instrument × calculation matrix, separating booking, model marks, accepted valuation and portfolio risk.
- [ ] Keep tape, modeled, simulated and carried prices distinct; keep assumed inputs visible in results and UI.

Next: Define remaining market-input readiness/provenance and supported coverage against the accepted engine contract.

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.
