# RI-01 — Order types and lifecycle

Updated: 2026-10-07. Status: in progress: all seven order types/lifecycle integrated locally; SC-OT35 performance and broader HA acceptance remain. Maintainer: coordinator; original delivery notes below retain their dates.

## Baseline

YU13 defines market and limit matching; the current YU17 matching engine retains those paths. Audit API/UI exposure and tests before calling anything missing. Source: [YU13 specification](../../specs/YU13-limit-order-book/spec.md). Professor deck: `01-Intro-to-Financial-Markets.pptx`, slides 13–15, supplied locally in Downloads; it is educational input, not an implementation contract.

## Authorized delivery — all seven types together

- [ ] Inventory order-type × time-in-force support through API, engine, snapshots and UI.
- [ ] Specify and implement stop and stop-limit; define trade/quote trigger, gap behavior, trigger ordering and revalidation of reserved risk.
- [ ] Implement iceberg with specified display/reserve quantities and replenishment priority.
- [ ] Implement trailing-stop with specified high/low watermark, trail units and deterministic trigger updates.
- [ ] Implement pegged orders with specified reference, offsets, caps and repricing priority. Decide genuine consolidated NBBO versus an explicitly labeled local-book reference; do not conflate them.
- [ ] Define Day, GTC, IOC and FOK eligibility per order type, trading-session clock, expiry and atomic FOK behavior. Existing market remainder cancellation alone does not prove general IOC/FOK support.
- [ ] Cover partial fills, cancel/replace, self-trade prevention, credit reservation and release, replay, snapshots, failover and UI states.
- [ ] Expand the component pack at specs/YU18-risk-integration/components/order-types; use a task worktree if needed, not a new state ID.

## Acceptance and next action

Next: Maintain seven-type functional regressions. SC-OT35 deployment-profile performance remains in RI13; broader recovery/HA acceptance remains in RI06/RI07. Do not reassign implemented order types.

2026-09-23: User selected component-based development within YU18-risk-integration; this supersedes the prior new-state plan. Lane dispatch remains pending.

20260923T181555Z: User authorized assignment. Board message `20260923T181555Z-coordinator-order-types-b9257b.txt`. Proposed worktree traderX-order-types, branch claude/order-types, base 05405897. First deliverable: support matrix and complete component spec for review before engine edits. No active execution claimed.

September 23 review: Claude worktree `traderX-order-types`, branch `claude/order-types`, base `d6ca3330`. Spec checkpoint requires four corrections before implementation: actual-trade provenance, legacy zero-price market compatibility, active console audit, and trigger latching/total ordering. Baseline tests continue independently; no engine implementation accepted. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri01-ri02-20260923/review.md`. Next: corrected spec and explicit STOP reservation decision.

Baseline delivery arrived during closeout: 20260923T190223Z-claude-order-types-baseline-ready.txt reports five new tests and 506 passing order-matcher tests, with three mutation checks. Full service-suite guard remains red because only order-matcher ran; do not call this a full hosted pass. Commits 00456453 and a370734a. Baseline source spot-reviewed, execution not independently repeated. Four spec findings remain applicable after heading-only restructure.

20260923T195429Z: User expanded RI-01 to one complete delivery: market, limit, stop, stop-limit, iceberg, pegged, trailing stop, with Day/GTC/IOC/FOK eligibility and behavior. Supersedes historical later-milestone/queued wording. Claude retains ownership; internal sequencing is allowed but remaining types must not be deferred. Full UI/API/engine/recovery/verification scope; no cloud authorization. Assignment: `20260923T195429Z-coordinator-order-types-full-scope.txt`.

20260923T214226Z: Revision 2 at `ecc40169` reviewed: previous four findings addressed in design, but new corrections required for peg risk bounds, replenishment/FOK/revalidation, dated DAY lifecycle and bounded cascade failure. Component validator and whitespace checks passed; no runtime implementation delivered. All seven remain one delivery. FIX pegged/trailing coverage and economic reservation still need resolution. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri01-design-r2-20260923/review.md`. Next: corrected full-scope checkpoint; resolve lane hook classification before writes.

20260923T231403Z: Revision 3 `e4c48163` reviewed. R2 blockers resolved in design; local implementation cleared with required consensus-configuration, exhaustion-test and range/fixture corrections (no additional full spec-only milestone). OPEN-D5 user question pending; no runtime feature implementation delivered yet. Next: exact path claim and full-scope implementation, retaining dependent-policy hold. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri01-design-r3-20260923/review.md`.

20260923T231432Z: User approved stop-price reservation while pending and trigger-time revalidation (trailing: admission stop level), with documented legacy gap limitation. OPEN-D5 resolved; dependent-policy hold lifted. Claude may proceed with all seven types under r3 review requirements. Board: `20260923T231432Z-coordinator-stop-policy-approved.txt`.

20260924T010239Z: Full seven-type implementation delivered at `0ede1370bd320ca009fb59847ee34e5a69bd4174`. Coordinator independently passed48 focused tests and allocation gates, verified28 source/generated overrides, but reproduced reset-ack kind collision, already-through trailing replace acceptance and typed REST truncation/field-presence errors. Reported latency regression remains open. Integration held; corrections returned to Claude. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri01-implementation-20260924/review.md`.

20260924T012946Z: Corrected delivery c41e508b/f6914d52 reviewed. I1/I2 corrected; 55 independent focused tests and five allocation checks pass. I3 still loses original JSON decimal precision before validation; I4 latency residual requires resolution. Coordinator owns next triage; Claude released and no further substantial work assigned due to user quota constraint. Integration remains held. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri01-correction-review-20260924/review.md`.

20260924T014059Z: Coordinator fixed the remaining precision defect in released Claude worktree, commit `88f3a27feed91bc9c4fcdd25e97efcada83f9083` (not integrated). 56 focused tests/five allocation gates, renderer and source/generated parity pass; before-failure reproduced. I1–I3 closed in source. SC-OT35 latency remains open: next measured optimization or explicit user tradeoff acceptance, then combined integration. Claude has no new assignment. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri01-codex-precision-20260924/review.md`.

20260924T015029Z: User authorized dedicated Codex RI-01 latency lane from 88f3a27f. Isolated checkout/claim pending; scope controlled reproduction and safe optimization, then evidence to coordinator. Handoff: shared coordination/eod-integration/HANDOFF-CODEX-ORDER-TYPES-LATENCY.md. Integration remains held; no Claude assignment.

20260924T021245Z: Codex latency lane delivered documentation-only 53bc3bec; coordinator verified413 evidence hashes and paired runtime statistics. JDK21 overhead not reproduced, JDK25 inconclusive; no safe optimization retained. SC-OT35 still open, integration held. Next recommendation: controlled Linux/Temurin21 deployment-profile comparison with agreed bound. No new lane started. Evidence: `coordination/eod-integration/review-evidence/ri01-latency-20260924T015157Z/review.md`.

20260924T031900Z: User deferred GKE-profile latency until credits, leaving SC-OT35 unverified and clearing the local integration hold. Authorized new Codex lane: combine reviewed order-types53bc3bec with integration3244f7eb in isolated checkout, validate combined output, then audit/add missing CI/container checks (RI-08). Owner/CLAIM pending. Coordinator retains final integration checkout writes; all dirty files preserved. Handoff: shared coordination/eod-integration/HANDOFF-CODEX-INTEGRATION-CI.md.

20260924T034152Z: Coordinator reviewed and integrated lane2f8359bd at `61b4c45e04ac53a461a985088d46d19603cbc11f`; acceptance documentation `66baef7556943b4eff8c9d62b770824eaab5ccc6`. All400 evidence hashes verified, five CI gate controls and component validator independently pass;57 delivered files exact,11 pre-existing dirty/untracked files preserved through integration. RI-01 locally integrated; latency user-deferred/unverified. RI-08 local CI/container milestone accepted; hosted CI, external engine checkout wiring and deployment remain open. No push/deploy. Evidence: shared coordination/eod-integration/review-evidence/integration-ci-coordinator-20260924.

20260924T182201Z: F1 typed gateway scratch-buffer fix integrated `9931b3e5ab87bce2c554f72c77ffc1257f78689b` from narrow77f851b1. Independent3 encoding+1 actual consensus gateway tests and5 allocation gates pass on integration sources. RI-06 unfinished work excluded. RI-07 proof corrections R1-R3 and F3 remain open; Claude may rerun on committed fix after coordination. No push/deploy.

2026-09-24 coordinator: F3 trailing-stop publication accepted and integrated at 4c3b9c59. Independent 7 engine/replay + 2 MariaDB tests passed. Supplied local-live 31/31 twice reviewed with temporary C1 manifest workaround; fresh unmodified YU18 startup remains blocked by C1, correction assigned to recovery lane. No deployment or latency claim.

2026-09-25: Cross-account ownership-group self-match prevention queued separately as [RI-12](resolved/12-cross-account-self-match-prevention.md). Existing same-account checks do not cover common ownership across accounts. No implementation assigned.

2026-09-25: RI-12 cross-account groups implemented and locally verified at2de9dd8b. See RI-12 for matching, FOK, snapshot/leader-restart and Desk account evidence.

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.
