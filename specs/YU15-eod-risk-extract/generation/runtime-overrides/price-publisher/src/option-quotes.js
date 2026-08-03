// YU15: quoting listed option contracts off their underlying.
//
// Without a feed for options they are MISSING in every EOD snapshot, and YU06's fail-safe halts
// any account holding one — so no account with an option position ever gets marked. This module
// is the feed side of that fix; it is deliberately a separate file from main.js so the parsing
// and the pricing can be tested directly (see test/option-quotes.test.js).
//
// This is market-data simulation — the venue has to quote something — not a risk calculation.
// Nothing here computes greeks, and positions are still marked from the published close exactly
// as an equity's are.

// An option's identity IS its unpadded OCC symbol (YU14 ADR-052):
// <root><yymmdd><C|P><strike x 1000, 8 digits>, e.g. AAPL260918C00240000.
// Underlying, expiry, call/put and strike are all derivable from it, so the feed needs no
// instrument reference data of its own.
const OCC_TAIL = 15; // yymmdd + C|P + 8-digit strike

function parseOcc(symbol) {
  if (typeof symbol !== 'string' || symbol.length <= OCC_TAIL) {
    return null;
  }
  const root = symbol.slice(0, symbol.length - OCC_TAIL);
  const tail = symbol.slice(symbol.length - OCC_TAIL);
  if (!/^[A-Z]{1,6}$/.test(root) || !/^\d{6}[CP]\d{8}$/.test(tail)) {
    return null;
  }
  const yy = Number(tail.slice(0, 2));
  const mm = Number(tail.slice(2, 4));
  const dd = Number(tail.slice(4, 6));
  if (mm < 1 || mm > 12 || dd < 1 || dd > 31) {
    return null;
  }
  return {
    root,
    call: tail[6] === 'C',
    strike: Number(tail.slice(7)) / 1000,
    // OCC years are two-digit and every contract this venue lists is forward-dated, so 2000-based
    // is correct here. Expiry is 21:00 UTC — the US market close on the expiry date.
    expiryMillis: Date.UTC(2000 + yy, mm - 1, dd, 21, 0, 0)
  };
}

// Abramowitz & Stegun 26.2.17 — standard normal CDF, max error ~7.5e-8. Enough for a synthetic
// venue quote and dependency-free.
function normCdf(x) {
  const sign = x < 0 ? -1 : 1;
  const z = Math.abs(x) / Math.SQRT2;
  const t = 1 / (1 + 0.3275911 * z);
  const y = 1 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t
    + 0.254829592) * t * Math.exp(-z * z);
  return 0.5 * (1 + sign * y);
}

/**
 * Black-Scholes with a flat implied vol. Flat is deliberate: a consumer running the same inputs
 * reproduces our mark exactly, which is what makes a reconciliation meaningful. A volatility
 * smile would be a modelling opinion this venue has no business asserting.
 *
 * Floored at intrinsic value, so a quote can never imply a free arbitrage.
 */
function blackScholes(spot, strike, yearsToExpiry, call, iv, rate) {
  const intrinsic = Math.max(call ? spot - strike : strike - spot, 0);
  if (!(yearsToExpiry > 0) || !(spot > 0) || !(strike > 0) || !(iv > 0)) {
    return intrinsic; // expired or degenerate: worth exactly its intrinsic value
  }
  const sqrtT = Math.sqrt(yearsToExpiry);
  const d1 = (Math.log(spot / strike) + (rate + (iv * iv) / 2) * yearsToExpiry) / (iv * sqrtT);
  const d2 = d1 - iv * sqrtT;
  const discountedStrike = strike * Math.exp(-rate * yearsToExpiry);
  const value = call
    ? spot * normCdf(d1) - discountedStrike * normCdf(d2)
    : discountedStrike * normCdf(-d2) - spot * normCdf(-d1);
  return Math.max(value, intrinsic);
}

/**
 * Quote one contract off its underlying's CURRENT tick. Derived every time, never walked
 * independently — otherwise a call and a put on the same strike would drift into contradicting
 * each other and no pricing engine could reconcile the surface.
 *
 * Returns null when the underlying is unknown: a mark with no basis is worse than no mark.
 */
function quoteOption(symbol, contract, underlying, model, nowMillis) {
  if (!contract || !underlying) {
    return null;
  }
  const years = (contract.expiryMillis - nowMillis) / (365.25 * 24 * 60 * 60 * 1000);
  const at = (spot) => Math.max(
    blackScholes(Number(spot), contract.strike, years, contract.call, model.iv, model.rate),
    model.minPremium);
  return {
    ticker: symbol,
    openPrice: model.round(at(underlying.openPrice)),
    closePrice: model.round(at(underlying.closePrice)),
    price: model.round(at(underlying.price)),
    source: 'black-scholes'
  };
}

module.exports = { parseOcc, normCdf, blackScholes, quoteOption };
