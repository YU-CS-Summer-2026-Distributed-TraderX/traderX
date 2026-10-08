# RI-15 — Connect the broader portfolio service and risk results

Updated: 2026-10-07. Status: queued broader portfolio adapter/container/results integration; financial/API contract acceptance required. Maintainer: coordinator; original delivery notes below retain their dates.

## Outcome and boundaries

Adapt TraderX portfolio exports to Alex's current market/trades/run-configuration request without duplicating pricing. Existing /eod/price remains a separate contract; the new /portfolio/price returns202/job_id for a durable worker. The /v2 alias is not a separately versioned API. Pin request, result and capabilities to a tested engine revision.

- Agree first multi-position USD bill/note scope, quantities/sign/units, settlement, valuation dates, accrued values and partial/refusal policy; synthetic acceptance with long/short positions and independent references.
- Reconcile the existing container against changed package/dependency requirements and worker entrypoint; mount queue/results/cache deliberately and verify restart behavior in an isolated container. Do not replace the accepted image just because the engine checkout advanced.
- Map trade identities and market provenance, retain immutable request/result custody, and preserve account/run authorization in intake and Desk results.
- Expose useful accepted outputs in Risk UI: portfolio/per-trade NPV, sensitivities, dated exposure profiles and later market-risk VaR/ES. Distinguish exposure simulation's risk-neutral measure from short-horizon market-risk assumptions, units, horizon, confidence levels, partial coverage and precision diagnostics.
- Market-risk and CAM calibration lack corresponding general HTTP routes as of audit; do not claim these are unlocked end to end merely because the library implements them. Coordinate any thin service wrapper; Alex owns pricing/model choices.
- Gate mixed-currency market risk on conversion or explicit refusal (audit R1). Treat Mac exact-zero precision-test failure as a numerical portability question, not automatically material pricing error. Record ORE simulation/sensitivity validation limits.

Next: Agree capability/financial examples, then upgrade adapter/container/intake/UI.
Audit: parent coordination/eod-integration/review-evidence/alex-audit-20261007/review.md.

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.

October 7 new direct assignment: RI15/16 packaging-only container qualification in existing chat01a116fd-2fce-77f1-a561-3b03472f0722; portfolio adapter/financial contract remains open. CLAIM/checkouts pending; coordinator reviews before integration. No cloud, push or retained rig activation.

October 7 container milestone reviewed: packaging077ffac/image24206883 based on service796ca5df. Coordinator repeated14actual-container groups successfully; scoped LR03StageA passed. Original-engine incorporation, TraderX connected adapter/contract, general financial acceptance and fleet/readiness policies remain open. See live-rig/lr-03-risk-container-pipeline.md; no deployment/self-integration.
