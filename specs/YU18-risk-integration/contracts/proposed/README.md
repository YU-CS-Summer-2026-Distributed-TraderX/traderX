# Proposed exchange draft — awaiting Alex's agreement

These JSON Schemas are a small W1 negotiation draft. They are not a released contract, not used
by the local coordinator, and do not alter `traderx.eod-bundle.v1` or `traderx.mock-result.v1`.
The coordinator currently accepts only the local transport mock. Alex's HTTP/GCS adapter is not
implemented. No fixture in this directory claims a measured engine result.

The request pins portfolio manifest bytes separately from the existing bundle-body identity,
a market package, reference data, valuation time and a computation profile. Profile contents must
be immutable and define the model/settings. The result echoes those identities, returns original
source identities and reports NPV/rate-sensitivity coverage separately. This narrow draft fixes
`usableForRisk=false` while the joint implementation and financial validation are unapproved.

Before release, both sides must agree:

- Versioned normalized instrument terms, unit conversions and the Treasury/SOFR supported subsets.
- Immutable market/reference package schemas and how their IDs relate to their bytes.
- Signed NPV conventions and `rateSensitivity.method` values: AD first-order estimate versus bumped
  revaluation; numeric bump, curve and optional pillar must be supplied for an available sensitivity.
- Job-level status derived from per-calculation status and the selected coverage policy. Coverage
  counts must sum to requested, and every source row must occur exactly once with matching identity.
- Failure envelopes for requests rejected before rows are available; calibration diagnostics and
  model-specific metadata; extension to gamma, theta, vega, scenarios and aggregate results.
- Financial acceptance gates before introducing any result eligible for risk decisions.

JSON Schema checks structure only. Cross-artifact identity, hashes, unique item mappings, arithmetic
coverage, supported financial terms and calculation semantics need adapter validation. Do not infer
these guarantees from a schema-valid document.

Synthetic source fixtures are in `generation/runtime-overrides/eod-risk-bundles/tests/fixtures/exchange/`
(relative to the state pack). They use the exported CSV shapes but were constructed locally, not
captured from a running exporter. They contain an equity, zero-coupon Treasury bill and SOFR swap.
The bill's coupon-schedule and accrued-interest fields are empty, matching the zero-coupon exporter contract.
All three remain `NOT_PRICED` under the current mock. SOFR refusal and actual NPV/Greek fixtures
belong to the agreed pricing adapter, not to this mock.

## Validation and examples

Run the development-only validator suite from quickstart section 8. The pinned jsonschema package
includes format-checking extras. Both meta-schemas and positive/negative examples are exercised.
`examples/` contains hand-authored shape illustrations, not engine outputs: values are synthetic,
all hashes are placeholders, paths do not resolve, and usableForRisk remains false.
