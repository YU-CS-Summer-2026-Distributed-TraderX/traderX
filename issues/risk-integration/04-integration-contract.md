# RI-04 — Shared integration contract and canonical engine reuse

Updated: 2026-09-23. Status: review performed; contract gaps remain. Owner: unassigned; decisions require Yaakov and Alex. Dependencies: review latest engine and reconcile correspondence.

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

Next: review 992db30 before assigning defect fixes. Done requires agreed revisions and failing-before/passing-after evidence for actual remaining defects, plus a consumer compatibility record.

## September 23 recheck

Engine 992db30 is unchanged from Friday; engine source matches e4ca50b. Its integration suite passed 750 tests with one skipped; proposed-contract starter remains 1 pass/4 failures (submission conflict on cache hit, duplicate execution on overlapping retries, unsupported currency, unknown calculation). TraderX pricing suite passed 11. Checkout remained clean. Local tests only; full ORE/numerical suite not run. Evidence: shared workspace `coordination/eod-integration/review-evidence/alex-992db30-20260923/review.md` and adjacent logs.

Next: agree ownership of engine corrections and packaging/order-type lanes. Proposed Codex containerization and Claude order types are awaiting user confirmation; no dispatch or implementation started.
