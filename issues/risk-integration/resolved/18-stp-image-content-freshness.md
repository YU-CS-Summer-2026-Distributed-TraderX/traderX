# RI-18 — STP image freshness depends on file timestamps

Updated: 2026-10-07. Status: done (source-integrated; local content/provenance checks passed). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Original finding (before integration)

The STP proof compares Docker Created against source mtimes. Regenerating unchanged sources can refuse a correct cached image indefinitely; mtimes are not content identity.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](../open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [x] Define a reproducible fingerprint of the complete image build inputs and selected runtime composition, including Dockerfile/build context and the synthesized pre/fix transformation.
- [x] Record immutable build provenance on the synthesized pre/fix artifacts; compare the selected source contents to recorded provenance at proof admission.
- [x] Refuse missing, malformed or mismatched provenance and wrong pre/fix role. An unchanged regeneration must pass; a changed input must fail.
- [x] Avoid cache-busting or weakening the existing stale-build guard. Keep current STP proof assertions intact.

## Acceptance

- [x] Run positive and negative controls against actual build/guard entrypoints using isolated fixtures.
- [x] Test identical bytes with newer mtimes, changed bytes with preserved mtimes, wrong image role, absent/malformed provenance and changes outside Java files.
- [x] If an actual local Docker build is available, use disposable artifacts with the source SHA recorded; label stub inspections separately.

## Original reports

- [the-stp-freshness-guard-is-unsatisfiable-after-a-no-op-rebuild.md](../../open/the-stp-freshness-guard-is-unsatisfiable-after-a-no-op-rebuild.md)

Next: Maintain regression coverage; live/deployment follow-ups belong to RI06/RI07/RI13.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

2026-10-07 dispatch: `01a1177f-0d06-79f1-90ab-43b103f4cca3` on `gpt-6.1-sol`, reasoning `high`. Exact owned checkout/branch claim pending. Handoff: `/Users/yaakov/dev/lmax/coordination/eod-integration/HANDOFF-CODEX-IMAGE-FRESHNESS-20261007.md`. Coordinator owns review/integration.

2026-10-07 coordinator review: local tool implementation at `bfe79b6d` accepted for content/role/payload identity;23 independent offline controls passed,21 delivered hashes match. Controlled integration and combined RI17 header/compatibility reconciliation pending. No live STP/production Java-image/retained-state compatibility acceptance. Evidence `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri18-coordinator-20261007/review.md`.

## October 7 integration outcome

The builder and proof compare complete source/build context fingerprints, immutable role/provenance and packaged contents rather than timestamps. Combined provenance tests: 23 passed. No-op regeneration passes and changed contents refuse. Real fleet STP/retained compatibility is a separate acceptance task.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.
