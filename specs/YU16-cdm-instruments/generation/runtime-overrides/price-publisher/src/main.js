const fs = require('fs');
const path = require('path');
const express = require('express');
const { connect } = require('nats');
const yahooFinance = require('yahoo-finance2').default;
const { parseOcc, quoteOption } = require('./option-quotes');
const treasury = require('./treasury-pricing');
const fred = require('./fred-curve');
const previousClose = require('./previous-close');
const taqReplay = require('./taq-replay');

const PORT = Number(process.env.PRICE_PUBLISHER_PORT || '18100');
const NATS_URL = process.env.NATS_ADDRESS || `nats://${process.env.NATS_BROKER_HOST || 'localhost'}:4222`;
const BOOTSTRAP_MODE = (process.env.PRICE_BOOTSTRAP_MODE || 'snapshot').toLowerCase();
const PUBLISH_INTERVAL_MIN_MS = Number(process.env.PRICE_PUBLISH_INTERVAL_MIN_MS || '750');
const PUBLISH_INTERVAL_MAX_MS = Number(process.env.PRICE_PUBLISH_INTERVAL_MAX_MS || '1500');
const PUBLISH_BATCH_RATIO = Number(process.env.PRICE_PUBLISH_BATCH_RATIO || '0.25');
// YU16: the default universe gains the five ETFs and five Treasuries (FR-CDM07); the env name
// stays literal (source FR-01704) and its values are general instrument keys.
const TICKERS = (process.env.PRICE_TICKERS || 'AAPL,MSFT,AMZN,GOOGL,META,NVDA,TSLA,IBM,BAC,C,SPY,QQQ,IWM,VTI,GLD,UST-20280630,UST-20310630,UST-20360515,UST-20460515,UST-20560515,UST-20290715,UST-20330731,UST-BILL-20260910,UST-BILL-20261112,UST-BILL-20270211,UST-BILL-20270812,UST-STRIP-20280630,UST-STRIP-20310630,UST-STRIP-20360515,UST-STRIP-20560515,CORP-IBM-20330215,CORP-JPM-20310601,CORP-GS-20360315,CORP-F-20320512')
  .split(',')
  .map((ticker) => ticker.trim().toUpperCase())
  .filter(Boolean);

// YU15: listed option contracts quoted off their underlying. Without a feed for them, options are
// MISSING in every EOD snapshot, and YU06's fail-safe halts any account holding one - so no
// account with an option position ever gets marked. The default chain matches the YU14 seeded
// universe (specs/YU14-listed-equity-options/reference-data/instruments.csv).
const DEFAULT_OPTION_CHAIN = [
  'AAPL260918C00220000', 'AAPL260918P00220000', 'AAPL260918C00240000', 'AAPL260918P00240000',
  'AAPL260918C00260000', 'AAPL260918P00260000', 'AAPL261218C00220000', 'AAPL261218P00220000',
  'AAPL261218C00240000', 'AAPL261218P00240000', 'AAPL261218C00260000', 'AAPL261218P00260000',
  'MSFT260918C00370000', 'MSFT260918P00370000', 'MSFT260918C00390000', 'MSFT260918P00390000',
  'MSFT260918C00410000', 'MSFT260918P00410000', 'MSFT261218C00370000', 'MSFT261218P00370000',
  'MSFT261218C00390000', 'MSFT261218P00390000', 'MSFT261218C00410000', 'MSFT261218P00410000'
].join(',');

const OPTION_CONTRACTS = (process.env.PRICE_OPTION_CONTRACTS ?? DEFAULT_OPTION_CHAIN)
  .split(',')
  .map((symbol) => symbol.trim().toUpperCase())
  .filter(Boolean);

// One flat implied vol for every contract, deliberately. A consumer running the same Black-Scholes
// inputs reproduces our mark exactly, which is what makes the reconciliation meaningful; a smile
// would be a modelling opinion we have no business asserting. Published in /health so the number
// is discoverable without reading this file.
const OPTION_IV = Number(process.env.PRICE_OPTION_IV || '0.25');
const OPTION_RATE = Number(process.env.PRICE_OPTION_RATE || '0.04');
const OPTION_MIN_PREMIUM = Number(process.env.PRICE_OPTION_MIN_PREMIUM || '0.01');

// YU17 (ADR-068 rule 2): the provenance a price carries. `simulated` is the boolean nobody can
// misread; this is the human-legible half of the same fact. Without both, "was that number from a
// vendor?" is unanswerable after the fact, which is the question that would actually matter.
const CURVE_SOURCE = 'fred-us-treasury-cmt-curve';

const SNAPSHOT_PATH = path.join(__dirname, '..', 'data', 'snapshot-prices.json');

const app = express();
const state = {
  source: BOOTSTRAP_MODE,
  nats: null,
  prices: new Map(),
  volatilityBands: new Map(),
  // symbol -> parsed OCC contract. Membership here is what marks an instrument as derived:
  // it is quoted off its underlying rather than walked, and it gets no volatility band.
  optionContracts: new Map(),
  // YU16: instrumentKey -> treasury static (coupon, term, maturity, seed). Membership here is
  // what marks an instrument as a Treasury: walked in percent space by treasury-pricing.js,
  // emitted as a fraction of par (ADR-057), no volatility band, suppressed once matured.
  treasuries: new Map(),
  // YU17 (ADR-069 rule 4): which rung of the hierarchy each instrument actually OPENED on, per
  // class, counted as bootstrapPrices decides it. Reported on /health, and that report is the
  // whole countermeasure to this ADR's trap -- an opening price that silently came from the seed
  // looks exactly like one that came from a close, so the counts are the only thing that can
  // tell them apart without reading logs.
  openingTally: {
    equity: { previousClose: 0, staticSeed: 0 },
    treasury: { previousClose: 0, staticSeed: 0 },
    corporate: { previousClose: 0, staticSeed: 0 }
  }
};

const VOLATILITY_PROFILES = [
  { name: 'extended_4pct', upperRoll: 0.20, overflowPct: 0.04 },
  { name: 'extended_2pct', upperRoll: 0.80, overflowPct: 0.02 },
  { name: 'strict', upperRoll: 1.00, overflowPct: 0.00 }
];

function normalizePublishConfig() {
  const minMs = Number.isFinite(PUBLISH_INTERVAL_MIN_MS) && PUBLISH_INTERVAL_MIN_MS > 0
    ? Math.floor(PUBLISH_INTERVAL_MIN_MS)
    : 750;
  const maxMsCandidate = Number.isFinite(PUBLISH_INTERVAL_MAX_MS) && PUBLISH_INTERVAL_MAX_MS > 0
    ? Math.floor(PUBLISH_INTERVAL_MAX_MS)
    : 1500;
  const maxMs = Math.max(minMs, maxMsCandidate);
  const ratio = Number.isFinite(PUBLISH_BATCH_RATIO)
    ? Math.min(1, Math.max(0.01, PUBLISH_BATCH_RATIO))
    : 0.25;
  return { minMs, maxMs, ratio };
}

function round3(value) {
  return Math.round(Number(value) * 1000) / 1000;
}

function clamp(value, low, high) {
  return Math.max(low, Math.min(high, value));
}

function loadSnapshot() {
  try {
    return JSON.parse(fs.readFileSync(SNAPSHOT_PATH, 'utf8'));
  } catch (err) {
    return {};
  }
}

function createFallbackQuote(ticker) {
  const basis = 100 + Math.random() * 50;
  return {
    ticker,
    openPrice: round3(basis),
    closePrice: round3(basis * (0.99 + Math.random() * 0.02)),
    price: round3(basis),
    source: 'fallback'
  };
}

function normalizeQuote(ticker, openPrice, closePrice, source) {
  const safeOpen = Number.isFinite(openPrice) ? Number(openPrice) : undefined;
  const safeClose = Number.isFinite(closePrice) ? Number(closePrice) : undefined;
  const open = round3(safeOpen ?? safeClose ?? 100);
  const close = round3(safeClose ?? safeOpen ?? open);
  return {
    ticker,
    openPrice: open,
    closePrice: close,
    price: close,
    source
  };
}

// YU16: a Treasury seeds from its auction-derived clean price and never touches Yahoo. The walk
// state is percent-of-par (cleanPercent); everything stored in `price`/`openPrice`/`closePrice`
// is the six-decimal fraction of par the rest of the system consumes (ADR-057).
function normalizeTreasuryQuote(instrumentKey, snapshotEntry) {
  const seedPercent = treasury.round3(Number(snapshotEntry.runtimeSeedCleanPrice));
  const fraction = treasury.pctToFraction(seedPercent);
  return {
    ticker: instrumentKey,
    openPrice: fraction,
    closePrice: fraction,
    price: fraction,
    cleanPercent: seedPercent,
    seedCleanPercent: seedPercent,
    couponRatePercent: Number(snapshotEntry.couponRatePercent),
    originalTermYears: Number(snapshotEntry.originalTermYears),
    // The issue date is what makes a real coupon schedule possible: generated from issue forward
    // it can carry a short or long first coupon, where a schedule derived from maturity alone
    // silently assumes neither. It is also the only way a zero-coupon bill can be modelled at
    // all — it has no coupon to walk backwards from.
    issueDate: String(snapshotEntry.issueDate),
    maturityDate: String(snapshotEntry.maturityDate),
    assetClass: String(snapshotEntry.assetClass),
    // Carried per instrument, never inferred from the asset class here. The extract and this feed
    // must agree on the convention or a consumer reconciling our accrual against our own mark
    // sees a break that looks like a pricing bug.
    dayCount: String(snapshotEntry.dayCount || treasury.DAY_COUNT.ACT_ACT_ICMA),
    creditRating: snapshotEntry.creditRating,
    officialSeedCleanPrice: Number(snapshotEntry.officialCleanPrice),
    simulated: true,
    // Stashed, not just used: a tick flips `source` between this and CURVE_SOURCE as the curve
    // comes and goes, and reconstructing the synthetic string from assetClass at every tick is
    // the kind of duplicated branch that drifts.
    syntheticSource: snapshotEntry.assetClass === 'CORPORATE_BOND'
      ? 'simulated-corporate-credit-spread'
      : 'simulated-us-treasury-auction-seed',
    source: snapshotEntry.assetClass === 'CORPORATE_BOND'
      ? 'simulated-corporate-credit-spread'
      : 'simulated-us-treasury-auction-seed'
  };
}

function chooseVolatilityProfile() {
  const roll = Math.random();
  for (const profile of VOLATILITY_PROFILES) {
    if (roll <= profile.upperRoll) {
      return profile;
    }
  }
  return VOLATILITY_PROFILES[VOLATILITY_PROFILES.length - 1];
}

function shuffleInPlace(items) {
  for (let i = items.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [items[i], items[j]] = [items[j], items[i]];
  }
  return items;
}

function buildVolatilityBand(quote, profile) {
  const baselineLow = Math.min(Number(quote.openPrice), Number(quote.closePrice));
  const baselineHigh = Math.max(Number(quote.openPrice), Number(quote.closePrice));
  const low = round3(baselineLow * (1 - profile.overflowPct));
  const high = round3(baselineHigh * (1 + profile.overflowPct));
  return {
    profile: profile.name,
    overflowPct: profile.overflowPct,
    low,
    high
  };
}

function ensureVolatilityBand(ticker, quote) {
  if (!state.volatilityBands.has(ticker)) {
    const profile = chooseVolatilityProfile();
    state.volatilityBands.set(ticker, buildVolatilityBand(quote, profile));
  }
  return state.volatilityBands.get(ticker);
}

function assignStartupVolatilityBands() {
  const tickers = shuffleInPlace(
    Array.from(state.prices.keys()).filter(
      (ticker) => !state.optionContracts.has(ticker) && !state.treasuries.has(ticker)));
  const total = tickers.length;
  if (total === 0) {
    return;
  }

  const countExtended4 = Math.floor(total * 0.2);
  const countStrict = Math.floor(total * 0.2);
  const countExtended2 = total - countExtended4 - countStrict;

  const assignments = [
    ...Array(countExtended4).fill('extended_4pct'),
    ...Array(countExtended2).fill('extended_2pct'),
    ...Array(countStrict).fill('strict')
  ];

  for (let i = 0; i < tickers.length; i += 1) {
    const ticker = tickers[i];
    const quote = state.prices.get(ticker);
    if (!quote) {
      continue;
    }
    const profileName = assignments[i] || 'extended_2pct';
    const profile = VOLATILITY_PROFILES.find((entry) => entry.name === profileName) || VOLATILITY_PROFILES[1];
    state.volatilityBands.set(ticker, buildVolatilityBand(quote, profile));
  }
}

// YU16: which snapshot entries are BONDS — walked in percent space, emitted as a fraction of par
// at six decimals, never quoted from an external provider. Asking about the asset class rather
// than testing for US_TREASURY is what stopped corporates from silently falling through to
// yfinance the moment they were added: three separate branches keyed off that one string, and a
// corporate would have matched none of them.
function isBond(snapshotEntry) {
  return Boolean(snapshotEntry)
    && (snapshotEntry.assetClass === 'US_TREASURY' || snapshotEntry.assetClass === 'CORPORATE_BOND');
}

async function loadFromYahoo(ticker, snapshotEntry) {
  // YU16: a bond entry must never reach a symbology/quote provider (NFR-CDM07).
  if (isBond(snapshotEntry)) {
    throw new Error(`bond ${ticker} cannot be loaded from yfinance`);
  }
  const quote = await yahooFinance.quote(ticker);
  const open = Number(quote.regularMarketOpen);
  const close = Number(quote.regularMarketPreviousClose ?? quote.regularMarketPrice);
  if (!Number.isFinite(open) && !Number.isFinite(close)) {
    throw new Error('yfinance quote missing open/close');
  }
  return normalizeQuote(ticker, open, close, 'yfinance');
}

// Registered after the underlyings so quoteOption always finds a spot to price against. A
// contract whose underlying is not in the feed is skipped rather than quoted off a fabricated
// price - a mark with no basis is worse than no mark.
function optionModel() {
  return { iv: OPTION_IV, rate: OPTION_RATE, minPremium: OPTION_MIN_PREMIUM, round: round3 };
}

function bootstrapOptionContracts() {
  for (const symbol of OPTION_CONTRACTS) {
    const contract = parseOcc(symbol);
    if (!contract) {
      console.warn(`skipping unparseable option symbol: ${symbol}`);
      continue;
    }
    if (!state.prices.has(contract.root)) {
      console.warn(`skipping ${symbol}: underlying ${contract.root} is not in the feed`);
      continue;
    }
    state.optionContracts.set(symbol, contract);
    const quote = quoteOption(symbol, contract, state.prices.get(contract.root), optionModel(), Date.now());
    if (quote) {
      state.prices.set(symbol, quote);
    }
  }
}

// The date the session is opening on, in UTC -- trade-processor keys EOD sessions by UTC date and
// runs in a UTC container, so a host-local date here would diverge from the service every evening
// and ask for the wrong "strictly earlier than". treasury.now() rather than Date.now() so the
// state's one fixed-clock contract (NFR-CDM09) reaches this read too.
function openingDateIso() {
  return new Date(treasury.now()).toISOString().slice(0, 10);
}

function tallyOpen(bucket, fromClose) {
  const counters = state.openingTally[bucket];
  if (counters) {
    counters[fromClose ? 'previousClose' : 'staticSeed'] += 1;
  }
}

async function bootstrapPrices() {
  const snapshot = loadSnapshot();
  // YU17 (ADR-069 rules 1-3): ONE read, before anything is seeded, resolving rule 2 server-side.
  // Null on every failure -- unreachable, timed out, unauthorized, no published session, an empty
  // one -- and every one of those is a sentence on /health rather than a silent fall-through.
  const closes = await previousClose.load(openingDateIso());
  for (const ticker of TICKERS) {
    const snapshotEntry = snapshot[ticker];
    const close = closes ? closes.get(ticker) : undefined;

    // YU16: bonds seed from the snapshot only — never yfinance, never fallback.
    if (isBond(snapshotEntry)) {
      const seeded = normalizeTreasuryQuote(ticker, snapshotEntry);
      const opened = previousClose.openBondFromClose(seeded, close);
      const quote = opened || seeded;
      state.prices.set(ticker, quote);
      state.treasuries.set(ticker, quote);
      tallyOpen(snapshotEntry.assetClass === 'CORPORATE_BOND' ? 'corporate' : 'treasury',
        Boolean(opened));
      continue;
    }

    if (BOOTSTRAP_MODE === 'yfinance') {
      try {
        const quote = await loadFromYahoo(ticker, snapshotEntry);
        state.prices.set(ticker, quote);
        ensureVolatilityBand(ticker, quote);
        // The TOP rung of ADR-068's hierarchy for equities: an external source answered, so the
        // close is not consulted. Counted as a seed open because it is not a close open -- the
        // per-class `source` on /health is the one that names yfinance, via `state.source`.
        tallyOpen('equity', false);
        continue;
      } catch (err) {
        // fall through to previous close / snapshot / fallback
      }
    }

    // ADR-069 rule 1, the new rung: the prior published close outranks the static seed, and the
    // static seed stays the floor. `openPrice` is set once here and never mutated by updateTick,
    // so it stays the witness of where this session actually opened.
    if (Number.isFinite(close)) {
      const quote = normalizeQuote(ticker, close, close, 'previous-close');
      state.prices.set(ticker, quote);
      ensureVolatilityBand(ticker, quote);
      tallyOpen('equity', true);
    } else if (snapshotEntry) {
      const quote = normalizeQuote(ticker, Number(snapshotEntry.openPrice), Number(snapshotEntry.closePrice), 'snapshot');
      state.prices.set(ticker, quote);
      ensureVolatilityBand(ticker, quote);
      tallyOpen('equity', false);
    } else {
      const quote = createFallbackQuote(ticker);
      state.prices.set(ticker, quote);
      ensureVolatilityBand(ticker, quote);
      tallyOpen('equity', false);
    }
  }
}

// YU17 (ADR-068): the clean price implied by the REAL constant-maturity curve, or null to walk.
//
// This is the whole integration, and it is three lines of substance because the model was already
// right: interpolate the curve at the bond's REMAINING term, then hand that yield to the existing
// cleanPriceFromYield — the exact inverse of the ytmPercent solve toPayload already publishes. No
// bond math is added, replaced or duplicated here.
//
// Remaining term, not originalTermYears: a 2026-issued 30Y and a seasoned one maturing next year
// are the same point on a curve keyed by original term, and that is not a small error.
//
// CORPORATES ARE EXCLUDED DELIBERATELY, and it is a licensing decision rather than an omission: a
// corporate needs a credit spread over this curve, and every free spread series on FRED is
// third-party and copyright-marked (ICE BofA, Moody's — checked 2026-08-23). They keep walking.
function curveCleanPercent(quote, ts) {
  // Defaulted the same way toPayload defaults it. A seeded quote with no assetClass IS a Treasury
  // by that convention, and two places disagreeing about it would put a bond on the curve in the
  // payload while pricing it off the walk — consistent-looking and wrong.
  if ((quote.assetClass || 'US_TREASURY') !== 'US_TREASURY') {
    return null;
  }
  const yearsToMaturity = (Date.parse(`${quote.maturityDate}T00:00:00.000Z`) - ts)
    / (365.2425 * 86400000);
  const curveYield = fred.yieldForYears(yearsToMaturity);
  if (curveYield === null) {
    return null;
  }
  const clean = treasury.cleanPriceFromYield(quote, new Date(ts), curveYield, quote.dayCount);
  return clean === null ? null : treasury.round3(clean);
}

function updateTick(ticker, sharedRoll) {
  const contract = state.optionContracts.get(ticker);
  if (contract) {
    // Derived: re-price from the underlying's current tick. No band, no drift of its own.
    const quote = quoteOption(ticker, contract, state.prices.get(contract.root), optionModel(), Date.now());
    if (quote) {
      state.prices.set(ticker, quote);
      return quote;
    }
  }
  const treasuryQuote = state.prices.get(ticker);
  if (state.treasuries.has(ticker) && treasuryQuote) {
    const ts = treasury.now();
    if (treasury.isMatured(treasuryQuote.maturityDate, ts)) {
      // Matured: report without advancing state; publishTick suppresses it (FR-CDM21).
      return { ...treasuryQuote, matured: true, quoteTimestamp: new Date(ts).toISOString() };
    }
    // YU17: the real curve if there is one, the random walk if there is not. Note what does NOT
    // apply on the real path: the term profile's seed +/- maxDistance clamp. That band exists to
    // keep an invented walk plausible; clamping a REAL price to a synthetic seed's neighbourhood
    // would silently discard the very thing being integrated.
    const fromCurve = curveCleanPercent(treasuryQuote, ts);
    const localRoll = Math.random() * 2 - 1;
    const nextPercent = fromCurve !== null ? fromCurve : treasury.updateTreasuryCleanPrice(
      treasuryQuote, Number.isFinite(sharedRoll) ? sharedRoll : Math.random() * 2 - 1, localRoll);
    const next = {
      ...treasuryQuote,
      cleanPercent: nextPercent,
      price: treasury.pctToFraction(nextPercent),
      matured: false,
      simulated: fromCurve === null,
      // `|| .source` so a quote seeded without syntheticSource (the unit tests do exactly that)
      // keeps its own provenance rather than publishing `undefined`.
      source: fromCurve === null
        ? (treasuryQuote.syntheticSource || treasuryQuote.source)
        : CURVE_SOURCE
    };
    state.prices.set(ticker, next);
    return next;
  }
  const current = state.prices.get(ticker) || createFallbackQuote(ticker);
  // YU17 (ADR-070): a tape symbol takes its reference from the replayed extract, not the walk.
  // Only price/source/asOf move — openPrice stays the bootstrap witness (ADR-069 rule 1), and the
  // volatility band is NOT consulted: it exists to keep an invented walk plausible, and clamping a
  // REAL Feb-2025 print to a synthetic band would discard the very thing being integrated (the
  // same reasoning as the Treasury curve branch above). A ticker the extract does not carry —
  // GOOGL (suffix-merged root, issues/open/tick-store-drops-taq-sym-suffix-*), FNMA (OTC, not in
  // TAQ) — falls through to the walk with its provenance unchanged.
  const replayed = taqReplay.priceAt(ticker, treasury.now());
  if (replayed) {
    const next = {
      ...current,
      price: replayed.price,
      source: replayed.source,
      asOf: replayed.asOf
    };
    state.prices.set(ticker, next);
    return next;
  }
  const band = ensureVolatilityBand(ticker, current);
  const low = band.low;
  const high = band.high;
  const drift = current.price * (Math.random() * 0.01 - 0.005);
  const nextPrice = round3(clamp(current.price + drift, low, high));
  const next = {
    ...current,
    price: nextPrice
  };
  state.prices.set(ticker, next);
  return next;
}

function toPayload(quote) {
  if (state.treasuries.has(quote.ticker)) {
    // YU16 (FR-CDM19): additive Treasury fields; one instant per payload; price IS the
    // fraction of par (ADR-057) and cleanPrice equals it by contract.
    const ts = treasury.now();
    const asOf = new Date(ts).toISOString();
    return {
      ticker: quote.ticker,
      instrumentKey: quote.ticker,
      price: quote.price,
      openPrice: quote.openPrice,
      closePrice: quote.closePrice,
      asOf,
      source: quote.source,
      assetClass: quote.assetClass || 'US_TREASURY',
      ...(quote.creditRating ? { creditRating: quote.creditRating } : {}),
      cleanPrice: quote.price,
      priceSemantics: 'CLEAN_FRACTION_OF_PAR',
      // A real price->yield solve off a real coupon schedule, not the old one-line approximation.
      // Semiannual bond basis for EVERY instrument — coupon-bearing, bill or STRIP — so the
      // points are comparable and a consumer can bootstrap a curve across them. The convention is
      // stated on the wire rather than assumed: a price and a yield are only a pair with respect
      // to a day count, and that is the first question any tie-out discrepancy asks.
      ytmPercent: treasury.ytmPercent(quote, ts, quote.cleanPercent, quote.dayCount),
      yieldConvention: 'SEMIANNUAL_BOND',
      dayCount: quote.dayCount || treasury.DAY_COUNT.ACT_ACT_ICMA,
      quoteTimestamp: asOf,
      maturityDate: quote.maturityDate,
      matured: Boolean(quote.matured),
      // Was a hardcoded `true`. It is now the actual provenance of THIS price (ADR-068 rule 2):
      // false means the number came off the real curve, and a hardcoded true would have made the
      // integration invisible on the wire — the one place it has to be visible.
      simulated: Boolean(quote.simulated),
      officialSeedCleanPrice: quote.officialSeedCleanPrice
    };
  }
  return {
    ticker: quote.ticker,
    price: quote.price,
    openPrice: quote.openPrice,
    closePrice: quote.closePrice,
    // YU17 (ADR-070 decision 4): a replayed quote carries the TRUE tape timestamp — a real price
    // at a fabricated time is a third provenance category, and `asOf` is the half of it a boolean
    // cannot express. A walked quote keeps stamping "now", which is when it was invented.
    asOf: quote.asOf || new Date().toISOString(),
    source: quote.source
  };
}

// Fixed-point scale matching order-matcher's Px.SCALE (price ticks = price x 1e6, 3dp HALF_UP
// rounded before scaling — see lmax/Px.java). Encoding to the same integer representation here
// means the BLP never re-parses a decimal string on the hot ingestion path.
const PRICE_TICK_SCALE = 1000n;

function toPriceTicks(price) {
  const milliUnits = Math.round(Number(price) * 1000); // 3dp HALF_UP, matching Px.toTicks
  return BigInt(milliUnits) * PRICE_TICK_SCALE;
}

// YU16 (FR-CDM15): a Treasury's fraction-of-par mark keeps all six decimals on the wire —
// 0.998780 -> 998,780 ticks. The 3dp path above stays the equity/option contract; routing a
// bond through it would round the fraction to one percentage decimal (0.998780 -> 0.999000).
function toTreasuryPriceTicks(fraction) {
  return BigInt(Math.round(Number(fraction) * 1000000));
}

// 16-byte fixed-width struct for the BLP's hot ingestion path: int64 price ticks + int64 source
// epoch millis, big-endian. No ticker in the payload — same convention as the JSON subject,
// ticker comes from the NATS subject (pricing-tick-bin.<TICKER>). Published in addition to (not
// instead of) the JSON envelope below: the Angular front-end's live price ticker still consumes
// the JSON pricing.* subject and must not be broken by this.
function encodeBinaryTick(quote) {
  const buf = Buffer.alloc(16);
  const ticks = state.treasuries.has(quote.ticker)
    ? toTreasuryPriceTicks(quote.price)
    : toPriceTicks(quote.price);
  buf.writeBigInt64BE(ticks, 0);
  buf.writeBigInt64BE(BigInt(Date.now()), 8);
  return buf;
}

function publishTick(quote) {
  if (!state.nats) {
    return;
  }
  if (quote.matured) {
    // A matured Treasury stops quoting (FR-CDM21): no JSON envelope, no binary tick.
    return;
  }
  const topic = `pricing.${quote.ticker}`;
  const envelope = {
    topic,
    payload: toPayload(quote),
    date: new Date().toISOString(),
    from: 'price-publisher',
    type: 'PriceTick'
  };
  state.nats.publish(topic, Buffer.from(JSON.stringify(envelope)));
  state.nats.publish(`pricing-tick-bin.${quote.ticker}`, encodeBinaryTick(quote));
}

function pickRandomSubset(items, count) {
  const shuffled = shuffleInPlace([...items]);
  return shuffled.slice(0, Math.max(1, Math.min(count, items.length)));
}

function schedulePublishLoop() {
  const publishCfg = normalizePublishConfig();
  const loop = () => {
    const tickers = Array.from(state.prices.keys());
    if (tickers.length > 0) {
      const batchSize = Math.max(1, Math.ceil(tickers.length * publishCfg.ratio));
      const selected = pickRandomSubset(tickers, batchSize);
      // YU16: one shared roll per batch correlates the Treasury curve (FR-CDM18).
      const sharedRoll = Math.random() * 2 - 1;
      for (const ticker of selected) {
        const quote = updateTick(ticker, sharedRoll);
        publishTick(quote);
      }
    }
    const delayMs = publishCfg.minMs + Math.floor(Math.random() * (publishCfg.maxMs - publishCfg.minMs + 1));
    setTimeout(loop, delayMs);
  };
  setTimeout(loop, 600);
}

function ensureTicker(ticker) {
  const normalized = String(ticker || '').trim().toUpperCase();
  if (!normalized) {
    return null;
  }
  if (!state.prices.has(normalized)) {
    // YU16 (FR-CDM21): an unknown bond key gets no fabricated quote — 404, never fallback.
    // createFallbackQuote invents a price around 100-150, which for an instrument quoted as a
    // FRACTION of par would be a hundredfold nonsense that still looks like a number.
    if (normalized.startsWith('UST-') || normalized.startsWith('CORP-')) {
      return null;
    }
    const quote = createFallbackQuote(normalized);
    state.prices.set(normalized, quote);
    ensureVolatilityBand(normalized, quote);
  }
  return state.prices.get(normalized);
}

// YU17 (ADR-069 rule 4). `source` is the rung that won for the class; the counts are beside it
// so a MIXED class (some instruments in the close, some not -- an instrument the feed has never
// published has no close) is visible as numbers rather than hidden behind one word. Options carry
// no opening price of their own by decision: an option is re-priced from its underlying's current
// tick, so it inherits any overnight gap through the model it already uses, and giving it a stored
// close would fight that re-price.
function openingSourceStatus() {
  const byClass = {};
  for (const [name, counters] of Object.entries(state.openingTally)) {
    byClass[name] = {
      source: counters.previousClose > 0 ? 'previous-close' : 'static-seed',
      previousClose: counters.previousClose,
      staticSeed: counters.staticSeed
    };
  }
  byClass.option = { source: 'derived-from-underlying', contracts: state.optionContracts.size };
  return { ...previousClose.status(), byClass };
}

app.get('/health', (_req, res) => {
  const profileCounts = {};
  for (const band of state.volatilityBands.values()) {
    profileCounts[band.profile] = (profileCounts[band.profile] || 0) + 1;
  }
  res.json({
    status: 'ok',
    source: state.source,
    tickers: Array.from(state.prices.keys()).length,
    optionContracts: state.optionContracts.size,
    treasuries: state.treasuries.size,
    // Published so a consumer reconciling against our option marks can reproduce them exactly.
    optionModel: { model: 'black-scholes', impliedVolatility: OPTION_IV, riskFreeRate: OPTION_RATE },
    // Published so a consumer of Treasury marks knows the semantics without reading this file.
    // Published so a consumer reconciling our bond marks knows exactly what basis they are on,
    // without reading treasury-pricing.js. Same reasoning as optionModel above.
    treasuryModel: {
      priceSemantics: 'CLEAN_FRACTION_OF_PAR',
      walkSpace: 'percent-of-par',
      tickScale: 1000000,
      yieldConvention: 'SEMIANNUAL_BOND',
      // Per instrument, not global: Treasuries ACT/ACT (ICMA), corporates 30/360.
      dayCounts: [treasury.DAY_COUNT.ACT_ACT_ICMA, treasury.DAY_COUNT.THIRTY_360],
      solver: 'newton-with-bisection-fallback'
    },
    // YU17 (ADR-068): where Treasury yields are actually coming from right now, the per-series
    // copyright check that let them in, and the FRED attribution string. `provider: 'none'` is the
    // synthetic default and is not a degraded state.
    priceSource: fred.status(),
    // YU17 (ADR-069 rule 4): WHICH RUNG THE SESSION ACTUALLY OPENED ON, per instrument class.
    //
    // This is not polish. It is the countermeasure to the ADR's stated trap: a failed close-read
    // and a successful one produce prices that are equally plausible, so "did the session open
    // from the prior close?" has no observable answer without this field. Its job is to make the
    // ABSENCE of continuity loud -- `previousSession: null` with `error` naming the reason, and a
    // class whose `source` reads `static-seed`.
    //
    // Sits beside priceSource deliberately: that one answers "where is this TICK from" (the
    // running source, which for Treasuries supersedes the open within one FRED interval); this
    // one answers "where did this SESSION START". Both are needed and neither implies the other.
    openingSource: openingSourceStatus(),
    // YU17 (ADR-070): the replay clock, derived at request time from the same arithmetic priceAt
    // uses — never a stored position, which would be a second clock able to disagree with the
    // first. `error` is the loud form of "the walk is what you are getting": an unfetched extract
    // and a working replay produce equally plausible prices, so this block is the only reading
    // that can tell them apart (the same trap, and the same countermeasure, as openingSource).
    taqReplay: taqReplay.status(treasury.now()),
    publish: normalizePublishConfig(),
    volatilityBands: profileCounts
  });
});

app.get('/prices', (_req, res) => {
  const rows = Array.from(state.prices.values()).map((quote) => toPayload(quote));
  res.json({ prices: rows });
});

app.get('/prices/:ticker', (req, res) => {
  const quote = ensureTicker(req.params.ticker);
  if (!quote) {
    res.status(404).json({ message: 'ticker not found' });
    return;
  }
  res.json(toPayload(quote));
});

async function main() {
  // Deliberately not awaited: rule 1 says the system starts with no network. The first ticks are
  // synthetic and flip to the curve when the first poll lands, and `simulated` on the wire says
  // exactly which is which at every instant.
  fred.start();
  // Sync, local, all-or-nothing; every failure path is a /health sentence and the walk (rule 1).
  taqReplay.load();
  await bootstrapPrices();
  bootstrapOptionContracts();
  assignStartupVolatilityBands();
  state.nats = await connect({ servers: NATS_URL, maxReconnectAttempts: -1 });
  schedulePublishLoop();
  app.listen(PORT, () => {
    console.log(`price-publisher listening on :${PORT}`);
  });
}

if (require.main === module) {
  main().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}

// Exported for tests (state inspection + the fallback/404 rule); the service entrypoint above
// only runs when launched directly.
module.exports = { ensureTicker, updateTick, toPayload, encodeBinaryTick, state };
