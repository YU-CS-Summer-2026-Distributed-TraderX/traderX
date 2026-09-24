/**
 * YU18 order types (components/order-types, FR-OT32 and FR-OT41): the console's copy of the ONE
 * validation table. Codes, TIF matrix and wording mirror
 * `order-matcher/.../lmax/OrderTypes.java`; the gateway answers 422 with the same text and the
 * engine is authoritative. The console runs every STATELESS check before submitting; checks that
 * need sequenced state (the last trade, the peg reference, the business date) are the engine's,
 * and its reason is shown verbatim.
 */
export type OrderType =
  'MARKET' | 'LIMIT' | 'STOP' | 'STOP_LIMIT' | 'ICEBERG' | 'PEGGED' | 'TRAILING_STOP';
export type Tif = 'DAY' | 'GTC' | 'IOC' | 'FOK';

export const ORDER_TYPES: OrderType[] =
  ['MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT', 'ICEBERG', 'PEGGED', 'TRAILING_STOP'];

/** FR-OT06 eligibility matrix. */
export const TIFS_FOR: Record<OrderType, Tif[]> = {
  MARKET: ['IOC', 'FOK'],
  LIMIT: ['DAY', 'GTC', 'IOC', 'FOK'],
  STOP: ['DAY', 'GTC'],
  STOP_LIMIT: ['DAY', 'GTC'],
  ICEBERG: ['DAY', 'GTC'],
  PEGGED: ['DAY', 'GTC'],
  TRAILING_STOP: ['DAY', 'GTC'],
};

export const defaultTif = (t: OrderType): Tif => (t === 'MARKET' ? 'IOC' : 'GTC');

/** Same text as OrderTypes.REASON_TEXT, index for index. */
export const REASON_TEXT = [
  'ok',
  'unknown orderType',
  'timeInForce not allowed for this orderType',
  'field not allowed for this orderType',
  'limitPrice required',
  'stopPrice required',
  'price must be positive and within range',
  'quantity must be positive',
  'displayQuantity must be positive and below quantity',
  'pegReference must be PRIMARY or MIDPOINT',
  'pegOffset must not be more aggressive than the reference (buy <= 0, sell >= 0) and within 10000 ticks',
  'exactly one of trailAmount (positive) or trailPercentBps (1-5000) required',
  'negative limitPrice without orderType',
];

/** Engine / gateway reason names a typed order can come back with, in operator words. */
export const REASON_HINT: Record<string, string> = {
  STOP_ALREADY_TRIGGERED: 'the stop is already through the last trade',
  TRAIL_REFERENCE_MISSING: 'nothing has traded yet, so a trailing stop has no watermark',
  TRAIL_INVALID: 'the trail would put the stop at or below zero',
  PEG_REFERENCE_MISSING: 'the book has no reference on that side (a midpoint needs both)',
  PEG_PRICE_INVALID: 'reference plus offset is not a valid price',
  FOK_UNFILLABLE: 'the book could not fill the whole quantity now; nothing traded',
  DAY_EXPIRED: 'the business day ended',
  NO_TRADING_DAY: 'no business day is open; DAY orders are refused',
  CASCADE_LIMIT: 'cancelled by the venue: cascade limit',
};

const MAX_PRICE = 9.2233720368547e14 / 1e6;   // OrderTypes.MAX_PRICE_TICKS in price units

export interface TypedTicket {
  orderType: OrderType;
  timeInForce: Tif;
  side: 'Buy' | 'Sell';
  quantity: number;
  limitPrice?: number;
  stopPrice?: number;
  displayQuantity?: number;
  pegReference?: 'PRIMARY' | 'MIDPOINT';
  pegOffset?: number;
  trailAmount?: number;
  trailPercentBps?: number;
}

const priceOk = (p: number | undefined) => p !== undefined && p > 0 && p <= MAX_PRICE;
const has = (v: number | undefined) => v !== undefined && v !== null && v !== 0;

/** OrderTypes.fieldsAllowed: the fields each type may carry; any other is refused by presence. */
const ALLOWED_FIELDS: Record<OrderType, (keyof TypedTicket)[]> = {
  MARKET: [],
  LIMIT: ['limitPrice'],
  STOP: ['stopPrice'],
  STOP_LIMIT: ['stopPrice', 'limitPrice'],
  ICEBERG: ['limitPrice', 'displayQuantity'],
  PEGGED: ['limitPrice', 'pegReference', 'pegOffset'],
  TRAILING_STOP: ['trailAmount', 'trailPercentBps'],
};
const TYPED_FIELDS: (keyof TypedTicket)[] =
  ['limitPrice', 'stopPrice', 'displayQuantity', 'pegReference', 'pegOffset', 'trailAmount', 'trailPercentBps'];
/** Gateway exactness order and scale: prices carry 6 decimals, counts, bps and peg ticks none. */
const EXACT_FIELDS: [keyof TypedTicket, number][] = [
  ['limitPrice', 6], ['stopPrice', 6], ['displayQuantity', 0], ['pegOffset', 0], ['trailAmount', 6], ['trailPercentBps', 0],
];

/** Decimal places of a JS number as written (1e-7 has 7). */
const decimals = (v: number): number => {
  const [mantissa, exp] = String(v).split('e');
  return Math.max(0, (mantissa.split('.')[1] ?? '').length - Number(exp ?? 0));
};

/** The gateway's exactness text (review I3), or '' when every sent value is exact. */
function exactnessError(t: TypedTicket): string {
  for (const [k, places] of EXACT_FIELDS) {
    const v = t[k] as number | undefined;
    if (v !== undefined && v !== null && decimals(v) > places) {
      return places === 0 ? `${k} must be a whole number` : `${k} allows at most ${places} decimal places`;
    }
  }
  return '';
}

/** FR-OT03 / FR-OT41, stateless: '' when valid, else the shared reason text. */
export function validateTicket(t: TypedTicket): string {
  if (!ORDER_TYPES.includes(t.orderType)) return REASON_TEXT[1];
  if (t.quantity != null && !Number.isInteger(t.quantity)) return 'quantity must be a whole number';
  if (!(t.quantity > 0)) return REASON_TEXT[7];
  if (!TIFS_FOR[t.orderType].includes(t.timeInForce)) return REASON_TEXT[2];
  for (const k of TYPED_FIELDS) {
    const present = k === 'pegReference' ? !!t.pegReference : has(t[k] as number);
    if (present && !ALLOWED_FIELDS[t.orderType].includes(k)) return REASON_TEXT[3];
  }
  const inexact = exactnessError(t);
  if (inexact) return inexact;
  const hasLimit = has(t.limitPrice);
  const hasStop = has(t.stopPrice);
  const hasDisplay = has(t.displayQuantity);
  const hasPeg = !!t.pegReference || has(t.pegOffset);
  const hasTrail = has(t.trailAmount) || has(t.trailPercentBps);
  switch (t.orderType) {
    case 'MARKET':
      return hasLimit || hasStop || hasDisplay || hasPeg || hasTrail ? REASON_TEXT[3] : '';
    case 'LIMIT':
      if (hasStop || hasDisplay || hasPeg || hasTrail) return REASON_TEXT[3];
      if (!hasLimit) return REASON_TEXT[4];
      return priceOk(t.limitPrice) ? '' : REASON_TEXT[6];
    case 'STOP':
      if (hasLimit || hasDisplay || hasPeg || hasTrail) return REASON_TEXT[3];
      if (!hasStop) return REASON_TEXT[5];
      return priceOk(t.stopPrice) ? '' : REASON_TEXT[6];
    case 'STOP_LIMIT':
      if (hasDisplay || hasPeg || hasTrail) return REASON_TEXT[3];
      if (!hasStop) return REASON_TEXT[5];
      if (!hasLimit) return REASON_TEXT[4];
      return priceOk(t.stopPrice) && priceOk(t.limitPrice) ? '' : REASON_TEXT[6];
    case 'ICEBERG':
      if (hasStop || hasPeg || hasTrail) return REASON_TEXT[3];
      if (!hasLimit) return REASON_TEXT[4];
      if (!priceOk(t.limitPrice)) return REASON_TEXT[6];
      return (t.displayQuantity ?? 0) > 0 && (t.displayQuantity ?? 0) < t.quantity ? '' : REASON_TEXT[8];
    case 'PEGGED': {
      if (hasStop || hasDisplay || hasTrail) return REASON_TEXT[3];
      if (t.pegReference !== 'PRIMARY' && t.pegReference !== 'MIDPOINT') return REASON_TEXT[9];
      if (!hasLimit) return REASON_TEXT[4];
      if (!priceOk(t.limitPrice)) return REASON_TEXT[6];
      const off = t.pegOffset ?? 0;
      if (!Number.isInteger(off) || Math.abs(off) > 10_000 || (t.side === 'Buy' ? off > 0 : off < 0)) {
        return REASON_TEXT[10];
      }
      return '';
    }
    case 'TRAILING_STOP': {
      if (hasLimit || hasStop || hasDisplay || hasPeg) return REASON_TEXT[3];
      const amount = has(t.trailAmount);
      const bps = has(t.trailPercentBps);
      if (amount === bps) return REASON_TEXT[11];
      if (amount) return priceOk(t.trailAmount) ? '' : REASON_TEXT[11];
      const b = t.trailPercentBps ?? 0;
      return Number.isInteger(b) && b >= 1 && b <= 5000 ? '' : REASON_TEXT[11];
    }
  }
}

/**
 * A HINT for the one state-dependent refusal a trader is most likely to hit — a sell trail at or
 * beyond the last trade. Advisory only (the engine judges against the sequenced last trade, which
 * the console may see late); '' when there is nothing to say.
 */
export function trailHint(t: TypedTicket, lastTrade: number | undefined): string {
  if (t.orderType !== 'TRAILING_STOP' || t.side !== 'Sell' || !lastTrade || !has(t.trailAmount)) return '';
  return (t.trailAmount ?? 0) >= lastTrade ? `trail must be below the last trade ${lastTrade}` : '';
}

/** The REST body for a typed order: only the fields the type uses (FR-OT03 refuses the rest). */
export function typedBody(t: TypedTicket): Record<string, unknown> {
  const body: Record<string, unknown> = {
    orderType: t.orderType, timeInForce: t.timeInForce, side: t.side, quantity: t.quantity,
  };
  const put = (k: keyof TypedTicket) => { if (has(t[k] as number)) body[k] = t[k]; };
  switch (t.orderType) {
    case 'LIMIT': put('limitPrice'); break;
    case 'STOP': put('stopPrice'); break;
    case 'STOP_LIMIT': put('stopPrice'); put('limitPrice'); break;
    case 'ICEBERG': put('limitPrice'); put('displayQuantity'); break;
    case 'PEGGED': body['pegReference'] = t.pegReference; put('limitPrice'); body['pegOffset'] = t.pegOffset ?? 0; break;
    case 'TRAILING_STOP': put('trailAmount'); put('trailPercentBps'); break;
    default: break;
  }
  return body;
}

/** Live statuses a trader can still cancel (FR-OT27, FR-OT28). */
export const LIVE_STATUSES = ['NEW', 'PARTIALLY_FILLED', 'PENDING_TRIGGER', 'SUSPENDED'];
