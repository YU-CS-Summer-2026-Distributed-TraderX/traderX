# OTC refusal audit tasks

Owner: Codex RI22 OTC chat `01a11835-d552-73d1-a720-e7a15fa66cb3`.
Status: implemented and verified locally; coordinator review/integration pending.

- [x] Verify accepted baseline and authoritative last-wins owner (FR-ORA01).
- [x] Preserve inherited booked-contract assertions and implement shadow-only observation (FR-ORA01,
  FR-ORA02, NFR-ORA01).
- [x] Exercise actual closed-archive regulatory report, reason/identity, retry/range/JSON and capacity
  controls (FR-ORA01 through FR-ORA04).
- [x] Complete independent accepted null-tap witness and source/generated regression/allocation
  verification (NFR-ORA01).
- [x] Verify generation/document parity, component checker and repository gates.
- [x] Prepare scoped commit, evidence and review handoff.
- [ ] Coordinator review, shared index reconciliation and controlled integration.

Local evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri22-otc-audit-20261007/`.
Final report records exact commands, nonzero test XML, negative controls, artifact hashes and limitations.
No live fleet/HA/performance or financial validation.

Evidence: accepted baseline 8/8; source-composed 14 audit tests plus 1 independent null-tap witness.
Generated focused accounting/snapshot/replay/service/audit cases and all 5 configured allocation gates
pass in Java 21 with their repository C2/-Xbatch profiles. Exact totals and final hashes are in the
evidence report. These are focused suites; no complete module-suite or live cluster claim.
