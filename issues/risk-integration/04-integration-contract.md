# RI-04 — Shared integration contract and canonical engine reuse

Updated: 2026-10-07. Status: in progress: engine/service reviews completed; portfolio contract, activation and financial acceptance remain. Maintainer: coordinator; original delivery notes below retain their dates.

Sources: [spec draft](../../docs/risk-integration/spec-kit-draft/README.md), [draft tasks](../../docs/risk-integration/spec-kit-draft/tasks.md), [initial evidence](../../docs/risk-integration/spec-kit-draft/acceptance/initial-results.md).

The initial starter run against e4ca50b passed 1 and failed 4 cases. Alex's local HEAD is now 992db30. Those old failures are historical observations, not a verdict on current code.

- [ ] Review the new engine diff and rerun relevant request-option, submission-binding, duplicate-execution and recovery acceptance cases.
- [ ] Reconcile Alex's responses with the draft; record which requirements are accepted and which remain proposed.
- [ ] Pin authoritative schema/spec/profile versions and define ownership and compatibility policy.
- [ ] Audit adapter-to-engine calls; reuse canonical financial components and document justified separate execution paths.
- [ ] Keep independent numerical reference checks in acceptance tooling, separated from production result handling.
- [ ] Implement only remaining consumer migration: identity/schema/coverage/provenance validation, immutable original bytes and explicit unsupported results.
- [ ] Reverify terms/accrual/date/settlement concerns from older reviews before reopening them.
- [ ] Retire provisional/mock paths only after their intended replacements are verified; retain useful fixture tests.

Next: Agree portfolio financial/capability contracts and engine activation; earlier dated four-gap findings require current-source revalidation before reopening.

## September 23 recheck

Engine 992db30 is unchanged from Friday; engine source matches e4ca50b. Its integration suite passed 750 tests with one skipped; proposed-contract starter remains 1 pass/4 failures (submission conflict on cache hit, duplicate execution on overlapping retries, unsupported currency, unknown calculation). TraderX pricing suite passed 11. Checkout remained clean. Local tests only; full ORE/numerical suite not run. Evidence: shared workspace `coordination/eod-integration/review-evidence/alex-992db30-20260923/review.md` and adjacent logs.

Next: agree ownership of engine corrections and packaging/order-type lanes. Proposed Codex containerization and Claude order types are awaiting user confirmation; no dispatch or implementation started.


2026-10-07 status reconciliation: October7 audit of7474c53→2df78cb: existing EOD A02–A05 failures reproduced; mixed-currency market-risk totals and queue startup acknowledgment defects also reproduced. See RI-15/16 and parent coordination/eod-integration/review-evidence/alex-audit-20261007/review.md. New worker does not solve EOD contract gaps.

2026-10-07 review reconciliation: RI-16 local service foundation accepted for its documented compatibility scope at engine clone `796ca5dfb9d9d95ffb125fdd29ee041e678e910e`. Submission binding, overlapping ownership, request validation and lost-response recovery are source-tested locally; coordinator reran 49 distinct focused cases. This supersedes earlier statements that those exact gaps remain unimplemented. Engine changes remain in the separate clone; no original-checkout integration, deployment or general financial/worker-fleet acceptance. Full-envelope calculation subsets and USD numerical admission are explicitly documented. Broader readiness, retention, backpressure, mixed-currency analytics and scheduling remain open.

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.
