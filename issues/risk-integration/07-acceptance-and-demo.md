# RI-07 — Repeatable acceptance, demonstrations and UI clarity

Updated: 2026-09-18. Status: partial; local acceptance and live Treasury demo foundations exist. Owner: unassigned. Dependencies: applicable RI-01–06 milestones.

Evidence: September 18 focused pricing suite passed 11 tests; banner suite passed 5. Earlier live Treasury evidence is linked from [demo guide](../../docs/risk-integration/treasury-trade-demo.md). These are bounded checks, not complete risk certification. The book banner simplification was deployed September 18; do not reassign it.

- [ ] Create a concise maintained test/demo command guide with prerequisites, expected outputs and execution level.
- [ ] Make full-rig readiness explicit: new console, trading services, algo and observability.
- [ ] Isolate startup proof orders from manual trading. Today a proof sell filled one share of the user's resting 100-share IBM buy.
- [ ] Make clean-start versus retained-state behavior explicit and reproducible.
- [ ] Show a real trade/booking through original lineage to a validated risk result without re-entering economics.
- [ ] Clarify PRICE_MISSING for missing FX; clear or update the selected preset label after terms are manually changed.
- [ ] Add bounded recovery and negative cases to demos, not just successful responses.
- [ ] Keep unsupported analytics, assumed inputs and old results visible as such.

Next: document the safe local meeting checks, then repair startup proof isolation. Done requires another person to reproduce the demonstration from the guide and obtain named evidence. GKE remains at zero; no automatic restart.
