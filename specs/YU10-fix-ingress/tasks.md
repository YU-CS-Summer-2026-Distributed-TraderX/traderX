# Tasks: YU10-fix-ingress

- T10-01: Correlation ledger (`fix/ClOrdIdLedger`): record format, append + amortized force,
  startup rehydration, duplicate detection, fail-closed unavailability; unit tests
  (write/rehydrate/duplicate/capacity/corrupt-tail truncation).
- T10-02: QuickFIX/J dependency + acceptor lifecycle (`fix/FixSessions`): settings generated
  from env, file store/log under `FIX_DATA_DIR`, start after engine readiness, stop on shutdown.
- T10-03: Identity (`fix/FixIdentity`): `FIX_SESSION_ACCOUNTS` parsing, logon JWT resolution via
  `EntitlementGate.resolve`, principal pinning, reject paths; unit tests for every failure mode.
- T10-04: Inbound translation (`fix/FixOrderApplication`): D/F/H handling, four-outcome
  admission model, ring publish with claim timeout; MVC-level tests with a QuickFIX/J initiator.
- T10-05: Outbound reports (`fix/FixExecutionReportHandler`): output-disruptor registration,
  inputSeq→ledger join, 8/9 construction, enqueue-only discipline; tests for every lifecycle
  kind + disconnected-session buffering.
- T10-06: Manifests: acceptor port on deployment + service + NodePort, `FIX_DATA_DIR` on the
  existing PVC, demo CompID mapping, disk-watermark coverage.
- T10-07: Proof script `yu10-fix-session.sh` (logon, D→8, F→8/9, H, duplicate, --resume
  pod-kill reconciliation, DB projection check).
- T10-08: Bench: `fix-load.mjs` raw sender (completed-lifecycle counting, SIDES=alternate),
  regression ladder (allocation gates, REST single/batch, journaled BLP, compose HA),
  results stored per bench discipline.
- T10-09: Generation wiring (`generate-state-YU10-fix-ingress.sh`, render script), shared-file
  ancestor-marker verification, doc sync (CLAUDE.md, specs/README.md, state enumerations).
- T10-10: `generation/implementation-status.md` with SC-FIX01…06 evidence.
