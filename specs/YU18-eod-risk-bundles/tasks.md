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

- [x] Freeze v1 golden inputs/preimages/expected bundle and workload hashes with an independent verifier.
- [x] Add optional bundle v2 and versioned Treasury/SWAP terms with source reconciliation and explicit missing conventions.
- [x] Produce and reproduce synthetic bill, note long/short, and SOFR shared examples using the production exporter.
- [x] Exercise v2 local coordination and refusal by the frozen v1-only HTTP draft.
- [ ] Agree final terms field names, real reference sources, and missing/stub conventions with Alex.
- [ ] Observe Alex's numerical bill/note results and identified SOFR unsupported outcome against shared examples.
