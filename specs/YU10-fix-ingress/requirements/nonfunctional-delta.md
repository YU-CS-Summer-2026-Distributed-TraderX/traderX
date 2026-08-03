# Non-Functional Delta: YU10 over YU09-ops-hardening

- **No-GC boundary preserved**: no FIX code on the BLP/journaler threads; the ExecutionReport
  handler is enqueue-only on the output-ring thread; allocation gates and `noGcTest` remain
  exact-zero (NFR-FIX01). QuickFIX/J allocates on its own session threads, which sit outside the
  measured boundary exactly as the REST edge's Tomcat threads do.
- **Backpressure**: FIX session threads inherit ring-producer semantics — a full ring blocks
  that session (TCP flow control to that counterparty) with a bounded claim timeout; the BLP and
  other sessions proceed (NFR-FIX02).
- **Durability**: ledger appends use the journal's amortized-force discipline; the FIX store
  persists sequence state and sent messages across restarts on the existing PVC; the FIX data
  directory is covered by the established disk-watermark alerting thresholds (NFR-FIX03,
  NFR-FIX04).
- **Startup**: ledger rehydration runs alongside journal replay inside the existing
  startup-probe budget (readiness target, 10-minute ceiling); the acceptor accepts logons only
  after readiness.
- **Benchmark honesty**: FIX throughput is reported as completed order lifecycles with
  submitted/accepted/completed splits and same-day REST controls, per the repository benchmark
  discipline (NFR-FIX05).
- **Security posture**: the FIX port is cluster-internal (NodePort on kind for test clients);
  session identity is fail-closed at logon independent of the REST entitlement flag; the risk
  control plane and its operator token are untouched.
