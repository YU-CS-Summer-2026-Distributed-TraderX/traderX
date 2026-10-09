# Requirements

- FR-LAF01: In LOCAL_ONLY, anonymous and workspace users cannot submit FIX orders outside the checked Desk order route. The explicitly authenticated demo administrator retains operator access.
- FR-LAF02: Decode/validate the request target once before policy and forwarding. Encoded and legacy aliases cannot obtain an internal credential for a protected mutation without admin authentication. Reject ambiguous syntax. Token minting remains unavailable through console aliases.
- FR-LAF03: The legacy cluster passes the same effective epoch to trade and order publishers, including null/blank environment values. Managed descriptor identity remains authoritative. Do not rewrite persisted rows or change old constructor defaults.
- FR-LAF04: Failed internal-token acquisition retries with bounded exponential backoff and a request deadline. Never retry the caller's mutation. Expired credentials are not served; protected requests report temporary unavailability while acquisition is pending.
- FR-LAF05: Any positive halted-account count prevents green PnL success. Rows plus halts are partial; zero rows plus halts are halted. Existing rows without completion evidence are unverified.
- FR-LAF06: Cluster observability renders one gateway target from declared Services, actual member count/namespace, collector metrics ports and available optional Spring services. A declared unhealthy service remains visible; no health-based target suppression. Baseline blackbox jobs are not used by cluster entrypoints.

Acceptance: real console HTTP negative and authorized positive controls; source/render equality; fresh image/pod identity; fresh synthetic cluster demonstrates no unauthorized FIX order or sweep, valid parent joins, credential recovery without restart, partial EOD status and healthy selected scrapes. Existing matching/refusal controls remain meaningful. No production performance or financial acceptance follows from this rig.
