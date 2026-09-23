# Provisional local pricing acceptance v1

This is an operator-selected TraderX compatibility profile, **not a frozen bilateral schema**, authenticated producer assertion, or financial/production approval. Alex has not supplied a result schema version at this commit. Nothing here claims he implemented terms-v2, a versioned result/capability service, or remote durable execution.

## Identity and input boundary

Adapter `alex-pricing-local-provisional-v1`; Alex commit `e7246e1765a2f9b4d4dd6049c97d1baa66319be7`; explicit assumed profile `flat-3pct-v1`. Both command selection (`--pricing-results`) and result market declaration are required. No default curve is inferred from missing market observations. Use a separate private coordinator state directory. The whole worker profile participates in the job hash.

The input is exactly either original `tests/fixtures/shared/bill/v2` or `note/v2`, after independent byte/hash/schema validation:

- Bill bundle `c3211337d0c3e61b5276613972fd6af9b6e7e8ec18070019963c61fc8c1b525d`.
- Note bundle `1b64bdcb2497423a72ddd7ca70b664a9a1cf6b8b6b595a562b87e6b2ac211dc9`.

These pins bind economics, artifact bytes, accounts 22214/42422, signed USD face ±100000, epoch `synthetic-shared-examples-v1`, sequence 8, snapshot 1, cut hashes, 2025-06-02 business date and `2025-06-02T16:00:00-04:00` valuation. Bundle v2 contains terms **v1**. Altered dates (including impossible dates), amounts, accounts, conventions, cut or valuation require a new reviewed profile; recomputing a hash does not admit them. SOFR, equity, terms-v2 and other portfolios fail this profile. Discovery may queue a structurally valid unsupported bundle; execution fails it even before waiting for a result.

## Exact producer result shape

`pricing_result.validate` is the executable closed-shape contract. Top-level fields are exactly `bundleId`, `clusterEpoch`, `sessionDate`, `valuationTime`, `mappingVersion`, `engineVersion`, `marketProvenance`, `marketInputs`, `measure`, `items`, `itemOrder`, `coverage`, `warnings`. Versions are `traderx-adapter-v1` / `0.1.0`; these are compatibility strings, not a result schema version.

Market provenance is `assumed`, measure `risk-neutral-pricing`; market inputs have exactly mode `assumed-profile`, assumedProfileId `flat-3pct-v1`, and curveProvenance `{curveId: flat-3pct-v1, inputOrigin: assumed, construction: flat-constant, inputHashes: []}`. Each NPV repeats that provenance. The reviewed warning about absent observed inputs is required; no observed data is fabricated.

Items exactly contain itemId, sourceIdentity, calculations, currency and mappingVersion. Identity is independently derived from source rows using `traderx-item-v1`. Items must follow source-row order; changing both the order and its hash fails. The order object has scheme, itemIds, itemCount, sha256. Accounts, source security, epoch, USD currency, face sign and amount must match.

Calculations exactly contain npv, accruedInterest, rateSensitivity, rateGamma, theta, vega and varEs:

- Bill NPV: status/value/method, signedFaceAmount/redemptionFraction, discountFactor/yearFraction/dayCount, maturityDate/valuationDate and curveProvenance. Method is discounted-cashflow, day count ACT/365 (Fixed). Value is signed USD (the producer omits NPV currency; item currency and this external profile establish units).
- Note NPV: common signed cashflow fields plus priceType=dirty, cleanNpv, accruedInterest, couponRate, redemptionPvPerUnitFace, redemptionDiscountFactor, accrualDayCount, discountDayCount, coupons and accrualReconciliation. Each coupon has startDate/endDate/paymentDate, accrualFraction, amountPerUnitFace, discountFactor/yearFraction. No extra or missing fields are accepted.
- Standalone accrual: status/value/provenance/currency/observedCleanPrice/signedFaceAmount. The current producer uses converted for notes and structural-zero for bills. Note NPV reconciliation separately identifies accrualSource=exported-fraction, exportedFraction, recomputedFraction, signed difference and tolerance. These are preserved as distinct concepts.
- Note sensitivity: status/value, method=bumped-revaluation, derivative=dNPV/dZeroRate, shockedFactor=zero-curve-parallel, bump=0.0001, currency=USD. Despite the derivative label, **value is the actual signed USD price difference P(0.0301)−P(0.03), not a derivative divided by the bump and not conventional positive long DV01**. The receipt makes this interpretation explicit. No AD/per-pillar sensitivity is accepted.
- Bill sensitivity, all rateGamma and theta remain unsupported with NO_PRICER_AT_THIS_STAGE and the reviewed detail. Vega and item varEs are not-applicable with their exact reviewed explanations. Item varEs contributes no portfolio-risk coverage.

Unknown fields/capabilities, wrong types (including booleans as numbers), duplicate JSON keys, nonfinite numbers and wrong labels are rejected. Coverage is independently rebuilt per calculation/status, including integer counts and actual boolean types; allApplicableComputed remains false. Original producer fixture outputs are test inputs, never expected-value oracles.

## Independent reference and tolerances

The validator uses Python Decimal at 40-digit precision and standard Gregorian date arithmetic; it imports no Alex/JAX/ORE pricing code. Continuous assumed rate r=0.03 and discount time t=(payment date−2025-06-02)/365 give D=exp(−rt).

Bill pays face × redemptionFraction once on 2025-12-02 (183/365 years). Note pays four 0.04/2 coupons on 2025-06-15, 2025-12-15, 2026-06-15 and 2026-12-15, plus redemption at maturity. Calendar NONE, UNADJUSTED, zero payment lag, same-day settlement and regular semiannual periods are synthetic fixture assumptions, not general Treasury-market conventions. Dirty NPV is signed face times the sum of discounted unit cashflows. Clean NPV subtracts exported accrual, not observed clean market value.

Note exported accrued = signed face × 0.018571. Separately recomputed ICMA fraction = (169/182) × 0.04/2. Difference is signed face × (exported−recomputed). The allowed economic reconciliation bound is **0.5 × 10^−6 × abs(face) + 0.01 USD, without rounding before adding the cent**. It is 0.06 at fixture face, 0.072 at 124000 and 0.075 at 130000; only fixture face is admitted. Alex's rounding bug is neither fixed nor generalized away.

NPV, clean/accrued USD, sensitivity and reconciliation-difference comparison use absolute 1e−8 USD (one millionth of a cent), allowing binary float arithmetic/serialization while rejecting economically meaningful deviations. Dimensionless time/discount/recomputed fractions and the declared reconciliation bound use 1e−12 absolute. Face, coupon, redemption, exported fraction and bump are exact decimal comparisons. Dirty=clean+accrued is checked directly within 1e−8 USD. Numeric tolerance never expands input scope.

## Durable local custody and status

Incoming `BUNDLE_ID.json` is read once, validated, then retained byte-for-byte with `acceptance.json` via existing exclusive directory publication. The receipt has schema `traderx.provisional-pricing-local-receipt.v1`, complete compatibilityProfile, engineCommit plus operator-evidence disclaimer, producerResultSchema=absent-at-reviewed-commit, bundle/result hash, explicit units and validation summary. This does not authenticate producer identity. Missing files keep a RUNNING attempt pending. Late discovery/restart resumes that attempt; published output is revalidated before database completion. Status revalidates original bytes and receipt hash, and never reselects an older cut just because a newer one failed.

Completion is `SYNTHETIC_PRICING_VALIDATED`, separate from `W0_VALIDATED` and `MOCK_COMPLETE`. Only a current, unambiguous, integrity-verified result has selectedSyntheticPricingResult=true. Read-only status exposes pricingAvailable with pricingScope=provisional-dated-synthetic-fixtures only for verified output; portfolioRiskAvailable and usableForRisk remain false. This task does not redesign the console UI or deploy its runtime.

W0 code, profile pin, receipt format, fixtures and original golden hashes remain unchanged. No pricing computation is added to W0. Future Alex commits require explicit review and a new compatibility decision.
