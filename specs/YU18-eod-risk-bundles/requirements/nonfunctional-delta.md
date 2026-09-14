# Non-Functional Delta: EOD Risk Bundles

- NFR-EB01: The added runtime SHALL use Python 3.10+ standard library only and open no network connections.
- NFR-EB02: Actual market data, exports and results SHALL reside outside the public checkout; repository fixtures SHALL be synthetic.
- NFR-EB03: The state SHALL own its additive component under its generation runtime-overrides directory.
- NFR-EB04: Tests SHALL exercise integrity failures, identity collisions, empty portfolios and repeatable output identity.
