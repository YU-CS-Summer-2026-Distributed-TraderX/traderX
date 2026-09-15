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

## Non-Functional Requirements

- NFR-EB01: The local Python integration commands SHALL use Python 3.10+ standard library only and open no network connections; producer hooks SHALL use the inherited Java runtime.
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
