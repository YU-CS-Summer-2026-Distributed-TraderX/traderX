# Feature Specification: EOD Risk Bundles

**Feature Branch**: `YU18-eod-risk-bundles`  
**Created**: 2026-09-14  
**Status**: Implemented local transport and durable mock coordination
**Input**: Delta over `YU17-otc-rates` position schema 3 and OTC schema 2 exports

## User Stories

- As an integration owner, I want a self-contained EOD bundle so a consumer can identify the exact exported portfolio.
- As a consumer, I want mismatched or corrupted artifacts rejected so I cannot silently combine different cuts.
- As a developer, I want a local mock so I can exercise transport without a pricing service or cloud resources.

## Functional Requirements

- FR-EB01: The state SHALL inherit YU17-otc-rates and preserve its two export schemas byte for byte.
- FR-EB02: The builder SHALL require matching consensus sequence, session date, price snapshot version and cut hash across artifacts.
- FR-EB03: The manifest SHALL include caller-supplied cluster epoch, offset-aware valuation time, input origin, file hashes, schemas and counts.
- FR-EB04: The bundle ID SHALL hash the canonical manifest body excluding bundleId.
- FR-EB05: Validation SHALL reject malformed headers, missing provenance, invalid numeric/date values, duplicate row identities and manifest disagreement.
- FR-EB06: Publication SHALL stage files privately and refuse existing output destinations.
- FR-EB07: The mock SHALL validate its input and echo every position/contract identity with null NPV, empty Greeks, NOT_PRICED and MOCK_ONLY.
- FR-EB08: The result SHALL identify its input bundle and explicitly declare synthetic=true, usableForRisk=false and priced coverage zero.

- FR-EB09: The coordinator SHALL snapshot valid discovered bundles and deduplicate workloads in local SQLite.
- FR-EB10: The coordinator SHALL preserve attempts and recover interrupted work without stealing a live worker.
- FR-EB11: Result ingestion SHALL reject missing, duplicate or mismatched identities and financial claims from the mock.
- FR-EB12: Status SHALL distinguish current cuts, historical results, ambiguous versions and artifact integrity failures.

- FR-EB13: The producer SHALL optionally publish a private completion receipt after both export artifacts exist.
- FR-EB14: The bridge SHALL validate ready receipts and package source bytes without manual artifact pairing.
- FR-EB15: Zero-coupon exports SHALL retain empty coupon-schedule/accrual fields.


- FR-EB16: Opt-in GCS staging SHALL restrict reads to an allowed prefix, pin each object's generation, enforce caller-selected per-object byte limits and retain source generations and SHA-256 hashes privately.
- FR-EB17: Receipt staging SHALL validate the original completion payload before publishing a local receipt. Archive-only staging SHALL verify the source cut hash and SHALL NOT synthesize a completion receipt, cluster epoch or valuation time.


- FR-EB18: The provisional HTTP mock SHALL have a distinct workload profile and SHALL refuse reuse of a coordinator state containing another profile.
- FR-EB19: Uncertain or pending HTTP execution SHALL retain its durable attempt for lookup/reconciliation on the next run; declared failures SHALL require explicit retry.
- FR-EB20: HTTP result acceptance SHALL verify workload identity, result hashes, worker attempt provenance and the existing strict non-pricing result contract.

## Non-Functional Requirements

- NFR-EB01: Default local commands SHALL use Python 3.10+ standard library without network access. Opt-in GCS staging SHALL use installed gcloud credentials for bounded read-only downloads. Provisional HTTP transport SHALL connect only to a literal loopback endpoint; its fake worker SHALL bind only to loopback and perform no pricing.
- NFR-EB02: Actual market data, exports and results SHALL reside outside the public checkout; repository fixtures SHALL be synthetic.
- NFR-EB03: The state SHALL own its additive component under its generation runtime-overrides directory.
- NFR-EB04: Tests SHALL exercise integrity failures, identity collisions, empty portfolios and repeatable output identity.

## Success Criteria

- SC-EB01: Synthetic equity and swap exports build, validate and produce an identified mock result locally.
- SC-EB02: A changed artifact or mixed-cut pair is rejected before a result is published.
- SC-EB03: Repeated input bundles have equal IDs while changed cluster epochs produce different IDs.
- SC-EB04: Generated component tests exercise the same implementation as the source-owned component.

- SC-EB05: Process termination before publication creates a preserved interrupted attempt and a new successful mock attempt.
- SC-EB06: Process termination after publication ingests that result without recomputing.
- SC-EB07: Duplicate discovery and out-of-order completion do not create duplicate workloads or select an older cut.

- SC-EB08: Real in-process sequenced orders and an OTC booking export four positions and one contract, then complete one mock job under repeated receipt delivery.

- FR-EB21: Preserve frozen v1 bundle and mock workload hash vectors with an independent verifier and production compatibility tests.
- FR-EB22: Explicit terms input selects bundle v2; pin original CSV bytes and a versioned terms artifact by exact hashes, reject missing/duplicate joins or disagreement with exported economics, and enumerate missing conventions.
- FR-EB23: Reproduce synthetic bill, fixed-rate note long/short, and current SOFR convention examples through the sequenced service and production exporter; separate acceptance expectations from observed mock results.

- FR-EB24: Preserve committed fixture bytes across Git checkout filters, reject malformed missing-accrual inputs, and version structural accrual-basis extensions without changing existing terms-v1 fixtures.

### Pinned local W0 result acceptance

The local coordinator supports an independent `alex-w0-local-v1` file-intake profile for the reviewed Alex adapter commit. Its accepted result status is `W0_VALIDATED`, with zero priced items and `usableForRisk=false`; it must not select a mock result or claim portfolio risk. Before publication it reconciles all source identities, input echoes, item-order hashes, per-calculation outcomes, coverage counts and signed exported-accrual conversions. Stored output bytes and the local receipt are revalidated on status reads. Missing files remain pending on the same attempt; published results can be recovered after interruption. This compatibility profile accepts only terms-v1 Treasury and incomplete-SWAP bundles with no market computation. Terms-v2 support, authenticated remote delivery and financial pricing remain unimplemented at this boundary.
