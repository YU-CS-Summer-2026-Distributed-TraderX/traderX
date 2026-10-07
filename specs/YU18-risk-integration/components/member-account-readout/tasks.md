# Member account readout tasks

Status: implemented and locally verified; controlled integration and live acceptance pending. Owner: Codex RI22 member-account-readout lane.

- [x] Verify base/source ownership and post isolated CLAIM.
- [x] Specify FR-MAR01/02/03 and NFR-MAR01 with member sampling limits.
- [x] Implement independent read-only route using existing risk accessor and admin JWT.
- [x] Verify actual production HTTP/JWT source and generated cases with negative controls.
- [x] Verify owner/test/doc generated parity and unchanged core/risk/service/Gateway.
- [x] Deliver scoped commit and evidence; release claim for coordinator review.
- [ ] Controlled integration and live acceptance (coordinator owned, unverified here).

Local evidence: 22 source readout cases; 64 generated scoped cases; 65 disposable reviewed gauge/readiness compatibility cases; seven expected assertion failures across three negative controls. Original nonempty member baseline 1/1 returns 404. Raw logs and positive XML counts: coordination/eod-integration/review-evidence/ri22-account-readout-20261007 outside the checkout. No financial, performance, HA, quorum or retained-rig validation.
