const fs = require('fs');
const path = require('path');
const express = require('express');
const { connect } = require('nats');
const yahooFinance = require('yahoo-finance2').default;
const { parseOcc, quoteOption } = require('./option-quotes');

const PORT = Number(process.env.PRICE_PUBLISHER_PORT || '18100');
const NATS_URL = process.env.NATS_ADDRESS || `nats://${process.env.NATS_BROKER_HOST || 'localhost'}:4222`;
const BOOTSTRAP_MODE = (process.env.PRICE_BOOTSTRAP_MODE || 'snapshot').toLowerCase();
const PUBLISH_INTERVAL_MIN_MS = Number(process.env.PRICE_PUBLISH_INTERVAL_MIN_MS || '750');
const PUBLISH_INTERVAL_MAX_MS = Number(process.env.PRICE_PUBLISH_INTERVAL_MAX_MS || '1500');
const PUBLISH_BATCH_RATIO = Number(process.env.PRICE_PUBLISH_BATCH_RATIO || '0.25');
const TICKERS = (process.env.PRICE_TICKERS || 'AAPL,MSFT,AMZN,GOOGL,META,NVDA,TSLA,IBM,BAC,C')
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

const SNAPSHOT_PATH = path.join(__dirname, '..', 'data', 'snapshot-prices.json');

const app = express();
const state = {
  source: BOOTSTRAP_MODE,
  nats: null,
  prices: new Map(),
  volatilityBands: new Map(),
  // symbol -> parsed OCC contract. Membership here is what marks an instrument as derived:
  // it is quoted off its underlying rather than walked, and it gets no volatility band.
  optionContracts: new Map()
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
    Array.from(state.prices.keys()).filter((ticker) => !state.optionContracts.has(ticker)));
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

async function loadFromYahoo(ticker, snapshotEntry) {
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

async function bootstrapPrices() {
  const snapshot = loadSnapshot();
  for (const ticker of TICKERS) {
    const snapshotEntry = snapshot[ticker];
    if (BOOTSTRAP_MODE === 'yfinance') {
      try {
        const quote = await loadFromYahoo(ticker, snapshotEntry);
        state.prices.set(ticker, quote);
        ensureVolatilityBand(ticker, quote);
        continue;
      } catch (err) {
        // fall through to snapshot/fallback
      }
    }

    if (snapshotEntry) {
      const quote = normalizeQuote(ticker, Number(snapshotEntry.openPrice), Number(snapshotEntry.closePrice), 'snapshot');
      state.prices.set(ticker, quote);
      ensureVolatilityBand(ticker, quote);
    } else {
      const quote = createFallbackQuote(ticker);
      state.prices.set(ticker, quote);
      ensureVolatilityBand(ticker, quote);
    }
  }
}

function updateTick(ticker) {
  const contract = state.optionContracts.get(ticker);
  if (contract) {
    // Derived: re-price from the underlying's current tick. No band, no drift of its own.
    const quote = quoteOption(ticker, contract, state.prices.get(contract.root), optionModel(), Date.now());
    if (quote) {
      state.prices.set(ticker, quote);
      return quote;
    }
  }
  const current = state.prices.get(ticker) || createFallbackQuote(ticker);
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
  return {
    ticker: quote.ticker,
    price: quote.price,
    openPrice: quote.openPrice,
    closePrice: quote.closePrice,
    asOf: new Date().toISOString(),
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

// 16-byte fixed-width struct for the BLP's hot ingestion path: int64 price ticks + int64 source
// epoch millis, big-endian. No ticker in the payload — same convention as the JSON subject,
// ticker comes from the NATS subject (pricing-tick-bin.<TICKER>). Published in addition to (not
// instead of) the JSON envelope below: the Angular front-end's live price ticker still consumes
// the JSON pricing.* subject and must not be broken by this.
function encodeBinaryTick(quote) {
  const buf = Buffer.alloc(16);
  buf.writeBigInt64BE(toPriceTicks(quote.price), 0);
  buf.writeBigInt64BE(BigInt(Date.now()), 8);
  return buf;
}

function publishTick(quote) {
  if (!state.nats) {
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
      for (const ticker of selected) {
        const quote = updateTick(ticker);
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
    const quote = createFallbackQuote(normalized);
    state.prices.set(normalized, quote);
    ensureVolatilityBand(normalized, quote);
  }
  return state.prices.get(normalized);
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
    // Published so a consumer reconciling against our option marks can reproduce them exactly.
    optionModel: { model: 'black-scholes', impliedVolatility: OPTION_IV, riskFreeRate: OPTION_RATE },
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
  await bootstrapPrices();
  bootstrapOptionContracts();
  assignStartupVolatilityBands();
  state.nats = await connect({ servers: NATS_URL, maxReconnectAttempts: -1 });
  schedulePublishLoop();
  app.listen(PORT, () => {
    console.log(`price-publisher listening on :${PORT}`);
  });
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
