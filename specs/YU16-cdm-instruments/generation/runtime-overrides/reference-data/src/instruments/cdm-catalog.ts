// YU16: the CDM decoration layer. The DB `stocks` table (and its durable outbox) remains the
// single membership authority for the tradable universe; this module turns a {ticker,
// companyName} row into a CDM-shaped Instrument using data baked offline (FR-CDM06/NFR-CDM07 —
// the runtime never calls a symbology provider). Violations of the CDM conditions throw
// (FR-CDM04); a missing FIGI only warns (FR-CDM05).
import { Logger } from '@nestjs/common';
import {
  AssetIdentifier,
  DebtEconomics,
  EquityTypeEnum,
  FundProductType,
  Instrument,
  PriceProvenance,
  SecurityType,
} from './instrument.model';

const logger = new Logger('CdmCatalog');

// Baked offline from OpenFIGI v3 (keyless tier). Classification keys off `securityType`, not
// `securityType2` (which reports "Mutual Fund" for SPY). Delisted-but-supported tickers (DFS)
// are baked explicitly rather than re-resolved.
const BAKED: Record<string, { figi: string; kind: 'Common Stock' | 'ETP' }> = {
  AAPL: { figi: 'BBG000B9XRY4', kind: 'Common Stock' },
  MSFT: { figi: 'BBG000BPH459', kind: 'Common Stock' },
  AMZN: { figi: 'BBG000BVPV84', kind: 'Common Stock' },
  GOOGL: { figi: 'BBG009S39JX6', kind: 'Common Stock' },
  META: { figi: 'BBG000MM2P62', kind: 'Common Stock' },
  NVDA: { figi: 'BBG000BBJQV0', kind: 'Common Stock' },
  TSLA: { figi: 'BBG000N9MNX3', kind: 'Common Stock' },
  IBM: { figi: 'BBG000BLNNH6', kind: 'Common Stock' },
  BAC: { figi: 'BBG000BCTLF6', kind: 'Common Stock' },
  C: { figi: 'BBG000FY4S11', kind: 'Common Stock' },
  JPM: { figi: 'BBG000DMBXR2', kind: 'Common Stock' },
  GS: { figi: 'BBG000C6CFJ5', kind: 'Common Stock' },
  MS: { figi: 'BBG000BLZRJ2', kind: 'Common Stock' },
  UBS: { figi: 'BBG007DJM539', kind: 'Common Stock' },
  DB: { figi: 'BBG000BR1W32', kind: 'Common Stock' },
  COF: { figi: 'BBG000BGKTF9', kind: 'Common Stock' },
  DFS: { figi: 'BBG000QBR5J5', kind: 'Common Stock' },
  FNMA: { figi: 'BBG000BJQ328', kind: 'Common Stock' },
  FIS: { figi: 'BBG000BK2F42', kind: 'Common Stock' },
  FNF: { figi: 'BBG006N7S6K9', kind: 'Common Stock' },
  SPY: { figi: 'BBG000BDTBL9', kind: 'ETP' },
  QQQ: { figi: 'BBG000BSWKH7', kind: 'ETP' },
  IWM: { figi: 'BBG000CGC9C4', kind: 'ETP' },
  VTI: { figi: 'BBG000HR9779', kind: 'ETP' },
  GLD: { figi: 'BBG000CRF6Q8', kind: 'ETP' },
};

export interface TreasurySeed {
  instrumentKey: string;
  shortDisplayName: string;
  displayName: string;
  /** Empty for a SIMULATED_CURVE_POINT — we have no verified FIGI and will not invent one. */
  figi: string;
  /** 0 marks a zero-coupon instrument: no coupon schedule exists, rather than one that pays 0. */
  couponRatePercent: number;
  issueDate: string;
  maturityDate: string;
  originalTermYears: DebtEconomics['originalTermYears'];
  officialCleanPrice: number;
  runtimeSeedCleanPrice: number;
  debtType: DebtEconomics['debtType'];
  sourceType: PriceProvenance['sourceType'];
  sourceUrl: string;
  /** Defaults to the US Treasury when absent — every seed below states it anyway. */
  issuer?: string;
  /** Corporates only. Its absence on a Treasury is meaningful, not missing data. */
  creditRating?: DebtEconomics['creditRating'];
  /** ACT/ACT (ICMA) when absent. Corporates state 30/360. */
  dayCount?: DebtEconomics['dayCount'];
  /** Corporates only: the spread over the Treasury curve their price was struck at, in bp. */
  creditSpreadBp?: number;
}

const AUCTION = 'https://www.treasurydirect.gov/instit/annceresult/press/preanre/2026';

// Where a simulated curve point's price comes from, since it comes from no auction: the spec
// records the settle date, the curve levels and the closed forms below. A reader who wants to
// know why a number is what it is gets pointed at arithmetic, not at a PDF that does not exist.
const CURVE = 'specs/YU16-cdm-instruments/spec.md#simulated-curve-points';

// The five auction-sourced Treasuries (FR-CDM07): real FIGIs, TreasuryDirect provenance.
// Clean prices here are quoted percent-of-par (provenance/display space); everything the
// runtime STORES for a bond is a fraction of par (ADR-057).
//
// Everything after them is a SIMULATED_CURVE_POINT, added so the risk engine has something to
// bootstrap from: four bills and four STRIPS — which ARE discount factors, the most direct curve
// input there is — plus a 3Y and a 7Y filling the gap between the coupon points we already hold.
// Their prices are arithmetic off one settle date (2026-08-13) and one curve, not provenance:
//   * bills, bank-discount basis:   price% = 100 x (1 - d x days/360)
//   * STRIPS, semiannual compounding: price% = 100 / (1 + y/2)^(2t), t = ACT/365 to maturity
//   * the 3Y/7Y notes: the standard ACT/ACT (ICMA) semiannual PV at the stated yield
// The five auction prices back out to a coherent curve at that settle (4.19 / 4.20 / 4.47 /
// 5.12 / 5.05), and the added points were chosen to sit on it rather than beside it.
export const TREASURY_SEEDS: readonly TreasurySeed[] = Object.freeze([
  {
    instrumentKey: 'UST-20280630', shortDisplayName: 'UST 2Y',
    displayName: 'U.S. Treasury Note 4.125% due June 30, 2028',
    figi: 'BBG022ZR1Z79', couponRatePercent: 4.125,
    issueDate: '2026-06-30', maturityDate: '2028-06-30', originalTermYears: 2,
    officialCleanPrice: 99.878432, runtimeSeedCleanPrice: 99.878,
    debtType: 'US_TREASURY_NOTE',
    sourceType: 'US_TREASURY_AUCTION_RESULT', sourceUrl: `${AUCTION}/R_20260623_2.pdf`,
  },
  {
    instrumentKey: 'UST-20310630', shortDisplayName: 'UST 5Y',
    displayName: 'U.S. Treasury Note 4.125% due June 30, 2031',
    figi: 'BBG022ZR1Z51', couponRatePercent: 4.125,
    issueDate: '2026-06-30', maturityDate: '2031-06-30', originalTermYears: 5,
    officialCleanPrice: 99.664909, runtimeSeedCleanPrice: 99.665,
    debtType: 'US_TREASURY_NOTE',
    sourceType: 'US_TREASURY_AUCTION_RESULT', sourceUrl: `${AUCTION}/R_20260624_3.pdf`,
  },
  {
    instrumentKey: 'UST-20360515', shortDisplayName: 'UST 10Y',
    displayName: 'U.S. Treasury Note 4.375% due May 15, 2036',
    figi: 'BBG0221YLR31', couponRatePercent: 4.375,
    issueDate: '2026-05-15', maturityDate: '2036-05-15', originalTermYears: 10,
    officialCleanPrice: 99.256552, runtimeSeedCleanPrice: 99.257,
    debtType: 'US_TREASURY_NOTE',
    sourceType: 'US_TREASURY_AUCTION_RESULT', sourceUrl: `${AUCTION}/R_20260512_3.pdf`,
  },
  {
    instrumentKey: 'UST-20460515', shortDisplayName: 'UST 20Y',
    displayName: 'U.S. Treasury Bond 5.000% due May 15, 2046',
    figi: 'BBG0226BZH97', couponRatePercent: 5.0,
    issueDate: '2026-06-01', maturityDate: '2046-05-15', originalTermYears: 20,
    officialCleanPrice: 98.481099, runtimeSeedCleanPrice: 98.481,
    debtType: 'US_TREASURY_BOND',
    sourceType: 'US_TREASURY_AUCTION_RESULT', sourceUrl: `${AUCTION}/R_20260520_2.pdf`,
  },
  {
    instrumentKey: 'UST-20560515', shortDisplayName: 'UST 30Y',
    displayName: 'U.S. Treasury Bond 5.000% due May 15, 2056',
    figi: 'BBG0221YLR40', couponRatePercent: 5.0,
    issueDate: '2026-05-15', maturityDate: '2056-05-15', originalTermYears: 30,
    officialCleanPrice: 99.292811, runtimeSeedCleanPrice: 99.293,
    debtType: 'US_TREASURY_BOND',
    sourceType: 'US_TREASURY_AUCTION_RESULT', sourceUrl: `${AUCTION}/R_20260513_2.pdf`,
  },

  // --- coupon points filling the 2Y..10Y gap (yields 4.19% and 4.32% on the curve above) ---
  {
    instrumentKey: 'UST-20290715', shortDisplayName: 'UST 3Y',
    displayName: 'U.S. Treasury Note 4.125% due July 15, 2029',
    figi: '', couponRatePercent: 4.125,
    issueDate: '2026-07-15', maturityDate: '2029-07-15', originalTermYears: 3,
    officialCleanPrice: 99.820187, runtimeSeedCleanPrice: 99.820,
    debtType: 'US_TREASURY_NOTE',
    sourceType: 'SIMULATED_CURVE_POINT', sourceUrl: CURVE,
  },
  {
    instrumentKey: 'UST-20330731', shortDisplayName: 'UST 7Y',
    displayName: 'U.S. Treasury Note 4.250% due July 31, 2033',
    figi: '', couponRatePercent: 4.25,
    issueDate: '2026-07-31', maturityDate: '2033-07-31', originalTermYears: 7,
    officialCleanPrice: 99.581343, runtimeSeedCleanPrice: 99.581,
    debtType: 'US_TREASURY_NOTE',
    sourceType: 'SIMULATED_CURVE_POINT', sourceUrl: CURVE,
  },

  // --- the short end: bills, all issued 2026-08-13, priced on the bank-discount basis ---
  {
    instrumentKey: 'UST-BILL-20260910', shortDisplayName: 'UST 4W BILL',
    displayName: 'U.S. Treasury Bill due September 10, 2026',
    figi: '', couponRatePercent: 0,
    issueDate: '2026-08-13', maturityDate: '2026-09-10', originalTermYears: 0.08,
    officialCleanPrice: 99.681111, runtimeSeedCleanPrice: 99.681,
    debtType: 'US_TREASURY_BILL',
    sourceType: 'SIMULATED_CURVE_POINT', sourceUrl: CURVE,
  },
  {
    instrumentKey: 'UST-BILL-20261112', shortDisplayName: 'UST 13W BILL',
    displayName: 'U.S. Treasury Bill due November 12, 2026',
    figi: '', couponRatePercent: 0,
    issueDate: '2026-08-13', maturityDate: '2026-11-12', originalTermYears: 0.25,
    officialCleanPrice: 98.968667, runtimeSeedCleanPrice: 98.969,
    debtType: 'US_TREASURY_BILL',
    sourceType: 'SIMULATED_CURVE_POINT', sourceUrl: CURVE,
  },
  {
    instrumentKey: 'UST-BILL-20270211', shortDisplayName: 'UST 26W BILL',
    displayName: 'U.S. Treasury Bill due February 11, 2027',
    figi: '', couponRatePercent: 0,
    issueDate: '2026-08-13', maturityDate: '2027-02-11', originalTermYears: 0.5,
    officialCleanPrice: 97.952500, runtimeSeedCleanPrice: 97.953,
    debtType: 'US_TREASURY_BILL',
    sourceType: 'SIMULATED_CURVE_POINT', sourceUrl: CURVE,
  },
  {
    instrumentKey: 'UST-BILL-20270812', shortDisplayName: 'UST 52W BILL',
    displayName: 'U.S. Treasury Bill due August 12, 2027',
    figi: '', couponRatePercent: 0,
    issueDate: '2026-08-13', maturityDate: '2027-08-12', originalTermYears: 1,
    officialCleanPrice: 95.955556, runtimeSeedCleanPrice: 95.956,
    debtType: 'US_TREASURY_BILL',
    sourceType: 'SIMULATED_CURVE_POINT', sourceUrl: CURVE,
  },

  // --- principal STRIPS off the coupon points above: one discount factor each, quoted x100 ---
  {
    instrumentKey: 'UST-STRIP-20280630', shortDisplayName: 'UST STRIP 2Y',
    displayName: 'U.S. Treasury Principal STRIP due June 30, 2028',
    figi: '', couponRatePercent: 0,
    issueDate: '2026-08-13', maturityDate: '2028-06-30', originalTermYears: 2,
    officialCleanPrice: 92.474852, runtimeSeedCleanPrice: 92.475,
    debtType: 'US_TREASURY_STRIP',
    sourceType: 'SIMULATED_CURVE_POINT', sourceUrl: CURVE,
  },
  {
    instrumentKey: 'UST-STRIP-20310630', shortDisplayName: 'UST STRIP 5Y',
    displayName: 'U.S. Treasury Principal STRIP due June 30, 2031',
    figi: '', couponRatePercent: 0,
    issueDate: '2026-08-13', maturityDate: '2031-06-30', originalTermYears: 5,
    officialCleanPrice: 81.438726, runtimeSeedCleanPrice: 81.439,
    debtType: 'US_TREASURY_STRIP',
    sourceType: 'SIMULATED_CURVE_POINT', sourceUrl: CURVE,
  },
  {
    instrumentKey: 'UST-STRIP-20360515', shortDisplayName: 'UST STRIP 10Y',
    displayName: 'U.S. Treasury Principal STRIP due May 15, 2036',
    figi: '', couponRatePercent: 0,
    issueDate: '2026-08-13', maturityDate: '2036-05-15', originalTermYears: 10,
    officialCleanPrice: 64.456612, runtimeSeedCleanPrice: 64.457,
    debtType: 'US_TREASURY_STRIP',
    sourceType: 'SIMULATED_CURVE_POINT', sourceUrl: CURVE,
  },
  {
    instrumentKey: 'UST-STRIP-20560515', shortDisplayName: 'UST STRIP 30Y',
    displayName: 'U.S. Treasury Principal STRIP due May 15, 2056',
    figi: '', couponRatePercent: 0,
    issueDate: '2026-08-13', maturityDate: '2056-05-15', originalTermYears: 30,
    officialCleanPrice: 22.002376, runtimeSeedCleanPrice: 22.002,
    debtType: 'US_TREASURY_STRIP',
    sourceType: 'SIMULATED_CURVE_POINT', sourceUrl: CURVE,
  },

  // --- fixed-rate bullet corporates: a CREDIT SPREAD as a second risk factor -------------------
  //
  // Everything above moves with one thing, the rates curve. These move with two, which is the
  // point of adding them: a portfolio holding both can be decomposed into rates risk and credit
  // risk, and the ratings ladder below (A / A- / BBB+ / BB+, 80bp to 310bp) is wide enough that
  // the second factor is not noise. All four issuers already trade here as equities, so the same
  // name's credit and equity can be looked at side by side.
  //
  // They are 30/360, and that is the reason the handoff wanted the real bond model FIRST: on the
  // GS bond the two conventions differ by 0.004514 of par, $4,514 on $1m face. Before day counts
  // were real, a corporate would have been accrued on the Treasury convention and been quietly
  // wrong by that much.
  //
  // Prices are the ACT/ACT-solved Treasury curve at the 2026-08-13 settle plus the stated spread,
  // repriced on 30/360. Simulated, like the curve points, and marked SIMULATED_CREDIT_POINT
  // because BOTH halves are invented — the base curve and the spread over it.
  {
    instrumentKey: 'CORP-IBM-20330215', shortDisplayName: 'IBM 4.5% 33',
    displayName: 'International Business Machines 4.500% due February 15, 2033',
    figi: '', couponRatePercent: 4.5,
    issueDate: '2026-02-15', maturityDate: '2033-02-15', originalTermYears: 7,
    officialCleanPrice: 95.751864, runtimeSeedCleanPrice: 95.752,
    debtType: 'CORPORATE_BOND',
    sourceType: 'SIMULATED_CREDIT_POINT', sourceUrl: CURVE,
    issuer: 'International Business Machines Corporation',
    creditRating: 'A-', dayCount: '30/360', creditSpreadBp: 95,
  },
  {
    instrumentKey: 'CORP-JPM-20310601', shortDisplayName: 'JPM 5.25% 31',
    displayName: 'JPMorgan Chase & Co. 5.250% due June 1, 2031',
    figi: '', couponRatePercent: 5.25,
    issueDate: '2026-06-01', maturityDate: '2031-06-01', originalTermYears: 5,
    // The one seeded bond trading ABOVE par, deliberately: a premium bond exercises the price
    // path from the other side, where a discount-only set would leave a sign error invisible.
    officialCleanPrice: 101.047466, runtimeSeedCleanPrice: 101.047,
    debtType: 'CORPORATE_BOND',
    sourceType: 'SIMULATED_CREDIT_POINT', sourceUrl: CURVE,
    issuer: 'JPMorgan Chase & Co.',
    creditRating: 'A', dayCount: '30/360', creditSpreadBp: 80,
  },
  {
    instrumentKey: 'CORP-GS-20360315', shortDisplayName: 'GS 5.75% 36',
    displayName: 'The Goldman Sachs Group 5.750% due March 15, 2036',
    figi: '', couponRatePercent: 5.75,
    issueDate: '2026-03-15', maturityDate: '2036-03-15', originalTermYears: 10,
    officialCleanPrice: 99.123457, runtimeSeedCleanPrice: 99.123,
    debtType: 'CORPORATE_BOND',
    sourceType: 'SIMULATED_CREDIT_POINT', sourceUrl: CURVE,
    issuer: 'The Goldman Sachs Group Inc.',
    creditRating: 'BBB+', dayCount: '30/360', creditSpreadBp: 140,
  },
  {
    instrumentKey: 'CORP-F-20320512', shortDisplayName: 'F 6.8% 32',
    displayName: 'Ford Motor Credit Company 6.800% due May 12, 2032',
    figi: '', couponRatePercent: 6.8,
    // The only sub-investment-grade name: without one, "credit spread" spans 80-140bp and a
    // model could fit it with a constant.
    issueDate: '2026-05-12', maturityDate: '2032-05-12', originalTermYears: 7,
    officialCleanPrice: 97.398274, runtimeSeedCleanPrice: 97.398,
    debtType: 'CORPORATE_BOND',
    sourceType: 'SIMULATED_CREDIT_POINT', sourceUrl: CURVE,
    issuer: 'Ford Motor Credit Company LLC',
    creditRating: 'BB+', dayCount: '30/360', creditSpreadBp: 310,
  },
]);

const TREASURY_BY_KEY = new Map(TREASURY_SEEDS.map((seed) => [seed.instrumentKey, seed]));

// One optional fixed-clock contract across the state (NFR-CDM09).
export function nowMillis(): number {
  const fixed = process.env.TRADERX_FIXED_UTC_INSTANT;
  if (fixed === undefined || fixed === '') {
    return Date.now();
  }
  const ts = Date.parse(fixed);
  if (!Number.isFinite(ts)) {
    throw new Error(`TRADERX_FIXED_UTC_INSTANT is not a valid instant: ${fixed}`);
  }
  return ts;
}

/** UTC midnight boundary, inclusive: a bond is matured ON its maturity date. */
export function isMatured(maturityDate: string, ts: number): boolean {
  return ts >= Date.parse(`${maturityDate}T00:00:00.000Z`);
}

/** Exactly one sub-type discriminator, agreeing with securityType; identifier rules per type. */
export function assertCdmConditions(instrument: Instrument): Instrument {
  const discriminators = [
    instrument.equityType !== undefined,
    instrument.fundType !== undefined,
    instrument.debtEconomics !== undefined,
  ].filter(Boolean).length;
  if (discriminators !== 1) {
    throw new Error(`${instrument.instrumentKey}: exactly one CDM sub-type discriminator required, found ${discriminators}`);
  }
  const present: SecurityType = instrument.equityType ? 'Equity' : instrument.fundType ? 'Fund' : 'Debt';
  if (instrument.securityType !== present) {
    throw new Error(`${instrument.instrumentKey}: securityType ${instrument.securityType} disagrees with sub-type ${present}`);
  }
  const bbgticker = instrument.identifiers.find((id) => id.identifierType === 'BBGTICKER');
  if (instrument.securityType === 'Debt') {
    if (bbgticker) {
      throw new Error(`${instrument.instrumentKey}: a Debt instrument must not claim BBGTICKER`);
    }
    const hasFigi = instrument.identifiers.some((id) => id.identifierType === 'FIGI');
    // The two provenance kinds carry opposite obligations, and it is the SIMULATED half that
    // matters: a curve point we invented must not be able to acquire a FIGI-shaped identifier
    // later and quietly start reading as a real security. Asserting the absence is what makes
    // "no verified FIGI" a checked property rather than an omission nobody notices.
    const sourceType = instrument.debtEconomics?.priceProvenance.sourceType;
    const simulated = sourceType === 'SIMULATED_CURVE_POINT' || sourceType === 'SIMULATED_CREDIT_POINT';
    if (simulated && hasFigi) {
      throw new Error(`${instrument.instrumentKey}: a ${sourceType} must not claim a FIGI`);
    }
    if (!simulated && !hasFigi) {
      throw new Error(`${instrument.instrumentKey}: an auction-sourced Debt instrument requires a FIGI`);
    }
    // A credit rating is the corporate's second risk factor and a Treasury has no such thing —
    // the US Treasury IS the curve, not a spread over it. Enforcing BOTH directions stops a
    // Treasury from acquiring a decorative AAA (which would invite a consumer to treat the
    // government curve as one credit among many) and stops a corporate from arriving unrated
    // (where a consumer bucketing by rating would silently drop it).
    const corporateDebt = instrument.debtEconomics?.debtType === 'CORPORATE_BOND';
    if (corporateDebt && instrument.debtEconomics?.creditRating === undefined) {
      throw new Error(`${instrument.instrumentKey}: a corporate bond requires a creditRating`);
    }
    if (!corporateDebt && instrument.debtEconomics?.creditRating !== undefined) {
      throw new Error(`${instrument.instrumentKey}: only a corporate bond carries a creditRating`);
    }
    // 30/360 and ACT/ACT disagree by real money, so an unstated convention is a refusal, never
    // a default (RiskExtractMain.loadBondStatics refuses the same way for the same reason).
    if (!instrument.debtEconomics?.dayCount) {
      throw new Error(`${instrument.instrumentKey}: a Debt instrument must state its dayCount`);
    }
    // Exactly one interest discriminator: a bill has no coupon schedule, a note has one, and
    // neither is allowed to be silent about which it is.
    const interestTerms = [
      instrument.debtEconomics?.fixedInterest !== undefined,
      instrument.debtEconomics?.zeroCoupon !== undefined,
    ].filter(Boolean).length;
    if (interestTerms !== 1) {
      throw new Error(`${instrument.instrumentKey}: exactly one of fixedInterest/zeroCoupon required, found ${interestTerms}`);
    }
  } else {
    if (!bbgticker || bbgticker.identifier !== instrument.instrumentKey) {
      throw new Error(`${instrument.instrumentKey}: BBGTICKER identifier must equal the instrument key`);
    }
  }
  return instrument;
}

function equityFundIdentifiers(instrumentKey: string): AssetIdentifier[] {
  const identifiers: AssetIdentifier[] = [{ identifier: instrumentKey, identifierType: 'BBGTICKER' }];
  const baked = BAKED[instrumentKey];
  if (baked) {
    identifiers.push({ identifier: baked.figi, identifierType: 'FIGI' });
  } else {
    logger.warn(`${instrumentKey}: no baked FIGI — serving BBGTICKER only (FR-CDM05)`);
  }
  return identifiers;
}

function classify(instrumentKey: string): { securityType: SecurityType; equityType?: EquityTypeEnum; fundType?: FundProductType } {
  const baked = BAKED[instrumentKey];
  if (baked?.kind === 'ETP') {
    return { securityType: 'Fund', fundType: 'ExchangeTradedFund' };
  }
  if (!baked) {
    logger.warn(`${instrumentKey}: no baked classification — defaulting to Equity/Ordinary (FR-CDM04)`);
  }
  return { securityType: 'Equity', equityType: 'Ordinary' };
}

function buildTreasury(seed: TreasurySeed, ts: number): Instrument {
  const zero = seed.couponRatePercent === 0;
  const corporate = seed.debtType === 'CORPORATE_BOND';
  const debtEconomics: DebtEconomics = {
    debtType: seed.debtType,
    issuer: seed.issuer ?? 'United States Department of the Treasury',
    ...(seed.creditRating ? { creditRating: seed.creditRating } : {}),
    dayCount: seed.dayCount ?? 'ACT/ACT ICMA',
    ...(zero
      ? { zeroCoupon: { rateType: 'Zero', couponRatePercent: 0 } as const }
      : {
        fixedInterest: {
          rateType: 'Fixed', couponRatePercent: seed.couponRatePercent, couponFrequency: 'Semiannual',
        } as const,
      }),
    principalRepayment: { style: 'Bullet', parAmount: 100 },
    issueDate: seed.issueDate,
    maturityDate: seed.maturityDate,
    originalTermYears: seed.originalTermYears,
    priceProvenance: {
      sourceType: seed.sourceType,
      sourceUrl: seed.sourceUrl,
      officialCleanPrice: seed.officialCleanPrice,
      runtimeSeedCleanPrice: seed.runtimeSeedCleanPrice,
      simulated: true,
    },
  };
  return assertCdmConditions({
    instrumentKey: seed.instrumentKey,
    displayName: seed.displayName,
    shortDisplayName: seed.shortDisplayName,
    assetClass: corporate ? 'CORPORATE_BOND' : 'US_TREASURY',
    currency: 'USD',
    securityType: 'Debt',
    debtEconomics,
    matured: isMatured(seed.maturityDate, ts),
    observedAt: new Date(ts).toISOString(),
    identifiers: [
      { identifier: seed.instrumentKey, identifierType: 'Other' },
      ...(seed.figi ? [{ identifier: seed.figi, identifierType: 'FIGI' as const }] : []),
    ],
  });
}

/**
 * The one entry point: a {ticker, companyName} universe row becomes a CDM Instrument. A seeded
 * Treasury key gets its Debt record; everything else classifies off the baked map, defaulting
 * to Equity/Ordinary with a warning — a row is never silently dropped (FR-CDM04).
 */
export function toInstrument(ticker: string, companyName: string): Instrument {
  const ts = nowMillis();
  const treasurySeed = TREASURY_BY_KEY.get(ticker);
  if (treasurySeed) {
    return buildTreasury(treasurySeed, ts);
  }
  const { securityType, equityType, fundType } = classify(ticker);
  return assertCdmConditions({
    instrumentKey: ticker,
    displayName: companyName,
    assetClass: fundType ? 'ETF' : 'Stock',
    currency: 'USD',
    securityType,
    ...(equityType ? { equityType: { equityType } } : {}),
    ...(fundType ? { fundType } : {}),
    matured: false,
    observedAt: new Date(ts).toISOString(),
    identifiers: equityFundIdentifiers(ticker),
  });
}
