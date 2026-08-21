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
    expect(ust.debtEconomics?.fixedInterest?.couponRatePercent).toBe(4.375);
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

  it('carries the five auction seeds with real FIGIs, and every simulated curve point with none (FR-CDM07)', () => {
    const auction = TREASURY_SEEDS.filter((s) => s.sourceType === 'US_TREASURY_AUCTION_RESULT');
    expect(auction.map((seed) => seed.instrumentKey)).toEqual([
      'UST-20280630', 'UST-20310630', 'UST-20360515', 'UST-20460515', 'UST-20560515',
    ]);
    for (const seed of auction) {
      expect(seed.figi).toMatch(/^BBG[0-9A-Z]{9}$/);
    }
    // The load-bearing half: a curve point we invented must carry NO FIGI, so it can never be
    // mistaken for a security someone could actually look up.
    for (const seed of TREASURY_SEEDS.filter((s) => s.sourceType === 'SIMULATED_CURVE_POINT')) {
      expect(seed.figi).toBe('');
      const built = toInstrument(seed.instrumentKey, seed.displayName);
      expect(built.identifiers.some((id) => id.identifierType === 'FIGI')).toBe(false);
    }
    for (const seed of TREASURY_SEEDS) {
      expect(toInstrument(seed.instrumentKey, seed.displayName).securityType).toBe('Debt');
    }
  });

  /**
   * The ticker prefixes the ENGINE uses to derive the fine book grid, mirrored from
   * MatchingEngineClusteredService.FRACTION_OF_PAR_TICKER_PREFIXES. Duplicated deliberately: the
   * engine cannot import this package, and a silent disagreement between the two lists is exactly
   * the failure the test below exists to catch.
   */
  const FRACTION_OF_PAR_PREFIXES = ['UST-', 'CORP-'];

  it('gives every instrument key exactly one seed, and EVERY bond a key the engine grids finely', () => {
    const keys = TREASURY_SEEDS.map((s) => s.instrumentKey);
    expect(new Set(keys).size).toBe(keys.length);

    // ADR-060 derives the fine book grid from the TICKER PREFIX — the grid must be a pure function
    // of committed state, and a symbol's committed state IS its ticker. So this is not a naming
    // convention, it is a functional gate: a bond whose key matches none of these prefixes gets
    // the 0.001 equity grid and every six-decimal limit on it is refused as off-grid.
    //
    // THIS IS THE LOOP-CLOSING ASSERTION. The engine's list and this one must agree, and the way
    // that breaks is by omission: someone adds MUNI- or AGCY- bonds to the reference data, the
    // engine's predicate does not know about them, and they quietly trade on the wrong grid with
    // nothing failing. Asserting it HERE — over every Debt seed — turns that silent exclusion into
    // a test failure naming the instrument.
    // Every seed here builds to securityType Debt — verified, not assumed, so "every Debt
    // instrument" below is a claim about what the catalog actually produces.
    const debt = TREASURY_SEEDS.filter(
      (s) => toInstrument(s.instrumentKey, s.displayName).securityType === 'Debt');
    expect(debt).toHaveLength(TREASURY_SEEDS.length);

    const offConvention = debt.filter(
      (s) => !FRACTION_OF_PAR_PREFIXES.some((p) => s.instrumentKey.startsWith(p)));
    expect(offConvention.map((s) => s.instrumentKey)).toEqual([]);

    // ...and both prefixes are actually exercised, so the assertion above cannot be satisfied by a
    // universe that happens to contain only one kind of bond.
    for (const prefix of FRACTION_OF_PAR_PREFIXES) {
      expect(keys.some((k) => k.startsWith(prefix))).toBe(true);
    }
    expect(keys.filter((k) => k.startsWith('UST-'))).toHaveLength(15);
    expect(keys.filter((k) => k.startsWith('CORP-'))).toHaveLength(4);
  });

  it('builds a bill as zero-coupon with no coupon schedule at all (2c depends on this)', () => {
    const bill = toInstrument('UST-BILL-20270812', 'U.S. Treasury Bill due August 12, 2027');
    expect(bill.securityType).toBe('Debt');
    expect(bill.debtEconomics?.debtType).toBe('US_TREASURY_BILL');
    expect(bill.debtEconomics?.zeroCoupon).toEqual({ rateType: 'Zero', couponRatePercent: 0 });
    // Not "a schedule paying 0%" — no schedule. A consumer that reads fixedInterest.couponFrequency
    // to walk coupons gets undefined here rather than a semiannual schedule that does not exist.
    expect(bill.debtEconomics?.fixedInterest).toBeUndefined();

    const note = toInstrument('UST-20330731', 'U.S. Treasury Note 4.250% due July 31, 2033');
    expect(note.debtEconomics?.zeroCoupon).toBeUndefined();
    expect(note.debtEconomics?.fixedInterest?.couponFrequency).toBe('Semiannual');
  });

  it('builds a corporate with its issuer, rating and 30/360 day count', () => {
    const gs = toInstrument('CORP-GS-20360315', 'The Goldman Sachs Group 5.750% due March 15, 2036');
    expect(gs.securityType).toBe('Debt');
    expect(gs.assetClass).toBe('CORPORATE_BOND');
    expect(gs.debtEconomics?.debtType).toBe('CORPORATE_BOND');
    expect(gs.debtEconomics?.issuer).toBe('The Goldman Sachs Group Inc.');
    expect(gs.debtEconomics?.creditRating).toBe('BBB+');
    expect(gs.debtEconomics?.dayCount).toBe('30/360');
    expect(gs.debtEconomics?.fixedInterest?.couponRatePercent).toBe(5.75);
    expect(gs.identifiers.some((id) => id.identifierType === 'FIGI')).toBe(false);

    // A Treasury is the curve, not a credit — it carries NO rating, and ACT/ACT.
    const ust = toInstrument('UST-20360515', 'whatever');
    expect(ust.debtEconomics?.creditRating).toBeUndefined();
    expect(ust.debtEconomics?.dayCount).toBe('ACT/ACT ICMA');
    expect(ust.debtEconomics?.issuer).toBe('United States Department of the Treasury');
  });

  it('spans a real ratings ladder, so a credit spread is a second factor and not a constant', () => {
    const corps = TREASURY_SEEDS.filter((s) => s.debtType === 'CORPORATE_BOND');
    expect(corps).toHaveLength(4);
    expect(corps.map((s) => s.creditRating)).toEqual(['A-', 'A', 'BBB+', 'BB+']);
    // Investment grade AND high yield: 80bp to 310bp. A set clustered inside 60bp could be fitted
    // with a constant, which would make "credit" indistinguishable from a parallel curve shift.
    const spreads = corps.map((s) => s.creditSpreadBp!);
    expect(Math.min(...spreads)).toBe(80);
    expect(Math.max(...spreads)).toBe(310);
    for (const seed of corps) {
      expect(seed.dayCount).toBe('30/360');
      expect(seed.figi).toBe('');
      expect(seed.instrumentKey.startsWith('CORP-')).toBe(true);
    }
    // One bond above par, so the price path is exercised from both sides.
    expect(corps.some((s) => s.officialCleanPrice > 100)).toBe(true);
    expect(corps.some((s) => s.officialCleanPrice < 100)).toBe(true);
  });

  it('refuses a Treasury that acquires a rating, and a corporate that loses one (FR-CDM04)', () => {
    // Both directions. A decorative AAA on a Treasury would invite a consumer to treat the
    // government curve as one credit among many; an unrated corporate would be silently dropped
    // by anything bucketing on rating.
    const ust = toInstrument('UST-20360515', 'whatever');
    expect(() => assertCdmConditions({
      ...ust,
      debtEconomics: { ...ust.debtEconomics!, creditRating: 'AAA' },
    })).toThrow(/only a corporate bond carries a creditRating/);

    const gs = toInstrument('CORP-GS-20360315', 'whatever');
    const { creditRating, ...unrated } = gs.debtEconomics!;
    expect(() => assertCdmConditions({ ...gs, debtEconomics: unrated }))
      .toThrow(/requires a creditRating/);
  });

  it('refuses a Debt instrument with no stated day count (FR-CDM04)', () => {
    const gs = toInstrument('CORP-GS-20360315', 'whatever');
    const { dayCount, ...noConvention } = gs.debtEconomics!;
    expect(() => assertCdmConditions({ ...gs, debtEconomics: noConvention as never }))
      .toThrow(/must state its dayCount/);
  });

  it('refuses a simulated curve point that acquires a FIGI (FR-CDM04)', () => {
    const seed = TREASURY_SEEDS.find((s) => s.sourceType === 'SIMULATED_CURVE_POINT')!;
    const built = toInstrument(seed.instrumentKey, seed.displayName);
    expect(() => assertCdmConditions({
      ...built,
      identifiers: [...built.identifiers, { identifier: 'BBG000000001', identifierType: 'FIGI' }],
    })).toThrow(/must not claim a FIGI/);
  });
});
