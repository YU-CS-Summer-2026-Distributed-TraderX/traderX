# Non-Functional Delta: EOD Risk Bundles

- NFR-EB01: Core local commands SHALL use Python 3.10+ standard library with no network connections. The opt-in GCS staging command SHALL use installed gcloud credentials for bounded read-only downloads; no listener or cloud compute is started.
- NFR-EB02: Actual market data, exports and results SHALL reside outside the public checkout; repository fixtures SHALL be synthetic.
- NFR-EB03: The state SHALL own its additive component under its generation runtime-overrides directory.
- NFR-EB04: Tests SHALL exercise integrity failures, identity collisions, empty portfolios and repeatable output identity.

- Coordinator state uses a private local directory outside Git checkouts.
- SQLite transitions and worker publication are recoverable after process termination.
- Locking supports one host only; no distributed-execution guarantee is claimed.
