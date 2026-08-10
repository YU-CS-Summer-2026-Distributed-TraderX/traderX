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

export type AssetClass = 'Stock' | 'ETF' | 'US_TREASURY';

export interface AssetIdentifier {
  identifier: string;
  identifierType: AssetIdType;
}

/** CDM asymmetry preserved: equityType is a wrapping type, fundType is the enum directly. */
export interface EquityType {
  equityType: EquityTypeEnum;
  depositaryReceipt?: DepositaryReceiptType;
}

export interface FixedInterestTerms {
  rateType: 'Fixed';
  couponRatePercent: number;
  couponFrequency: 'Semiannual';
}

export interface PrincipalRepaymentTerms {
  style: 'Bullet';
  parAmount: 100;
}

export interface PriceProvenance {
  sourceType: 'US_TREASURY_AUCTION_RESULT';
  sourceUrl: string;
  /** Quoted clean percent-of-par, as the auction PDF states it (display space, ADR-057). */
  officialCleanPrice: number;
  runtimeSeedCleanPrice: number;
  simulated: true;
}

export interface DebtEconomics {
  debtType: 'US_TREASURY_NOTE' | 'US_TREASURY_BOND';
  issuer: 'United States Department of the Treasury';
  fixedInterest: FixedInterestTerms;
  principalRepayment: PrincipalRepaymentTerms;
  issueDate: string;
  maturityDate: string;
  originalTermYears: 2 | 5 | 10 | 20 | 30;
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
