# Managed-run UI tasks

Status: review needed. Owner: Claude.

- [x] Inspect RI-06 contracts in source; post CLAIM and lease.
- [x] Rules module + blotter integration (active context, named-run history, scoped subjects, fencing, read-only).
- [x] Proxy refusal of operator routes in image server and dev proxy, including `/legacy` and `/gw` forms (live bypass via `/legacy/` found and closed).
- [x] Unit (6 pure, 10 mock) + node (2) tests; Angular suite 113/113; production build.
- [x] Live disposable fixture proof, 20/20 checks, 9 screenshots.
- [x] Request additive active-scope read; report scoped orders 500-for-unknown-scope.
- [x] Coordinator review (2026-09-24): corrections R1–R3 requested.
- [x] R1: adopt integrated `GET /v2/projections/active`, scoped readers, pointer revalidation; inference removed.
- [x] R2: single fail-closed path; populate-then-refuse regressions.
- [x] R3: bounded, abortable polls; state-gated actions; late-reply/destroy regressions.
- [x] Re-proved: 119/119 Angular, mutation checks, live fixture 27/27 on the merged backend.
- [x] Admin trade list on the authoritative pointer (`readActiveRunTrades`), 3 new specs; Angular 122/122.
- [x] Real RI-06 transition proof on a disposable rig (real Aeron runs/gateways/controller), 21/21 incl. Admin list.
- [x] Component index row (own branch; also moved the Event-recovery row back inside the table).
- [ ] Coordinator review/integration.
- Not in scope for this lane: deployment / retained installations (local only, cloud hold).

Coordinator review correction R4 (2026-09-24): Admin trade and algo requests share an abort-raced 2.5-second deadline; a hung dependency cannot keep stale trades actionable. Account changes and destruction abort pending requests; late responses remain fenced. Three actual AdminPanel tests with abort-ignoring mock transport cover the timeout, late-response/newer-poll and lifecycle cases. Integrated-source Angular125/125, node15/15 and production build passed. Earlier disposable browser/transition proofs are supplied lane evidence, not rerun for this small correction.
