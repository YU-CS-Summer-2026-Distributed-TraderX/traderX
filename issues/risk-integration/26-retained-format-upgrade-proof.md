# RI-26 — Retained upgrades across historical snapshot and capacity changes lack proof

Updated: 2026-10-07. Status: blocked (supported retained upgrade boundary/barrier decision needed). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Current finding

Current snapshot tests do not establish a three-member retained upgrade across a real historical format/capacity gap. Behavioral pre/fix STP builds intentionally share a format.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [ ] Record reproducible source/binary provenance for the historical boundary and supported migration policy.
- [ ] Require compatible state/snapshot readers and a defined safe upgrade barrier.
- [ ] Prove retained epoch, nonempty book/control/contracts and member agreement through the supported upgrade or explicit refusal.

## Acceptance

- [ ] Distinct real format/capacity revisions are recorded, not merely current-tip restarts.
- [ ] Unsupported old/new formats refuse loudly; no data loss hidden behind Ready.

## Original reports

- [nothing-proves-recovery-across-a-real-format-and-capacity-gap.md](../open/nothing-proves-recovery-across-a-real-format-and-capacity-gap.md)

Next: Agree the supported historical upgrade boundary and barrier.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

## October 7 integration outcome

No retained-upgrade implementation is assigned or claimed. A supported historical writer/reader/capacity boundary and safe barrier must be agreed before a disposable three-member proof.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.
