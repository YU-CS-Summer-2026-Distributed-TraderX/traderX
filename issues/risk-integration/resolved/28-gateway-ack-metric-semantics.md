# RI-28 — Gateway unmatched-ack counter includes expected continuation fills

Updated: 2026-10-07. Status: done (source-integrated; bounded ACK classification checks passed). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Original finding (before integration)

Continuation fill acknowledgments find an already-removed inflight entry and increment ack_unmatched alongside foreign/stale acknowledgments. The metric and its ignored comment imply anomalies that ordinary fills produce.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](../open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [x] Separate expected continuation flow from exceptional unmatched acknowledgments or rename the metric honestly.
- [x] Correct comments and monitoring consumers without changing keyed request correlation.
- [x] Keep trace-key reuse documented as intentional behavior rather than another identity defect.

## Acceptance

- [x] Sequential crossing orders from two accounts distinguish expected fills from true stale/foreign acknowledgments.
- [x] Orders still complete once, and genuine missing-ack signals remain observable.

## Original reports

- [ack-unmatched-counts-continuation-fills.md](../../open/ack-unmatched-counts-continuation-fills.md)

Next: Maintain regression coverage; live/deployment follow-ups belong to RI06/RI07/RI13.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

2026-10-07 class-window dispatch: gateway metric/classification only with bounded state, real production-path controls and unchanged completion/core semantics. Shared HANDOFF-CODEX-ACK-METRICS-20261007.md; exact checkout CLAIM pending.

2026-10-07 local review acceptance at `6a4e9f9c`:36 independent focused Java21 cases and5non-skipped allocation-gate tasks pass;49 delivery hashes match. Unknown partial-fill ambiguity retained; legacy aggregate preserved, bounded reasons added. Controlled integration and actual deployed/HA/performance acceptance remain separate. Evidence `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri28-coordinator-20261007/review.md`.

## October 7 integration outcome

The legacy unmatched total remains compatible; bounded reason counters distinguish continuation, duplicate, reaped/drained, other-session and unknown cases. History eviction/reset visibility and ambiguous repeated partial fills remain explicit. Combined generated gateway tests and five allocation gates passed; completion/wire/core semantics are unchanged.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.
