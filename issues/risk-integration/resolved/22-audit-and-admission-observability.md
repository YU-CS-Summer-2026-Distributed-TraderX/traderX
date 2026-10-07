# RI-22 — OTC rejection audit and risk/admission readouts are missing

Updated: 2026-10-07. Status: done (three source-integrated readout/audit components; local acceptance passed). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Original finding (before integration)

Accepted OTC bookings reach the regulatory report, refused bookings do not. The gateway control snapshot lists securities without admitted accounts, and member metrics omit reserved risk capacity.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](../open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [x] Expose sequenced OTC refusal decisions on a reproducible audit surface with reason and identity.
- [x] Expose observed current-member engine account state without presenting directory existence as admission or claiming a coherent global cut.
- [x] Publish bounded risk-capacity gauges using existing core accessors, with explicit units and aggregation semantics.
- [x] Before designing a new wire event, inspect cheaper read-side hooks and deterministic-core compatibility.

## Acceptance

- [x] Known accepted/refused bookings yield distinct attributable audit rows.
- [x] Directory-only and control-admitted account cases remain distinguishable.
- [x] Gauge checks show reserve/release behavior with nonzero positive and refusal controls.

## Original reports

- [a-refused-otc-booking-leaves-no-audit-trace.md](../../open/a-refused-otc-booking-leaves-no-audit-trace.md)
- [control-snapshot-carries-no-accounts.md](../../open/control-snapshot-carries-no-accounts.md)
- [the-cluster-tier-exports-no-risk-gauge.md](../../open/the-cluster-tier-exports-no-risk-gauge.md)

Next: Maintain regression coverage; live/deployment follow-ups belong to RI06/RI07/RI13.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

2026-10-07 class-window dispatch: bounded member risk reservation gauges only. OTC refusal audit and admitted-account snapshot remain queued. Handoff shared HANDOFF-CODEX-RISK-GAUGES-20261007.md; exact checkout CLAIM pending.

2026-10-07 risk-gauge milestone reviewed locally at `bb86eede`:14 independent actual HTTP/formatter tests pass,48 hashes match. Raw reserve/gross-executed accounting and unavailable/zero semantics accepted in stated sampling scope. Controlled integration/live acceptance pending; OTC audit/admission remain queued. Evidence `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri22-coordinator-20261007/review.md`.

2026-10-07 class-window dispatch: OTC refusal audit assigned to Sol6.1 High chat `01a11835-d552-73d1-a720-e7a15fa66cb3`; shadow replay only with null live tap and no wire/snapshot/policy change. Gauge delivery reviewed separately, admission readout queued. No integration or live proof.

2026-10-07 class-window update: Member account readout assigned to Sol6.1 Medium chat `01a11837-861d-70b0-ae65-4029c84af323`; actual observed member state/admin JWT only. No gateway/UI/core mutation. OTC audit assigned separately; both await review/integration.

2026-10-07 member account readout locally reviewed at `ca543b23`:65 independent HTTP/JWT/gauge/readiness checks pass;66 source/evidence hashes match. Per-member sequential observation only, no global admission guarantee. Controlled integration pending; OTC audit remains its separate lane.

2026-10-07 OTC refusal audit locally reviewed at `f326c115`:15 coordinator-run actual service/disposable archive/report/witness tests pass;103 hashes match. Gauge/readout milestones remain separate reviewed commits. No live fleet/HA/financial validation; controlled integration pending.

## October 7 integration outcome

All three scoped components are integrated: reserve/executed-capacity gauges, admin member accountId/enabled observations, and sequenced OTC refusal rows from shadow replay. Their production HTTP/replay paths passed combined generated tests. Member reads remain sequential/non-atomic; archive history is required for the audit. No global admission guarantee, HA or financial validation is claimed.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.
