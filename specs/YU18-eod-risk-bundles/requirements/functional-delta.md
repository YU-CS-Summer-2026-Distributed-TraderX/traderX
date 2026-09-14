# Functional Delta: EOD Risk Bundles

- FR-EB01: The state SHALL inherit YU17-otc-rates and preserve its two export schemas byte for byte.
- FR-EB02: The builder SHALL require matching consensus sequence, session date, price snapshot version and cut hash across artifacts.
- FR-EB03: The manifest SHALL include caller-supplied cluster epoch, offset-aware valuation time, input origin, file hashes, schemas and counts.
- FR-EB04: The bundle ID SHALL hash the canonical manifest body excluding bundleId.
- FR-EB05: Validation SHALL reject malformed headers, missing provenance, invalid numeric/date values, duplicate row identities and manifest disagreement.
- FR-EB06: Publication SHALL stage files privately and refuse existing output destinations.
- FR-EB07: The mock SHALL validate its input and echo every position/contract identity with null NPV, empty Greeks, NOT_PRICED and MOCK_ONLY.
- FR-EB08: The result SHALL identify its input bundle and explicitly declare synthetic=true, usableForRisk=false and priced coverage zero.
