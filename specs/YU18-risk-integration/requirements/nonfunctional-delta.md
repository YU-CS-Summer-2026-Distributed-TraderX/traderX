# Non-Functional Delta: EOD Risk Bundles

- NFR-EB01: Default local commands SHALL use Python 3.10+ standard library without network access. Opt-in GCS staging SHALL use installed gcloud credentials for bounded read-only downloads. Provisional HTTP transport SHALL connect only to a literal loopback endpoint; its fake worker SHALL bind only to loopback and perform no pricing.
- NFR-EB02: Actual market data, exports and results SHALL reside outside the public checkout; repository fixtures SHALL be synthetic.
- NFR-EB03: The state SHALL own its additive component under its generation runtime-overrides directory.
- NFR-EB04: Tests SHALL exercise integrity failures, identity collisions, empty portfolios and repeatable output identity.

- Coordinator state uses a private local directory outside Git checkouts.
- SQLite transitions and worker publication are recoverable after process termination.
- Locking supports one host only; no distributed-execution guarantee is claimed.
