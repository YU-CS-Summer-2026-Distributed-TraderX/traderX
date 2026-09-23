# RI-03 — Operational risk pipeline and parallel portfolios

Updated: 2026-09-18. Status: partial foundations; service migration queued. Owner: unassigned. Dependencies: RI-04 agreed API/profile, RI-02 container, RI-05 inputs, RI-06 identity/recovery.

Existing foundations include local bundle/coordinator work, result intake and a Treasury demonstration. Do not recreate those as competing orchestration services.

- [ ] Map existing producer, coordinator, engine API, storage and UI responsibilities.
- [ ] Automate portfolio cut → verified market package → durable submission → status/recovery → validated immutable result → UI.
- [ ] Define remote artifact staging explicitly; a server-local bundlePath is not an upload protocol.
- [ ] Preserve job/workload/attempt identities, coverage, original result bytes and result eligibility.
- [ ] Prove one portfolio through the container with no manual re-entry of economics.
- [ ] Then add stateless execution workers for independent portfolios; keep durable jobs/results external to workers.
- [ ] Add bounded concurrency, ownership/leases as appropriate, retries, backpressure, cancellation and per-job resource limits.
- [ ] Add authenticated boundaries, useful logs/metrics and visible failure/recovery states.

Next: document the existing-to-target flow after RI-04 review. Done requires a complete one-portfolio proof, then separately evidenced multi-portfolio behavior under worker loss and duplicate submissions. A process-local lock does not establish cross-process exactly-once execution.
