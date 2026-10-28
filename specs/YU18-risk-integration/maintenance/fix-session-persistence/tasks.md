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
- [ ] Ship in a `traderx` rollout (yaakov approves): the first roll resets every live session once.
- [ ] Fresh-epoch runbook step executed on the next wipe (FR-GFS-13).
