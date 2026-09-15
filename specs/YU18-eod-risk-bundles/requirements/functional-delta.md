# Functional Delta: EOD Risk Bundles

- FR-EB01: The state SHALL inherit YU17-otc-rates and preserve its two export schemas byte for byte.
- FR-EB02: The builder SHALL require matching consensus sequence, session date, price snapshot version and cut hash across artifacts.
- FR-EB03: The manifest SHALL include caller-supplied cluster epoch, offset-aware valuation time, input origin, file hashes, schemas and counts.
- FR-EB04: The bundle ID SHALL hash the canonical manifest body excluding bundleId.
- FR-EB05: Validation SHALL reject malformed headers, missing provenance, invalid numeric/date values, duplicate row identities and manifest disagreement.
- FR-EB06: Publication SHALL stage files privately and refuse existing output destinations.
- FR-EB07: The mock SHALL validate its input and echo every position/contract identity with null NPV, empty Greeks, NOT_PRICED and MOCK_ONLY.
- FR-EB08: The result SHALL identify its input bundle and explicitly declare synthetic=true, usableForRisk=false and priced coverage zero.

- Persist local jobs and attempt history; deduplicate immutable mock workloads.
- Recover abandoned RUNNING attempts under an exclusive OS lock.
- Validate result identities, non-pricing semantics and artifact integrity.
- Select mock results by input cut identity, never completion order.

- Publish opt-in local completion receipts only after both exporter artifacts exist.
- Validate source hashes, counts, cut identity, business date and adjacent witness before packaging.
- Preserve empty zero-coupon schedule/accrual fields from production exports.

- FR-EB16: Opt-in GCS staging SHALL restrict reads to an allowed prefix, pin each object's generation, enforce caller-selected per-object byte limits and retain source generations and SHA-256 hashes privately.
- FR-EB17: Receipt staging SHALL validate the original completion payload before publishing a local receipt. Archive-only staging SHALL verify the source cut hash and SHALL NOT synthesize a completion receipt, cluster epoch or valuation time.
