# RI-08 — Build, test and deployment automation

Updated: 2026-09-18. Status: audit first; existing CI is not assumed absent or broken. Owner: unassigned. Dependencies: RI-02 build and RI-04 accepted contracts.

This is distinct from RI-03, which schedules and runs risk workloads. P3 and risk-branch CI already received work; inspect their actual commits and workflows before assigning replacements.

- [ ] Inventory active workflows on traderX-risk-integration and the engine repository; record current source and available run evidence.
- [ ] Add missing contract/container gates using pinned revisions and deterministic fixtures.
- [ ] Verify image contents, platform and digest; record artifact provenance.
- [ ] Design controlled promotion, rollback and smoke checks for service updates.
- [ ] Keep CI tests isolated from the live demo and require explicit deployment scope.
- [ ] Document node lifecycle, persistent storage and job resource/cost limits.

Next: identify only missing gates after reviewing existing CI. Done requires actual passing build/test evidence and, separately when authorized, a verified deployment/rollback exercise. No Git push or cloud start is authorized by this backlog.
