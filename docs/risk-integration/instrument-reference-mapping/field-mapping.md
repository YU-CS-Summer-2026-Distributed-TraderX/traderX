# Reference definitions and current field support

TraderX baseline: `22d4c9067ce6a01f33678e7cb061c8d0e677e9c3`. Retrieval: 2026-10-08 UTC / 2026-10-07 Eastern. Definitions below are attributed to their source; implementation rules are attributed to their owner. Neither selects a new production convention.

## Primary sources actually read

TreasuryDirect HTML pages below expose no own document version or effective date. The OCC landing page identifies the linked PDF version and effective date. Retrieval dates are snapshot dates, not security effective dates. No auction results, option chains or real portfolios were collected.

| ID | Official title and URL | Version / section read | Use and limit |
| --- | --- | --- | --- |
| T1 | [Treasury Bills](https://www.treasurydirect.gov/marketable-securities/treasury-bills/) | Unversioned HTML; introduction and Bills at a Glance | Discount/par, redemption, purchase increment. No security-specific terms. |
| T2 | [Treasury Notes](https://www.treasurydirect.gov/marketable-securities/treasury-notes/) | Unversioned HTML; introduction and Notes at a Glance | Fixed interest and six-month payments. No issue-specific schedule/calendar. |
| T3 | [Understanding Pricing and Interest Rates](https://www.treasurydirect.gov/marketable-securities/understanding-pricing/) | Unversioned HTML; Bills and Bonds and Notes | Discount-rate formula and coupon/yield distinction. No calibrated curve. |
| T4 | [Treasury Reopenings](https://www.treasurydirect.gov/auctions/reopenings/) | Unversioned HTML; introduction and Security Details | Issue-date ambiguity and accrued purchase interest. |
| T5 | [Announcements, Data & Results](https://www.treasurydirect.gov/auctions/announcements-data-results/) | Unversioned HTML; format-change notices | XML/API and URL changes announced; ingestion format not pinned here. |
| O0 | [Characteristics and Risks of Standardized Options landing page](https://www.theocc.com/company-information/documents-and-archives/options-disclosure-document) | Page identifies June 2024 ODD, effective June 3, 2024 | Establishes retrieved PDF version; mentions T+1 update. |
| O1 | [Characteristics and Risks of Standardized Options](https://www.theocc.com/getmedia/a151a9ae-d784-4a15-bdeb-23a029f50b70/riskstoc.pdf) | June 2024; printed pp. 3, 7–8, 18–21, 56–57 (PDF pp. 4, 8–9, 19–22, 57–58) | General nomenclature and adjustment examples, not a series specification or information memo. |

T1 describes repayment at face and a $100 minimum/increment. T2 describes a fixed annual rate with payments every six months. T3's bill discount formula uses days/360; a note's coupon and yield are distinct. T4 distinguishes a reopening's issue date from the original issue while retaining maturity/coupon, and notes possible accrued interest at purchase. These statements do not determine the project's settlement or accrual policy. [T1](https://www.treasurydirect.gov/marketable-securities/treasury-bills/), [T2](https://www.treasurydirect.gov/marketable-securities/treasury-notes/), [T3](https://www.treasurydirect.gov/marketable-securities/understanding-pricing/), [T4](https://www.treasurydirect.gov/auctions/reopenings/).

O1 distinguishes calls/puts, expiry, exercise style and physical/cash settlement. Most single-stock contracts cover 100 shares; exercise prices are per share. Adjustments can change shares or add cash/other property. Its reverse-split example retains aggregate exercise consideration while reducing delivered shares. An option's root alone therefore cannot establish its deliverable. O1 also leaves particular terms to the listing market and adjustment determinations to OCC. [O1, Chapters I–III](https://www.theocc.com/getmedia/a151a9ae-d784-4a15-bdeb-23a029f50b70/riskstoc.pdf).

## Code and schema ownership

All TraderX links below identify authoritative source, not a generated tree. Same-basename searches across `specs/` found the exporter in YU15/YU16/YU17; YU17 composes last for `RiskExtractCsv`. YU18 overrides `RiskExtractMain`, but not that exporter or the YU14 symbol parser. Rendering scripts compose full runtime files last-wins. No generation was needed for documentation-only changes.

| ID | Operative owner / exact implementation |
| --- | --- |
| C1 | [YU16 CDM catalog](../../../specs/YU16-cdm-instruments/generation/runtime-overrides/reference-data/src/instruments/cdm-catalog.ts): `TreasurySeed`, `toInstrument`, debt economics and provenance. Display/seed prices are percent-of-par; runtime debt prices use fractions. Its metadata does not establish engine admission. |
| C2 | [YU18 RiskExtractMain](../../../specs/YU18-risk-integration/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/RiskExtractMain.java): `loadBondStatics` reads ticker/type/couponRatePercent/maturityDate/dayCount from instruments.csv, not issueDate/CUSIP/schedule. |
| C3 | [YU17 RiskExtractCsv](../../../specs/YU17-otc-rates/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/RiskExtractCsv.java): schema 3, `row`, `accrual`, `preamble`. Bond join takes precedence; otherwise `OccSymbol.isOption` selects OPTION/EQUITY. |
| C4 | [YU14 OccSymbol](../../../specs/YU14-listed-equity-options/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/lmax/OccSymbol.java): `isOption`, `multiplierFor`, tail accessors. Root accepts uppercase letters only; dates check month/day ranges, not full calendar validity; option multiplier always 100, fallback 1. |
| C5 | [YU15 option-quotes.js](../../../specs/YU15-eod-risk-extract/generation/runtime-overrides/price-publisher/src/option-quotes.js): `parseOcc` requires 1–6 uppercase letters, uses strike /1000 and 21:00 UTC expiry. Quote generation is synthetic. No quote/pricing function was run for this work. |
| C6 | [YU18 bundle.py](../../../specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles/bundle.py): `POSITION_FIELDS`, `read_extract`, `validate`; schema-3 position CSV and schema-2 OTC CSV. Parser accepts OPTION rows without validating their symbol or deliverable. OTC expiry/style fields describe swaptions, not listed options. |
| C7 | [YU18 instrument_terms.py](../../../specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles/instrument_terms.py): `BOND`, `REQUIRED`, `validate`; terms v1/v2 support TREASURY/SWAP only. V2 adds the closed SESSION_DATE accrual basis. |
| C8 | [YU18 worker profiles](../../../specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles/worker_protocol.py), [container input allowlist](../../../specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles/container_adapter.py), [provisional input allowlist](../../../specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles/pricing_result.py). Container pin `992db307f40c1b19de19a0ae43bf653ede3a5bda`; historical local pricing pin `e7246e1765a2f9b4d4dd6049c97d1baa66319be7`; W0 pin `cb9b277a9de702b2ba4f0bcda431a396f54c029a`. Pins remain distinct. |
| C9 | [Consumer copy of result schema](../../../specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles/container-result-schema.json): `jaxrisk.eod-result.v1`, Draft 2020-12. `container_adapter.validate_document` narrows upstream open calculation payloads to reviewed bill fields/units and validates identities/coverage. A result schema is not an instrument-reference input schema. |

Engine owner E1 means Alex's Git objects at container pin `992db307…`, read without importing or running them: `engine/integration/{bundle,terms,normalize,conventions,pipeline,capabilities,schema,schema_version}.py` and `engine/api/eod_routes.py`. `normalize_position` parses signed face, divides coupon percent by 100, and multiplies exported accrual fraction by signed face once. `check_conventions` allows SWAP/TREASURY/EQUITY, not OPTION. `EodSubmissionSchema` accepts a bundlePath and execution options, not a direct instrument dictionary. The checkout observed during research was `2df78cb08f782010f61acffc07d78030580e7639`; that newer HEAD is not accepted by changing this document. Code/schema byte hashes are recorded in examples.json and the review evidence. Exact remote page/PDF bytes were not archived or hashed; their version/section/retrieval locators are the source record.

## Treasury mapping

Each row names a source or explicitly records that the explanatory sources do not establish the field. Units here describe current code/fixtures. `terms.*` means C7 inside bundle v2; `positions.*` means C3/C6 CSV. Decimal economics in terms are strings.

| ID / reference field | Source support | TraderX target, units and transformation | Engine/profile support or gap |
| --- | --- | --- | --- |
| T-ID security / identifier | T1/T2 are product explanations, not a CUSIP resolver | C1 instrumentKey/identifiers; C2 ticker joins to positions.security; terms.identity={source:positions,security} | E1 identity retains exact security/account/epoch. Synthetic UST keys are not verified issuer IDs. No CUSIP field in position CSV/terms. D1. |
| T-TYPE bill vs fixed note | T1/T2 | C1 zeroCoupon/fixedInterest; C3 both export TREASURY, coupon distinguishes zero vs nonzero | E1 splits zero-coupon/coupon-bearing shape; do not classify by prefix alone. |
| T-CURRENCY | No currency convention inferred from these pages | terms.currency agrees with positions.currency; C3 currency comes from account reference, not BondStatic | Existing examples USD; C8 closed profiles do not establish FX support. D2. |
| T-FACE position / denomination | T1 minimum/increment is not a position | positions.quantity signed currency face; contractMultiplier=1. terms.faceDenomination positive currency amount; quantityUnit=signed-currency-face | E1 signed_face_amount parses quantity unchanged; denomination never rescales it. |
| T-ISSUE issueDate | T4 original/reopening distinction | C1 seed issueDate; terms.issueDate ISO date; absent from C2/C3 position columns | C7 schedule must start here; E1 terms join can retain it. Original vs reopening date unresolved. D1. |
| T-MATURITY maturityDate | T1/T2 repayment endpoint, not a concrete date | C2 static -> positions.maturityDate -> terms.maturityDate, ISO date | E1 uses terms. Example dates are synthetic, not taken from an auction. |
| T-COUPON couponRatePercent | T2 fixed annual interest; T1 bill interest at maturity | C2 couponRatePercent -> positions.coupon annual percent -> terms.couponRatePercent; bill "0", note "4.0" | E1 coupon_rate = percent/100. Bill zero coupon does not imply zero discount yield. |
| T-FREQUENCY couponFrequency | T2 six-month payments | terms.couponFrequency="6M" for note, "NONE" for bill; C3 maturity-anchored six-month walk | C7 refuses other note frequency in its initial profile. |
| T-DAYCOUNT dayCount | T3 discount-rate basis is separate; note accrual convention not established by T2 | C2 named static "ACT/ACT ICMA" -> C3 accrued fraction; no position dayCount column; supplementary terms.dayCount="ACT/ACT (ICMA)" note, "NOT_APPLICABLE" bill | E1 bill discount time ACT/365 Fixed under assumed curve; this is not T3 auction discount quoting. D3. |
| T-SCHEDULE schedule / firstCouponDate / penultimateCouponDate | T2 does not give a concrete schedule | C7 ordered startDate/endDate/paymentDate periods; note maturity-anchored; bill [] and null coupon dates | Existing note regular no-stub assumption. No real issuer schedule validated. D4. |
| T-STUB stubConvention | Not established | terms.stubConvention="NONE" in fixtures; C7 enforces note NONE | Short/long first coupons not represented by current exporter assumption. D4. |
| T-CALENDAR calendar / businessDayAdjustment | Not established | terms.calendar="NONE", businessDayAdjustment="UNADJUSTED" in fixtures | Fixture assumptions; no holiday calendar from C2/C3. D4. |
| T-LAGS paymentLagBusinessDays / settlementDays | Not established | terms integers 0 in fixtures | V2 current accrualBasis requires settlementDays 0; general settlement policy undecided. D4. |
| T-REDEMPTION redemptionFraction | T1/T3 face repayment | terms.redemptionFraction="1" dimensionless fixture value | E1 terms-driven; not a recovery/credit convention. |
| T-PRICE clean price / discount rate / yield | T3 separates bill discount rate, note yield and coupon | C1 percent price -> runtime fraction /100; positions.costBasis/closingMark six-decimal clean fractions; terms.priceBasis=clean-fraction-of-par | Discount/yield is not positions.coupon; terms have no auction discount-rate field. No rate copied into flat-3pct-v1. D3. |
| T-ACCRUAL lastCouponDate / accruedInterestFraction | T4 purchase accrual is distinct from this exporter calculation | C3 sessionDate maturity-anchored calculation, HALF_EVEN six-decimal fraction; blank pair for bill; dirty fraction=clean+accrued | E1 converts fraction × signed face once. V2 accrualBasis has schema/dateBasis/valuationDate/settlementAdjustment/fractionDecimals/rounding. Current basis SESSION_DATE/NONE/6/HALF_EVEN, not settlement-date accrual. D4. |

## Listed-option mapping

O1 supports the concepts named below. Encoding rules and numeric values are current implementation or synthetic-example facts, not a certification of exchange symbology. No applicable real-series memo was read because the examples are fictional; a real adjusted series requires that evidence before adoption.

| ID / reference field | Source | Current TraderX target / units | Gap at export, terms and E1 |
| --- | --- | --- | --- |
| O-ID series / underlying identity | O1 I–III | C4/C5 parse root, six expiry digits, C/P, eight strike digits; positions.security exact unpadded string | Root is not a verified share-class/security identifier. No alias conversion. Numeric adjusted root fails these parsers. D5. |
| O-EXPIRY expiration date / time | O1 II | C4 expiryYymmdd; C5 2000-based date and hardcoded 21:00 UTC; no separate listed expiry CSV field | No expiry zone, exchange cutoff or calendar contract. Month/day range checks cannot certify a valid date. D6. |
| O-RIGHT call / put | O1 I–II | C4 isCall; C5 call boolean | Not a separate CSV column; not represented in C7. |
| O-STRIKE nominal strike | O1 III | C4 strikeThousandths integer /1000 -> C5 dollar value | Not a separate CSV column; no aggregate exercise consideration field. D5. |
| O-QUANTITY position count | O1 nomenclature; position sign is local | positions.quantity signed contract count; example "2" | Never rescale contracts to shares before applying contractMultiplier. E1 Treasury signed-face normalization is not an option mapper. |
| O-MULTIPLIER premium factor | O1 II–III | C4 returns 100 for recognized option; positions.contractMultiplier numerical factor; marketValue=quantity×closingMark×factor | No verified per-series factor store or adjustment-aware mapping. Do not replace it with deliverable shares. D5. |
| O-DELIVERABLE components | O1 III adjustments | No field for security/share-class quantities, cash components, cash-in-lieu or per-contract basket | C7 refuses OPTION; C6 accepts a labeled row without representing these omissions. Adjusted example is refused for use, not auto-normalized. D5. |
| O-STYLE exerciseStyle | O1 II | No listed-option field in C4/C3/C7 | OTC contracts.exerciseStyle is a swaption field. Do not reuse it. D6. |
| O-SETTLEMENT settlement type / lag | O1 I/VIII; O0 version notice | No listed physical/cash, settlementDays or calendar fields in export/terms | Document terminology does not implement clearing/exercise. No T+1 adoption here. D6. |
| O-ADJUSTMENT memo / effective date / predecessor | O1 III general discussion | No adjustment/memo/effective-version fields in C3/C7 | Fictional counterpart has no actual memo ID; retain unknown, do not assert "unadjusted" from a parsable root. D5. |
| O-PRICE premium / mark | O1 II–III | positions.costBasis/closingMark; C3 marketValue/unrealizedPnl equations; C5 synthetic quote generator | Source definitions are not observed quotes. Example mark is invented arithmetic only; E1 has no listed-option pricer. D7. |

Other export fields are operational, not issuer/OCC reference terms: accountId/counterpartyId/nettingSetId originate in account reference; markSource/markQuality identify supplied marks or last trades; sessionDate/consensusSequence/priceSnapshotVersion/cutSha256 identify the extract cut. `marketValue` and `unrealizedPnl` are C3 arithmetic from quantity, marks and multiplier. No public explanatory document supplies any of these values for the examples.

## Exact acceptance boundaries

C6 structural validation is broader than C7 terms support and broader than C8 financial-result intake. Complete terms never establish model correctness. C8 container inputs admit bill bundle `c3211337d0c3e61b5276613972fd6af9b6e7e8ec18070019963c61fc8c1b525d` only, with explicitly selected assumed `flat-3pct-v1`. The historical provisional profile additionally admits note `1b64bdcb2497423a72ddd7ca70b664a9a1cf6b8b6b595a562b87e6b2ac211dc9`. Its bill/note terms are v1; locally valid terms-v2 does not expand either allowlist.

An OPTION entry would fail C7 with `initial terms profile supports only Treasury and swap`. An option row in a v1 bundle can pass C6, but no terms are supplied; E1's convention check can report TERMS_NOT_SUPPLIED. With a supplied OPTION entry, E1 rejects that instrument type. Neither is a pricing claim or a result observed in this task. C8 refuses such bundles before a submission can occur.

See [decisions](decisions.md) and [illustrations](examples.json) for representable, unsupported and unknown fields. Public explanatory access was verified; automated ingestion, storage/distribution rights and changing API formats were not approved. T5 still announces format changes. The ODD is linked and briefly paraphrased, not redistributed. An inaccessible OCC equity-options URL was excluded from evidence; no search snippet stands in for a read source.
