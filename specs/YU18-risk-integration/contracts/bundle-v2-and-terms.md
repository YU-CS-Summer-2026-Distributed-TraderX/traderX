# Bundle v2 and instrument terms v1

Implemented local exchange format, 2026-09-15. This is TraderX's proposal, pending Alex's agreement.
No financial support or valuation is implied by successful bundle validation.

## Compatibility and identity

`bundle.py build` without `--terms` produces the unchanged v1 manifest and byte scopes. With
`--terms PATH`, it produces `traderx.eod-bundle.v2`. Position schema 3 and OTC schema 2 CSV bytes
remain unchanged. The fourth file is `instrument-terms.json`, pinned by this manifest entry:

```json
"instrumentTerms": {
  "path": "instrument-terms.json",
  "schema": "traderx.instrument-terms.v1",
  "sha256": "<SHA-256 of exact artifact bytes>",
  "entries": 1
}
```

All other v1 manifest fields remain, including `marketInputs.status=NOT_SUPPLIED`. Bundle identity
is SHA-256 over the canonical manifest **without** bundleId. Canonicalization remains the v1 rule:
sorted keys, two-space indentation, ASCII JSON escaping, finite JSON numbers, UTF-8 and one final LF.
The terms file itself is hashed as supplied, so even whitespace changes its artifact hash and bundle
identity. A terms revision cannot silently reuse the old job key. A version field identifies the
schema; the artifact hash identifies its exact content. Neither requires a mutable latest lookup.

V1 and v2 are different candidates. For the same epoch/date/cut, putting both into one coordinator
state produces the existing ambiguous-current-cut condition; choose one version for a real run.
The local mock validates and copies all v2 files. The frozen HTTP mock draft-1 accepts only v1 and
rejects v2 **before network access**; it never drops the terms artifact. Bridge/GCS receipt staging
continue to produce v1; v2 packaging is an explicit subsequent build with a supplied terms file.
No new real-worker protocol is activated.

## Terms artifact

Top-level fields are exactly `schema` and `entries`. Each entry contains:

| Field | Meaning |
|---|---|
| identity | Positions: `{source:"positions", security}`. Contracts: `{source:"contracts", contractId, clusterEpoch}`. |
| instrumentType | Initial profile supports `TREASURY` and `SWAP`. Other classes require a further terms profile. |
| provenance | `{origin:"synthetic" or "supplied", description}` explaining the source and assumptions. |
| terms | Explicit terms, detailed below. Financial decimal values are strings, lags are nonnegative integers. |
| missingTerms | Sorted exact list of required term names absent from terms. Missing does not mean zero or a default. |

Every unique position security and every contract needs exactly one entry. Security terms are shared
across long/short accounts, while amounts stay in position rows. The validator checks each joined
row, not only the first account. Contract identities include the bundle epoch. Extra, duplicate,
missing and wrong-epoch entries fail. Exported economics must be present and agree with the CSV;
the supplement cannot change a booked rate, index, date, currency or direction.

An entry can preserve an incomplete product without fabricating terms. It must enumerate missing
fields and is not pricing-ready. Even an entry with no missing fields does not prove a model can
represent it. A supplied reference's truth cannot be established by a structural validator; its
source must still be checked. Synthetic terms are rejected with an `inputOrigin=export` bundle.

### Treasury

Required fields: currency, issueDate, maturityDate, couponRatePercent, couponFrequency, dayCount,
calendar, businessDayAdjustment, paymentLagBusinessDays, settlementDays, firstCouponDate,
penultimateCouponDate, stubConvention, schedule, faceDenomination, redemptionFraction, priceBasis,
quantityUnit.

- Coupon is annual **percent**, matching the exporter; 4.0 means 4%. The swap's fixedRate instead
  uses an annual decimal fraction; 0.040000 means 4%.
- `faceDenomination` is a positive monetary denomination of the security. It does not rescale the
  CSV position quantity, which is already signed currency face amount.
- `priceBasis=clean-fraction-of-par`; `quantityUnit=signed-currency-face`.
- Schedule is an ordered array of `{startDate,endDate,paymentDate}` coupon periods, contiguous from
  issue to maturity. Redemption is represented separately by maturityDate/redemptionFraction.
- A zero-coupon bill has couponFrequency=NONE, an empty coupon schedule, and null coupon dates.
- Initial fixed-rate note schedules follow the exporter's current semiannual ACT/ACT (ICMA),
  maturity-anchored, no-stub accrual model. Other frequencies, stub conventions or mismatched
  coupon-date metadata are refused, rather than silently reconciling against a different model.

The shared note explicitly assumes calendar NONE, unadjusted payments, zero payment lag and
same-day settlement. This is a test agreement matching session-date accrual, **not a claim about
actual Treasury settlement conventions**. None of this adds issue dates or holiday handling to the
production security store. Real reference ingestion remains future work.

### Swap

Required fields: currency, payReceive, notional, fixedRate, floatIndex, effectiveDate, maturityDate,
fixedPaymentFrequency, fixedDayCount, fixedSchedule, floatingSchedule, floatingDayCount, calendar,
businessDayAdjustment, fixedPaymentLagBusinessDays, floatingPaymentLagBusinessDays,
overnightCompounding, lookbackBusinessDays, lockoutBusinessDays, observationShift, fixingCalendar,
fixingHistoryReference.

`fixedPaymentFrequency` and `fixedDayCount` preserve exported paymentFrequency/dayCount; neither
specifies the floating index tenor. Explicit leg schedules use the same period representation.
Observation shift is boolean. Fixing-history reference may be null only when explicitly inapplicable;
absence is listed in missingTerms. No calendars, compounding rule or overnight history are inferred.
This proposal leaves further rate-product features for agreement with Alex.

## Delivered examples and acceptance boundary

The component's `tests/fixtures/shared/` contains three cases, each with v1/v2 bundles and the
original cut. CSVs were produced by SharedEodExamplesTest applying commands through the sequenced
service and calling production RiskExtractCsv/SwapContractCsv. Supplementary reference terms are
synthetic and separately described. The exporter source hashes are in provenance.json.

| Case | Exported population | Target for Alex |
|---|---|---|
| bill | Long and short 100,000 face, zero coupon, no coupon accrual dates | Identified bill NPV/sensitivity under an agreed assumed curve |
| note | Long and short 100,000 face, 4% annual coupon, nonzero accrued fraction | Signed NPV/accrued interest, clean/dirty reconciliation, sensitivities |
| sofr | One PAY_FIXED 1,000,000 USD booking, 1Y fixed payments, ACT/360 | Identified unsupported outcome explaining the convention gap |

Valuation is pinned to 2025-06-02. The swap reproduces the current compiled SOFR convention with
synthetic dates and identity; it is not a copy of the private live booking. `case.json` describes an
acceptance expectation, never an observed worker result. No expected numerical prices or Greeks
are supplied. The local mock still returns NOT_PRICED for every item, including the swap.

Run `bash scripts/demo-state-YU18-shared-examples.sh` after generation. It executes fresh Java
exports, validates completion receipts/cuts, compares every delivered case file byte-for-byte,
and exercises both versions through separate local coordinator states. A changed exporter requires
reviewing the fixtures and provenance, not automatically rewriting expected results.

See [the v3 compatibility follow-up](compatibility-v3.md) for scoped checkout attributes, CRLF diagnostics, the additive terms-v2 accrual basis and the invalid missing-accrual fixture. Existing examples and v1 hashes remain unchanged.
