# Order types tasks

Status: implemented locally, READY_FOR_REVIEW. Backlog: RI-01. Single delivery.

- [x] Assign owner and checkout (board post 20260923T181555Z; claim 20260923T184208Z).
- [x] Audit the baseline and pin current market/limit behaviour (5 baseline tests, 00456453).
- [x] Revise the spec to the full seven-type + TIF scope, with the four review corrections (revision 2).
- [x] Revision 3 answers review R2-1 to R2-4; cleared for implementation (20260923T231403Z).
- [x] OPEN-D5 (STOP reservation) approved by the user (20260923T231432Z).
- [x] Post the exact implementation paths on the board (20260923T231924Z).
- [x] Wire and snapshot format 11; engine (trade reference, triggers, trailing, iceberg, pegged, TIF, business date, cascade loop).
- [x] REST/FIX boundary, including OrdType P and the F1 fix; read model and DDL migration; console ticket, validation and views.
- [ ] Close all SC-OT01–48 acceptance: correctness, generated-source, allocation, replay/restore, migration and console evidence is recorded; SC-OT35 latency acceptance remains open.
- [x] Docs, backlog and one READY_FOR_REVIEW package.
- [x] Implementation review (20260924T010239Z): I1 ack kind, I2 trailing replace, I3 exact
  parsing, I4 latency fast paths; SC-OT49–51 added (c41e508b).
- [ ] Coordinator review and incorporation into traderX-risk-integration (coordinator).
- [ ] Any live or cluster proof (needs separate authorization; not part of this delivery).

2026-09-24 Codex takeover: original-decimal REST correction implemented and generated-tested (56 focused tests, five allocation gates). Raw HTTP test failed before and passed after. SC-OT35 remains open; no integration or live proof claimed. See component README and shared ri01-codex-precision-20260924 evidence.

2026-09-24 latency lane: bounded diagnosis complete, no safe optimization demonstrated. Engine retained at 88f3a27f; two pinned-runtime campaigns and two scratch ablations recorded in README. Coordinator review/explicit acceptance remains next; no performance waiver or live proof.

2026-09-24 combined integration: source/generated correctness, recovery, read-model/DDL,
console and RI-03 coexistence verified in `codex/integration-ci`; see component README.
SC-OT35/NFR-OT06 deferred/unverified by user until GKE credits. Coordinator incorporation pending.


## RI-12 cross-account self-match prevention

- [x] Define shared ownership groups, legacy group-zero behavior and operator reassignment semantics.
- [x] Enforce groups in matching and FOK preflight; preserve cancel-oldest risk release.
- [x] Sequence group configuration and persist it in versioned snapshots; retain it on sandbox reset.
- [x] Bind local workspace accounts to a stable persisted group before admission; fail login closed on setup failure.
- [x] Source/generated tests for all seven order types, replacement, FOK and snapshot-plus-tail recovery.
- [ ] Complete local cluster upgrade and behavior/restart proof; record evidence before closing RI-12.
