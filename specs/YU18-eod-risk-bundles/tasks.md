# Tasks: EOD Risk Bundles

- [x] T-EB01 Define local manifest and mock contracts.
- [x] T-EB02 Implement byte-preserving builder and validation.
- [x] T-EB03 Implement identified non-pricing mock results.
- [x] T-EB04 Add synthetic fixtures and failure-path tests.
- [x] T-EB05 Register state and generation entrypoints.
- [x] T-EB06 Record source/generated verification evidence.

- [x] Add private local coordinator, durable attempts, explicit retry and mock-result validation.
- [x] Exercise process interruption before/after publication, duplicate discovery and late results.
- [x] Add unapproved exchange schema drafts and synthetic equity/bill/SOFR fixtures.

- [x] Add completed-export receipt publication and local bridge.
- [x] Exercise real in-process engine/exporter output through the coordinator.
- [x] Correct zero-coupon and SOFR fixture assumptions against production output.
- [x] Validate draft schemas with positive and negative cases using format checking.

- [x] Add bounded generation-pinned GCS staging with private provenance and local failure tests.
- [x] Verify an existing cloud archive and repeated staging without starting compute.
- [x] Exercise a live producer completion receipt through GCS staging and the local coordinator with recorded operational epoch identity and operator-selected demo valuation time.
- [x] Implement the provisional loopback HTTP mock adapter, durable fake worker and response/restart recovery tests.
- [ ] Connect Alex's real HTTP/result contract, authentication and financial capabilities after schema agreement.
