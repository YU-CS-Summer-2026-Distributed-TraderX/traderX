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
  figi: string;
  couponRatePercent: number;
  issueDate: string;
  maturityDate: string;
  originalTermYears: 2 | 5 | 10 | 20 | 30;
  officialCleanPrice: number;
  runtimeSeedCleanPrice: number;
  debtType: 'US_TREASURY_NOTE' | 'US_TREASURY_BOND';
  sourceUrl: string;
}

const AUCTION = 'https://www.treasurydirect.gov/instit/annceresult/press/preanre/2026';

// The five approved Treasuries (FR-CDM07): real FIGIs, TreasuryDirect auction provenance.
// Clean prices here are quoted percent-of-par (provenance/display space); everything the
// runtime STORES for a bond is a fraction of par (ADR-057).
export const TREASURY_SEEDS: readonly TreasurySeed[] = Object.freeze([
  {
    instrumentKey: 'UST-20280630', shortDisplayName: 'UST 2Y',
    displayName: 'U.S. Treasury Note 4.125% due June 30, 2028',
    figi: 'BBG022ZR1Z79', couponRatePercent: 4.125,
    issueDate: '2026-06-30', maturityDate: '2028-06-30', originalTermYears: 2,
    officialCleanPrice: 99.878432, runtimeSeedCleanPrice: 99.878,
    debtType: 'US_TREASURY_NOTE', sourceUrl: `${AUCTION}/R_20260623_2.pdf`,
  },
  {
    instrumentKey: 'UST-20310630', shortDisplayName: 'UST 5Y',
    displayName: 'U.S. Treasury Note 4.125% due June 30, 2031',
    figi: 'BBG022ZR1Z51', couponRatePercent: 4.125,
    issueDate: '2026-06-30', maturityDate: '2031-06-30', originalTermYears: 5,
    officialCleanPrice: 99.664909, runtimeSeedCleanPrice: 99.665,
    debtType: 'US_TREASURY_NOTE', sourceUrl: `${AUCTION}/R_20260624_3.pdf`,
  },
  {
    instrumentKey: 'UST-20360515', shortDisplayName: 'UST 10Y',
    displayName: 'U.S. Treasury Note 4.375% due May 15, 2036',
    figi: 'BBG0221YLR31', couponRatePercent: 4.375,
    issueDate: '2026-05-15', maturityDate: '2036-05-15', originalTermYears: 10,
    officialCleanPrice: 99.256552, runtimeSeedCleanPrice: 99.257,
    debtType: 'US_TREASURY_NOTE', sourceUrl: `${AUCTION}/R_20260512_3.pdf`,
  },
  {
    instrumentKey: 'UST-20460515', shortDisplayName: 'UST 20Y',
    displayName: 'U.S. Treasury Bond 5.000% due May 15, 2046',
    figi: 'BBG0226BZH97', couponRatePercent: 5.0,
    issueDate: '2026-06-01', maturityDate: '2046-05-15', originalTermYears: 20,
    officialCleanPrice: 98.481099, runtimeSeedCleanPrice: 98.481,
    debtType: 'US_TREASURY_BOND', sourceUrl: `${AUCTION}/R_20260520_2.pdf`,
  },
  {
    instrumentKey: 'UST-20560515', shortDisplayName: 'UST 30Y',
    displayName: 'U.S. Treasury Bond 5.000% due May 15, 2056',
    figi: 'BBG0221YLR40', couponRatePercent: 5.0,
    issueDate: '2026-05-15', maturityDate: '2056-05-15', originalTermYears: 30,
    officialCleanPrice: 99.292811, runtimeSeedCleanPrice: 99.293,
    debtType: 'US_TREASURY_BOND', sourceUrl: `${AUCTION}/R_20260513_2.pdf`,
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
    if (!instrument.identifiers.some((id) => id.identifierType === 'FIGI')) {
      throw new Error(`${instrument.instrumentKey}: a Debt instrument requires a FIGI`);
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
  const debtEconomics: DebtEconomics = {
    debtType: seed.debtType,
    issuer: 'United States Department of the Treasury',
    fixedInterest: { rateType: 'Fixed', couponRatePercent: seed.couponRatePercent, couponFrequency: 'Semiannual' },
    principalRepayment: { style: 'Bullet', parAmount: 100 },
    issueDate: seed.issueDate,
    maturityDate: seed.maturityDate,
    originalTermYears: seed.originalTermYears,
    priceProvenance: {
      sourceType: 'US_TREASURY_AUCTION_RESULT',
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
    assetClass: 'US_TREASURY',
    currency: 'USD',
    securityType: 'Debt',
    debtEconomics,
    matured: isMatured(seed.maturityDate, ts),
    observedAt: new Date(ts).toISOString(),
    identifiers: [
      { identifier: seed.instrumentKey, identifierType: 'Other' },
      { identifier: seed.figi, identifierType: 'FIGI' },
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
