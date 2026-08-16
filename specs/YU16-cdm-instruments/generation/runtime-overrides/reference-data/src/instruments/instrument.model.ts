// YU16: the CDM-shaped instrument record (FR-CDM02..05). Flat record — the CDM
// Asset -> Instrument -> Security choice tree is taxonomy documentation, not a runtime
// discriminated union (NFR-CDM05). Enum literals are quoted from finos/common-domain-model
// rosetta source (base-staticdata-asset-common-enum.rosetta); there is no TICKER member —
// Bloomberg ticker symbology is BBGTICKER.

export type AssetIdType =
  | 'BBGID' | 'BBGTICKER' | 'CUSIP' | 'FIGI' | 'ISDACRP' | 'ISIN' | 'Name' | 'REDID'
  | 'RIC' | 'Other' | 'Sicovam' | 'SEDOL' | 'UPI' | 'Valoren' | 'Wertpapier'
  | 'CurrencyCode' | 'ExchangeCode' | 'ClearingCode';

export type SecurityType = 'Debt' | 'Equity' | 'Fund' | 'Warrant' | 'Certificate';

export type EquityTypeEnum =
  | 'Ordinary' | 'NonConvertiblePreference' | 'DepositaryReceipt' | 'ConvertiblePreference';

export type DepositaryReceiptType = 'ADR' | 'GDR' | 'IDR' | 'EDR';

export type FundProductType =
  | 'MoneyMarketFund' | 'ExchangeTradedFund' | 'MutualFund' | 'OtherFund';

export type AssetClass = 'Stock' | 'ETF' | 'US_TREASURY' | 'CORPORATE_BOND';

/**
 * The accrual convention, NAMED on the instrument rather than inferred from its type. It is a
 * field because the two genuinely disagree: on the seeded GS 5.750% of 2036 the same position
 * accrues 0.004514 of par more under 30/360 than under ACT/ACT — $4,514 on $1m face. A bond
 * price and its accrued interest mean nothing without it.
 */
export type DayCount = 'ACT/ACT ICMA' | '30/360';

/** S&P-style long-term issuer credit rating. Investment grade is BBB- and above. */
export type CreditRating =
  | 'AAA' | 'AA+' | 'AA' | 'AA-' | 'A+' | 'A' | 'A-'
  | 'BBB+' | 'BBB' | 'BBB-' | 'BB+' | 'BB' | 'BB-' | 'B+' | 'B' | 'B-';

export interface AssetIdentifier {
  identifier: string;
  identifierType: AssetIdType;
}

/** CDM asymmetry preserved: equityType is a wrapping type, fundType is the enum directly. */
export interface EquityType {
  equityType: EquityTypeEnum;
  depositaryReceipt?: DepositaryReceiptType;
}

/**
 * A zero-coupon instrument (bill, STRIP) carries rateType 'Zero' and NO couponFrequency: it has
 * no coupon schedule at all, which is a different statement from "a schedule paying 0%". The
 * whole point of the discriminator is that a consumer cannot accidentally walk a schedule that
 * does not exist (ADR-061 / the extract's zero-coupon branch depends on exactly this).
 */
export interface FixedInterestTerms {
  rateType: 'Fixed';
  couponRatePercent: number;
  couponFrequency: 'Semiannual';
}

export interface ZeroCouponTerms {
  rateType: 'Zero';
  couponRatePercent: 0;
}

export type InterestTerms = FixedInterestTerms | ZeroCouponTerms;

export interface PrincipalRepaymentTerms {
  style: 'Bullet';
  parAmount: 100;
}

/**
 * Two source types, kept apart deliberately. US_TREASURY_AUCTION_RESULT means the price came off
 * a real TreasuryDirect auction PDF and the instrument has a real FIGI. SIMULATED_CURVE_POINT
 * means the instrument is a curve point we invented to give the risk engine a bootstrappable
 * short end and a set of discount factors — its price is arithmetic, not provenance, and it has
 * NO FIGI, because a FIGI-shaped string we made up is worse than an absent one.
 * `assertCdmConditions` enforces that split so the two can never be confused downstream.
 */
export interface PriceProvenance {
  /**
   * SIMULATED_CREDIT_POINT is a corporate priced as a stated spread over the simulated Treasury
   * curve. Kept distinct from SIMULATED_CURVE_POINT because the two are simulated in different
   * ways — a curve point is a discount factor we invented, a credit point is a spread we invented
   * ON TOP of one — and a consumer decomposing a corporate yield into rates and credit needs to
   * know that both halves are synthetic.
   */
  sourceType: 'US_TREASURY_AUCTION_RESULT' | 'SIMULATED_CURVE_POINT' | 'SIMULATED_CREDIT_POINT';
  sourceUrl: string;
  /** Quoted clean percent-of-par, as the auction PDF states it (display space, ADR-057). */
  officialCleanPrice: number;
  runtimeSeedCleanPrice: number;
  simulated: true;
}

export interface DebtEconomics {
  debtType:
    | 'US_TREASURY_NOTE' | 'US_TREASURY_BOND' | 'US_TREASURY_BILL' | 'US_TREASURY_STRIP'
    | 'CORPORATE_BOND';
  issuer: string;
  /**
   * Present only on a corporate. Its absence is what makes a Treasury's credit risk implicit
   * rather than unknown — the US Treasury is the curve, not a spread over it — so this is
   * deliberately not defaulted to AAA.
   */
  creditRating?: CreditRating;
  /** Stated on every debt instrument. Never inferred from debtType by a consumer. */
  dayCount: DayCount;
  /** Present for a coupon-bearing bond; absent for a bill or a STRIP. */
  fixedInterest?: FixedInterestTerms;
  /** Present for a zero-coupon instrument; absent for a coupon-bearing bond. */
  zeroCoupon?: ZeroCouponTerms;
  principalRepayment: PrincipalRepaymentTerms;
  issueDate: string;
  maturityDate: string;
  /**
   * The profile bucket the price walk is keyed by, in years. For a bill this is the rounded
   * tenor (a 4-week bill is 0.08, not 4/52) — it labels a volatility bucket, it is not a
   * day-count term and nothing computes with it.
   */
  originalTermYears: 0.08 | 0.25 | 0.5 | 1 | 2 | 3 | 5 | 7 | 10 | 20 | 30;
  priceProvenance: PriceProvenance;
}

export interface Instrument {
  instrumentKey: string;
  displayName: string;
  shortDisplayName?: string;
  assetClass: AssetClass;
  currency: string;
  securityType: SecurityType;
  equityType?: EquityType;
  fundType?: FundProductType;
  debtEconomics?: DebtEconomics;
  matured: boolean;
  observedAt: string;
  identifiers: AssetIdentifier[];
}
