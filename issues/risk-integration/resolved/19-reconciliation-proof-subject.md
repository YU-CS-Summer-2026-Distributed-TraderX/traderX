# RI-19 — Forward reconciliation proof mutates an out-of-window trade

Updated: 2026-10-07. Status: done (source-integrated; scoped offline proof controls passed). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Original finding (before integration)

yu05-recon mutates the oldest SQL trade even when member restart has removed that trade from the bounded live forward blotter.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](../open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [x] Create or select a trade whose identity is observed in the live forward window and SQL.
- [x] Plant the mismatch on that exact row; show the new classification names it; restore original values on every exit.
- [x] Report absent/too-old window prerequisites explicitly; never classify empty-vs-empty agreement as success.

## Acceptance

- [x] Negative control remains discriminating after member restart and with older retained SQL rows.
- [x] No unrelated row changes; failed SQL reads are refusals, not empty populations.

## Original reports

- [yu05-recon-forward-sweep-cannot-pass-on-a-rolled-rig.md](../../open/yu05-recon-forward-sweep-cannot-pass-on-a-rolled-rig.md)

Next: Maintain regression coverage; live/deployment follow-ups belong to RI06/RI07/RI13.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

2026-10-07: User authorized class-window rotation; dispatched local offline RI19 proof correction. Exact checkout CLAIM pending. Runtime assertions and code are reserved; handoff shared HANDOFF-CODEX-RECON-PROOF-20261007.md.

2026-10-07 review: delivery `dd1c2398`,30 independent tests pass, but orphan-probe check/insert race permits foreign-row deletion on failed insert. Correction `20261007T191217Z-coordinator-ri19-orphan-review-r1-5013c6.txt` queued for original author at next class-window slot. Exact-subject negative control otherwise reviewed positively; not integrated.

2026-10-07 correction reviewed locally at `71f3df5c`:15 focused methods independently pass,19artifact+9source hashes match. Orphan ownership race resolved; foreign/replaced rows retained, ambiguous owned insert reconciled without retry. Controlled integration/shared indices and real MariaDB/Kubernetes proof remain pending. Evidence `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri19-coordinator-r1-20261007/review.md`.

## October 7 integration outcome

The proof selects an attributable SQL/live intersection, preserves scoped checkpoints, verifies fresh logs and owns its synthetic orphan by exact byte-sensitive identity. Combined tests: 55 passed. Offline SQL/command fixtures do not establish live deployment or full-window completeness.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.
