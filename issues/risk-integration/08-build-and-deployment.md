# RI-08 — Build, test and deployment automation

Updated: 2026-10-07. Status: in progress: local CI/container/image admission gates integrated; engine upgrade and deployment/rollback qualification remain. Maintainer: coordinator; original delivery notes below retain their dates.

This is distinct from RI-03, which schedules and runs risk workloads. P3 and risk-branch CI already received work; inspect their actual commits and workflows before assigning replacements.

- [ ] Inventory active workflows on traderX-risk-integration and the engine repository; record current source and available run evidence.
- [ ] Add missing contract/container gates using pinned revisions and deterministic fixtures.
- [ ] Verify image contents, platform and digest; record artifact provenance.
- [ ] Design controlled promotion, rollback and smoke checks for service updates.
- [ ] Keep CI tests isolated from the live demo and require explicit deployment scope.
- [ ] Document node lifecycle, persistent storage and job resource/cost limits.

Next: Qualify missing external-engine/deployment gates and controlled rollout; do not recreate the delivered local CI.

20260924T031900Z: User deferred GKE-profile latency until credits, leaving SC-OT35 unverified and clearing the local integration hold. Authorized new Codex lane: combine reviewed order-types53bc3bec with integration3244f7eb in isolated checkout, validate combined output, then audit/add missing CI/container checks (RI-08). Owner/CLAIM pending. Coordinator retains final integration checkout writes; all dirty files preserved. Handoff: shared coordination/eod-integration/HANDOFF-CODEX-INTEGRATION-CI.md.

20260924T034152Z: Coordinator reviewed and integrated lane2f8359bd at `61b4c45e04ac53a461a985088d46d19603cbc11f`; acceptance documentation `66baef7556943b4eff8c9d62b770824eaab5ccc6`. All400 evidence hashes verified, five CI gate controls and component validator independently pass;57 delivered files exact,11 pre-existing dirty/untracked files preserved through integration. RI-01 locally integrated; latency user-deferred/unverified. RI-08 local CI/container milestone accepted; hosted CI, external engine checkout wiring and deployment remain open. No push/deploy. Evidence: shared coordination/eod-integration/review-evidence/integration-ci-coordinator-20260924.

2026-09-24 coordinator reconciliation: user reports CI passes on push. Hosted pass is user-reported; exact run/SHA was not independently inspected here. Remove generic hosted-CI verification from the immediate task queue; external engine checkout wiring and deployment verification remain open. No new lane assigned.

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.
