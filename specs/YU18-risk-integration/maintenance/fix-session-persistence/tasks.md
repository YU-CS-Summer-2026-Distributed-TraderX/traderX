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
- [x] Production before the next roll: `nats stream edit KV_FIX_SESSIONS --discard old` (yaakov approves; §6a.5).
      Applied on `traderx` 2026-10-28, 1:21 pm ET; rollout record §13a.
- [ ] Ship in a `traderx` rollout (yaakov approves): the first roll resets every live session once.
- [ ] Fresh-epoch runbook step executed on the next wipe (FR-GFS-13).
