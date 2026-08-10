import { assertCdmConditions, isMatured, toInstrument, TREASURY_SEEDS } from './cdm-catalog';
import { Instrument } from './instrument.model';

describe('cdm-catalog (YU16)', () => {
  afterEach(() => {
    delete process.env.TRADERX_FIXED_UTC_INSTANT;
  });

  it('classifies SPY as Fund/ExchangeTradedFund with both identifiers (SC-CDM01)', () => {
    const spy = toInstrument('SPY', 'SPDR S&P 500 ETF Trust');
    expect(spy.securityType).toBe('Fund');
    expect(spy.fundType).toBe('ExchangeTradedFund');
    expect(spy.equityType).toBeUndefined();
    expect(spy.assetClass).toBe('ETF');
    expect(spy.identifiers).toEqual(expect.arrayContaining([
      { identifier: 'SPY', identifierType: 'BBGTICKER' },
      { identifier: 'BBG000BDTBL9', identifierType: 'FIGI' },
    ]));
  });

  it('classifies IBM as Equity/Ordinary with no fundType (SC-CDM01)', () => {
    const ibm = toInstrument('IBM', 'IBM');
    expect(ibm.securityType).toBe('Equity');
    expect(ibm.equityType).toEqual({ equityType: 'Ordinary' });
    expect(ibm.fundType).toBeUndefined();
    expect(ibm.identifiers).toEqual(expect.arrayContaining([
      { identifier: 'BBG000BLNNH6', identifierType: 'FIGI' },
    ]));
  });

  it('builds the 10Y Treasury as Debt with FIGI + Other and never BBGTICKER (SC-CDM02)', () => {
    const ust = toInstrument('UST-20360515', 'U.S. Treasury Note 4.375% due May 15, 2036');
    expect(ust.securityType).toBe('Debt');
    expect(ust.assetClass).toBe('US_TREASURY');
    expect(ust.shortDisplayName).toBe('UST 10Y');
    expect(ust.debtEconomics?.fixedInterest.couponRatePercent).toBe(4.375);
    expect(ust.debtEconomics?.maturityDate).toBe('2036-05-15');
    expect(ust.matured).toBe(false);
    expect(ust.identifiers).toEqual(expect.arrayContaining([
      { identifier: 'UST-20360515', identifierType: 'Other' },
      { identifier: 'BBG0221YLR31', identifierType: 'FIGI' },
    ]));
    expect(ust.identifiers.some((id) => id.identifierType === 'BBGTICKER')).toBe(false);
  });

  it('flips matured at the UTC-midnight boundary, inclusive, honoring the fixed clock (NFR-CDM09)', () => {
    expect(isMatured('2028-06-30', Date.parse('2028-06-29T23:59:59.999Z'))).toBe(false);
    expect(isMatured('2028-06-30', Date.parse('2028-06-30T00:00:00.000Z'))).toBe(true);
    process.env.TRADERX_FIXED_UTC_INSTANT = '2028-06-30T00:00:00.000Z';
    const ust = toInstrument('UST-20280630', 'whatever');
    expect(ust.matured).toBe(true);
  });

  it('defaults an unbaked ticker to Equity/Ordinary with BBGTICKER only, never dropping it (FR-CDM04/05)', () => {
    const parity = toInstrument('PARITYA', 'Parity Test A');
    expect(parity.securityType).toBe('Equity');
    expect(parity.identifiers).toEqual([{ identifier: 'PARITYA', identifierType: 'BBGTICKER' }]);
  });

  it('throws on a securityType/sub-type disagreement (FR-CDM04)', () => {
    const bad: Instrument = {
      instrumentKey: 'BAD',
      displayName: 'Bad',
      assetClass: 'Stock',
      currency: 'USD',
      securityType: 'Fund',
      equityType: { equityType: 'Ordinary' },
      matured: false,
      observedAt: new Date(0).toISOString(),
      identifiers: [{ identifier: 'BAD', identifierType: 'BBGTICKER' }],
    };
    expect(() => assertCdmConditions(bad)).toThrow(/disagrees/);
  });

  it('carries all five Treasury seeds with real FIGIs (FR-CDM07)', () => {
    expect(TREASURY_SEEDS.map((seed) => seed.instrumentKey)).toEqual([
      'UST-20280630', 'UST-20310630', 'UST-20360515', 'UST-20460515', 'UST-20560515',
    ]);
    for (const seed of TREASURY_SEEDS) {
      expect(seed.figi).toMatch(/^BBG[0-9A-Z]{9}$/);
      expect(toInstrument(seed.instrumentKey, seed.displayName).securityType).toBe('Debt');
    }
  });
});
