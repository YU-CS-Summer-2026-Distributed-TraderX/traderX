# RI-15 — Connect the broader portfolio service and risk results

Updated: 2026-10-09. Status: owned C8 service/container integration complete; connected portfolio adapter/results and financial acceptance remain open. Maintainer: coordinator; dated history retained.

## Outcome and boundaries

Adapt TraderX portfolio exports to Alex's current market/trades/run-configuration request without duplicating pricing. Existing /eod/price remains a separate contract; the new /portfolio/price returns202/job_id for a durable worker. The /v2 alias is not a separately versioned API. Pin request, result and capabilities to a tested engine revision.

- Agree first multi-position USD bill/note scope, quantities/sign/units, settlement, valuation dates, accrued values and partial/refusal policy; synthetic acceptance with long/short positions and independent references.
- Reconcile the existing container against changed package/dependency requirements and worker entrypoint; mount queue/results/cache deliberately and verify restart behavior in an isolated container. Do not replace the accepted image just because the engine checkout advanced.
- Map trade identities and market provenance, retain immutable request/result custody, and preserve account/run authorization in intake and Desk results.
- Expose useful accepted outputs in Risk UI: portfolio/per-trade NPV, sensitivities, dated exposure profiles and later market-risk VaR/ES. Distinguish exposure simulation's risk-neutral measure from short-horizon market-risk assumptions, units, horizon, confidence levels, partial coverage and precision diagnostics.
- Market-risk and CAM calibration lack corresponding general HTTP routes as of audit; do not claim these are unlocked end to end merely because the library implements them. Coordinate any thin service wrapper; Alex owns pricing/model choices.
- Gate mixed-currency market risk on conversion or explicit refusal (audit R1). Treat Mac exact-zero precision-test failure as a numerical portability question, not automatically material pricing error. Record ORE simulation/sensitivity validation limits.

Next: agree the portfolio financial/API examples and connected TraderX adapter/intake/UI; original-engine adoption and deployment remain separately authorized.
Audit: parent coordination/eod-integration/review-evidence/alex-audit-20261007/review.md.

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.

October 7 new direct assignment: RI15/16 packaging-only container qualification in existing chat01a116fd-2fce-77f1-a561-3b03472f0722; portfolio adapter/financial contract remains open. CLAIM/checkouts pending; coordinator reviews before integration. No cloud, push or retained rig activation.

October 7 container milestone reviewed: packaging077ffac/image24206883 based on service796ca5df. Coordinator repeated14actual-container groups successfully; scoped LR03StageA passed. Original-engine incorporation, TraderX connected adapter/contract, general financial acceptance and fleet/readiness policies remain open. See live-rig/lr-03-risk-container-pipeline.md; no deployment/self-integration.


## October 9 approved owned-engine integration

Direct human approval covered exact reviewed `e770f912baed47214220ce92e3de99a876781c68` and owned target
`/Users/yaakov/dev/lmax/jax-risk-container-upgrade`, branch
`codex/risk-container-upgrade`. After RI20 validated closeout/release at
`d24078f0`, the target fast-forwarded safely from `077ffac` to that exact tree,
preserving history. All 97 runtime context hashes equal the measured image
`sha256:80c885f8305067421a4c34af3eeef95ab1eca5a38376e9dcc689f8fe8bd5483d` (build `98367871`). No image was rebuilt or container started for
unchanged bytes. Focused target tests and tree/hash checks verify incorporation.
Author 880 installed-image passes and 16 actual-container groups were reviewed;
coordinator independently passed 139 focused checks plus HTTP refusal/fresh-key
controls and inspected source/image identities. Lifecycle stubs remain separate
from real synthetic USD pricing/calibration. Prior 26/imageeecd failed the expanded
bound-ID provenance invariant; that failure and earlier profiles remain historical.

The bounded [C8 upgrade](resolved/ri16-upstream-c8-upgrade.md) and
[container qualification](resolved/ri15-16-risk-container-upgrade.md) are done
for this approved owned integration. Original Alex and service-review clone are
unchanged. This does not copy pricing into TraderX generation, activate a service,
push/deploy, or approve broad financial/fleet behavior. LR03 Stage A applies only
to the corrected named image/profile; connected Stage B remains unexecuted and
blocked on accepted account/run/provenance-bound adapter/contract.
