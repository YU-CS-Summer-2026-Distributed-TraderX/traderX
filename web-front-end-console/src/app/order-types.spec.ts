import { REASON_TEXT, TIFS_FOR, TypedTicket, defaultTif, trailHint, typedBody, validateTicket } from './order-types';

/**
 * The console's copy of the order-type validation table must answer exactly as the gateway's
 * (OrderTypesBoundaryTest.sc03 holds the same cases on the Java side, with the same text).
 */
describe('order types (console validation, FR-OT41 UI parity)', () => {
  const t = (over: Partial<TypedTicket>): TypedTicket =>
    ({ orderType: 'LIMIT', timeInForce: 'GTC', side: 'Buy', quantity: 10, ...over } as TypedTicket);

  it('refuses each mis-shaped ticket with the gateway wording', () => {
    const cases: [Partial<TypedTicket>, string][] = [
      [{ orderType: 'MARKET', timeInForce: 'IOC', limitPrice: 1 }, 'field not allowed for this orderType'],
      [{ orderType: 'LIMIT', limitPrice: 1, stopPrice: 1 }, 'field not allowed for this orderType'],
      [{ orderType: 'STOP_LIMIT', stopPrice: 1 }, 'limitPrice required'],
      [{ orderType: 'ICEBERG', limitPrice: 1, displayQuantity: 10 }, 'displayQuantity must be positive and below quantity'],
      [{ orderType: 'MARKET', timeInForce: 'GTC' }, 'timeInForce not allowed for this orderType'],
      [{ orderType: 'STOP', stopPrice: 1, timeInForce: 'IOC' }, 'timeInForce not allowed for this orderType'],
      [{ orderType: 'TRAILING_STOP', trailAmount: 1, trailPercentBps: 10 }, REASON_TEXT[11]],
      [{ orderType: 'TRAILING_STOP', trailPercentBps: 5001 }, REASON_TEXT[11]],
      [{ orderType: 'PEGGED', pegReference: 'PRIMARY', limitPrice: 1, pegOffset: 1 }, REASON_TEXT[10]],
      [{ orderType: 'LIMIT', limitPrice: -2 }, 'price must be positive and within range'],
    ];
    for (const [over, text] of cases) {
      expect(validateTicket(t(over))).withContext(JSON.stringify(over)).toBe(text);
    }
  });

  it('refuses inexact values with the gateway wording (review I3)', () => {
    const cases: [Partial<TypedTicket>, string][] = [
      [{ orderType: 'TRAILING_STOP', trailPercentBps: 1.9 }, 'trailPercentBps must be a whole number'],
      [{ orderType: 'ICEBERG', limitPrice: 100, displayQuantity: 3.8 }, 'displayQuantity must be a whole number'],
      [{ orderType: 'LIMIT', limitPrice: 1, quantity: 10.5 }, 'quantity must be a whole number'],
      [{ orderType: 'LIMIT', limitPrice: 101.1234567 }, 'limitPrice allows at most 6 decimal places'],
      [{ orderType: 'PEGGED', pegReference: 'PRIMARY', limitPrice: 1, pegOffset: -1.5 }, 'pegOffset must be a whole number'],
      [{ orderType: 'TRAILING_STOP', trailAmount: 0.0000001 }, 'trailAmount allows at most 6 decimal places'],
      [{ orderType: 'LIMIT', limitPrice: 1, displayQuantity: 3.8 }, REASON_TEXT[3]],   // presence first
    ];
    for (const [over, text] of cases) {
      expect(validateTicket(t(over))).withContext(JSON.stringify(over)).toBe(text);
    }
    expect(validateTicket(t({ orderType: 'LIMIT', limitPrice: 101.123456 }))).toBe('');
  });

  it('accepts a valid ticket of every type', () => {
    expect(validateTicket(t({ orderType: 'MARKET', timeInForce: 'FOK' }))).toBe('');
    expect(validateTicket(t({ orderType: 'LIMIT', limitPrice: 101 }))).toBe('');
    expect(validateTicket(t({ orderType: 'STOP', stopPrice: 101, timeInForce: 'DAY' }))).toBe('');
    expect(validateTicket(t({ orderType: 'STOP_LIMIT', stopPrice: 101, limitPrice: 102 }))).toBe('');
    expect(validateTicket(t({ orderType: 'ICEBERG', limitPrice: 100, displayQuantity: 2 }))).toBe('');
    expect(validateTicket(t({ orderType: 'PEGGED', pegReference: 'MIDPOINT', limitPrice: 101, pegOffset: -1 }))).toBe('');
    expect(validateTicket(t({ orderType: 'TRAILING_STOP', side: 'Sell', trailPercentBps: 150 }))).toBe('');
  });

  it('filters TIF by type and defaults a market order to IOC', () => {
    expect(TIFS_FOR.MARKET).toEqual(['IOC', 'FOK']);
    expect(TIFS_FOR.STOP).toEqual(['DAY', 'GTC']);
    expect(defaultTif('MARKET')).toBe('IOC');
    expect(defaultTif('ICEBERG')).toBe('GTC');
  });

  it('sends only the fields a type uses; a STOP carries no limitPrice', () => {
    const body = typedBody(t({ orderType: 'STOP', stopPrice: 101, limitPrice: 99 }));
    expect(body['stopPrice']).toBe(101);
    expect('limitPrice' in body).toBeFalse();
  });

  it('hints, without refusing, at a sell trail beyond the last trade', () => {
    expect(trailHint(t({ orderType: 'TRAILING_STOP', side: 'Sell', trailAmount: 6 }), 5))
      .toBe('trail must be below the last trade 5');
    expect(trailHint(t({ orderType: 'TRAILING_STOP', side: 'Sell', trailAmount: 1 }), 5)).toBe('');
  });
});
