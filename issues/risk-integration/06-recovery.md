# RI-06 — Identity, restart recovery and reconciliation

Updated: 2026-09-18. Status: queued hardening; existing recovery mechanisms must be reused. Owner: unassigned. Dependencies: RI-04 identities and RI-03 orchestration.

September 18 evidence: engine state was fresh after zero-node shutdown; SQL retained prior records. Bring-up backed up and cleared the stale projection. The configured epoch still read 2026091601. Historical Risk results remained separately available. This identifies a lifecycle problem to design and test, not authorization to delete more state.

- [ ] Establish authoritative epoch/run identity and change rules across restart, recovery and intentional reset.
- [ ] Prevent order/trade/contract identity reuse from joining new events to prior results.
- [ ] Define persistence, checkpoint, replay and read-model rebuild policies; distinguish restart from fresh session.
- [ ] Reconcile engine fills/contracts, account positions, exports and risk results at a common boundary.
- [ ] Handle duplicate, missing, late and interrupted records without silent accounting loss.
- [ ] Clearly scope historical UI results by original run, account and valuation time.
- [ ] Exercise worker/coordinator restart and stale-result ordering using existing durable ledger mechanisms.

Next: write the recovery/reset matrix and inventory actual storage mounts. Done requires controlled failure/recovery evidence and no cross-epoch joins. Destructive tests need a scoped disposable rig; never delete the risk-extract bucket.
