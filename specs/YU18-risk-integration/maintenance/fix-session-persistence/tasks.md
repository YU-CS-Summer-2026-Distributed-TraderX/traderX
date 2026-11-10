# FIX session persistence tasks

- [x] `FixSessionStore`: KV `FIX_SESSIONS`, claim-then-body writes, refresh, reset purge (FR-GFS-01, 02, 10).
- [x] Lease bucket, Logon refusal, on-demand take, in-flight hold, release (FR-GFS-03).
- [x] Lease freshness re-confirm before acting; CAS fencing (FR-GFS-04).
- [x] Disconnected-time reports and adoption from the stored log, with ADR-084 fills (FR-GFS-05).
- [x] `ORDER_READMODEL_URL` on the GKE gateway; `prepare.py` guards (FR-GFS-06).
- [x] ExecID `<boot id>-<n>` (FR-GFS-07); configurable reset time (FR-GFS-08).
- [x] NATS outage: ALERT, close live sessions, refuse Logons, resume (FR-GFS-09).
- [x] Unknown ticker refused UNKNOWN_SECURITY (FR-GFS-12).
- [x] `FixSessionPersistenceIT` (5 cases) and the negative control; GKE proofs `yu18-fix-resume`,
      `yu18-fix-store-faults`, `yu18-fix-session`; latency A/B recorded in ADR-086 (NFR-GFS-01).
- [x] Bounded by sessions, not traffic (FR-GFS-14/15, ADR-086 §6a): per-session ring of `FIX_RESEND_WINDOW`
      keys, discard-old with a 90 % ALERT; `FixSessionStoreBoundIT` fails on the previous store (6,144 keys;
      `maximum bytes exceeded`). Found by f15s on GKE.
- [x] Takeover after a gateway dies ≈ one lease TTL (FR-GFS-16, ADR-086 §6b): TTL 3 s / 500 ms renew, a live holder
      refuses at once, a dead one is waited out on the first Logon, a stalled owner nobody replaced retakes.
      Measured 3,147 ms locally (was ~12 s on GKE, xof).
- [x] Production before the next roll: `nats stream edit KV_FIX_SESSIONS --discard old` (yaakov approves; §6a.5).
      Applied on `traderx` 2026-10-28, 1:21 pm ET; rollout record §13a.
- [x] A reset Logon wins over the stored day (FR-GFS-17, ADR-086 §6c): the 10-29 rehearsal wedged CLIENT4/6/8
      for 50 min on "MsgSeqNum too low, expecting 180146". The reset is applied again under the lease.
- [ ] Ship FR-GFS-17 at the next bring-up (rollout chat).
- [x] Group commit with a write barrier (FR-GFS-18, ADR-099): ~6x per gateway locally (16.6K -> 101K execution
      reports/s at 33 sessions), fence, lease and store-before-send kept.
- [x] codeX review 2026-10-30 §10-12 (FR-GFS-04/16/18, ADR-099 decisions 1, 3, 7): a retake fences `K.seq`
      first; the compare-and-set waits for its batch's bodies; the bound counts the batch in flight.
- [x] codeX review 2026-10-30 §17 (FR-GFS-16, ADR-099 decision 3): every acquisition fences `K.seq` in both
      modes, so an owner that only recorded orders or answered resends still breaks a stalled owner's retake.
- [ ] Measure FR-GFS-18 on kind or GKE with the hxb FIX arm, store on.
- [x] FR-GFS-19 (FR-BSP-02, RI35): `K.seq`'s target held below a pipelined session's undecided orders;
      `FixStoreGroupCommitIT.theStoredTargetStaysBelowAnUndecidedOrder`, both modes.
- [ ] Ship in a `traderx` rollout (yaakov approves): the first roll resets every live session once.
- [ ] Fresh-epoch runbook step executed on the next wipe (FR-GFS-13).
