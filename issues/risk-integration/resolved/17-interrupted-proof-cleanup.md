# RI-17 — Interrupted proofs leave borrowed rig state behind

Updated: 2026-10-07. Status: done (source-integrated; local acceptance passed). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Original finding (before integration)

The proof runner restores member image, feed and observability only on the normal path. Its EXIT trap returns the borrowed gateway and port forwards, leaving other preparation exposed to interruption.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](../open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [x] Persist the exact pre-mutation state and target context before applying changes; recover or refuse when a previous run left an unfinished journal.
- [x] Make ordinary exit, failed preparation and catchable termination run idempotent cleanup; include partial preparation and cleanup errors.
- [x] Treat SIGKILL and host failure as interrupted work requiring explicit recovery on the next invocation. Never claim a trap handles uncatchable termination.
- [x] Restore original image, probe/env settings and replica counts rather than assumed defaults. Preserve existing destructive-operation authorization and do not automatically wipe/rebuild storage during recovery.

## Acceptance

- [x] Exercise the actual runner/helper with fake kubectl/rig fixtures: success, proof failure, preparation failure, TERM/INT, interrupted journal and failed cleanup.
- [x] Assert every changed field returns to its recorded value or is reported as unresolved; a failed cleanup must not print PASS.
- [x] No real rig mutation required for this assigned milestone.

## Original reports

- [a-proof-killed-mid-run-leaves-its-prep-stranded-on-the-rig.md](../../open/a-proof-killed-mid-run-leaves-its-prep-stranded-on-the-rig.md)
- [the-rig-was-left-mixed-version-on-the-stp-revert-build.md](../../open/the-rig-was-left-mixed-version-on-the-stp-revert-build.md)

Next: Maintain regression coverage; live/deployment follow-ups belong to RI06/RI07/RI13.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

2026-10-07 dispatch: `01a1177e-9ffb-75b1-b0e7-5a53ea808483` on `gpt-6.1-sol`, reasoning `high`. Exact owned checkout/branch claim pending. Handoff: `/Users/yaakov/dev/lmax/coordination/eod-integration/HANDOFF-CODEX-PROOF-CLEANUP-20261007.md`. Coordinator owns review/integration.

2026-10-07 coordinator review: delivery `50385c29` requires corrections; not integrated. Independent16 acceptance cases passed, but final-proof prerequisite failure reproduced exit0 with no proof entry. Automatic original-image restoration also needs retained compatibility admission or explicit refusal. Correction board `20261007T182642Z-coordinator-ri17-review-r1-df3ee1.txt`; evidence shared `review-evidence/ri17-coordinator-20261007`. Owner remains the RI17 lane; no retained/cloud action.

2026-10-07 corrections accepted locally at `2672dcf7`: R1 final-proof accounting and R2 default unknown retained rollback refusal addressed. Eight added methods independently passed;33 delivery hashes match. Controlled integration/header reconciliation and real retained compatibility proof remain separate. Evidence `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri17-coordinator-r1-20261007/review.md`.

## October 7 integration outcome

The runner journals the borrowed target/state before mutations, supervises catchable termination, and refuses unfinished or unsafe retained rollback. Combined cleanup tests: 24 passed. SIGKILL requires the persisted next-invocation recovery/refusal path; no trap or real retained compatibility is claimed.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.
