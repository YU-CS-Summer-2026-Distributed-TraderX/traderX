#!/usr/bin/env node
// session.mjs — a compressed trading session driven by synthetic PARTICIPANTS, not by a load
// generator. Everything in scripts/bench/load/ answers "how fast"; this answers "what does a day
// look like", which is what the EOD risk extract needs and has never had.
//
// WHAT THIS DOES AND DOES NOT CLAIM
// It does NOT make prices real. No market data is involved, and price discovery only aggregates
// whatever information is in the order flow — synthetic agents produce a synthetic price. What it
// produces is realistic price FORMATION: the price moves because someone lifted the offer, because
// depth was consumed, because a large order pushed through levels. For a matching engine that is
// the relevant claim and it is one we can make honestly. Do not let any doc, comment or slide
// upgrade it to "our prices are real".
//
// The agents:
//   market maker      quotes two-sided around its OWN inventory, widens as inventory grows, pulls
//                     quotes on a large adverse move. Without it there is no spread, nothing to
//                     trade against, and every other agent degenerates into a load generator.
//   momentum taker    leans with the recent move — creates trends and impact.
//   mean-reversion    fades it — supplies the other side and stops the price walking off.
//   institutional     one large parent per session, sliced by the EXISTING YU08 TWAP engine.
//                     Submitted by run-session.sh, not here; this file never reimplements slicing.
//
// HOW THE PRICE MOVES, EXACTLY. The maker's quotes are the only resting liquidity, so a taker's
// marketable order walks the maker's own levels: the sim consumes them in price order and sets the
// new mark to the DEEPEST level consumed. That is not a guess about the engine — it is the same
// arithmetic the engine performs (price-time priority, execution at the resting order's limit), and
// the last print is therefore the last level touched, which under ADR-051 IS the security's mark.
// A taker that finds no depth rests instead, and the mark correctly does not move.
//
// TRAPS THIS SCRIPT IS BUILT AROUND — all of them observed on this rig:
//   * PRICE_COLLAR is the book BAND, not a percentage. LimitBook anchors a band of BOOK_LEVELS
//     (1<<17) ticks of 0.001 on a security's FIRST limit order of the epoch, with that price
//     mid-band — so ~±$65.5 around wherever the security first traded, forever, until a fresh
//     epoch. seed-proof-fixtures.sh crosses AAPL/IBM/NVDA at 200, so NVDA (published ~$903) cannot
//     be quoted at its real level at all. Hence probeAnchor(): every symbol is probed at its
//     published price before the session and dropped, loudly, if the band refuses it. Never assume.
//   * ADR-051: a price tick seeds a security's mark only until a trade prints; after that the last
//     TRADE price is the mark. These agents' trades therefore become the marks, so a runaway agent
//     would walk a mark somewhere absurd and collar everything after it. --max-move clamps the
//     model to a band around the session anchor, and a PRICE_COLLAR rejection pulls it back in.
//   * Agents quote RELATIVE to the prevailing price. A hardcoded limit is how a script rejects
//     every order (a limit of 100 against a live IBM of ~187 did exactly that).
//   * kind's idle CPU is the rig's weak point — three busy-spinning Aeron members on a Docker VM.
//     Default rates here are deliberately ~2 orders/s/symbol, not thousands: a sim that saturates
//     the box makes a healthy cluster look like it is failing.
//
// The maker CANCELS its quotes before repricing. Two reasons, both load-bearing: stale quotes at
// every price the maker ever posted are a wall the price cannot move through, and an unbounded open
// set is what made an account's blotter pull ~14MB (see the note in yu08-algo-slicing.sh).
//
// Usage (normally via run-session.sh, which owns the port-forwards and the TWAP parent):
//   node scripts/sim/session.mjs --minutes 10 --symbols 12
//   node scripts/sim/session.mjs --minutes 45 --symbols 20 --seed 7 --rate 12
//
// Seeded PRNG: same --seed and same universe => same decision stream, so a session is re-runnable.

const args = process.argv.slice(2);
const arg = (name, def) => {
  const i = args.indexOf(name);
  return i !== -1 && args[i + 1] !== undefined ? args[i + 1] : def;
};
const num = (name, env, def) => Number(arg(name, process.env[env] ?? def));

const cfg = {
  matcher: arg('--matcher', process.env.MATCHER_URL || 'http://localhost:18110').replace(/\/$/, ''),
  positions: arg('--positions', process.env.POSITIONS_URL || 'http://localhost:18090').replace(/\/$/, ''),
  prices: arg('--prices', process.env.PRICES_URL || 'http://localhost:18100').replace(/\/$/, ''),
  // The universe MUST be a subset of price-publisher's PRICE_TICKERS. An account holding a security
  // with no published price halts that account's EOD P&L, and yu15-risk-extract asserts halted=0 —
  // so an off-universe symbol here fails a proof about a system that is fine. run-session.sh reads
  // the list off the live Deployment rather than duplicating it.
  universe: arg('--universe', process.env.PRICE_TICKERS
    || 'AAPL,MSFT,AMZN,GOOGL,META,NVDA,TSLA,IBM,BAC,C,JPM,GS,MS,UBS,DB,COF,DFS,FNMA,FIS,FNF')
    .split(',').map((s) => s.trim().toUpperCase()).filter(Boolean),
  minutes: num('--minutes', 'SIM_MINUTES', 10),
  symbols: num('--symbols', 'SIM_SYMBOLS', 12),
  seed: num('--seed', 'SIM_SEED', 42),
  // Taker arrivals per second across the WHOLE market at intensity 1.0; the session shape multiplies
  // this. The maker's requoting adds ~(2 * levels * symbols / quoteMs) orders/s on top.
  rate: num('--rate', 'SIM_RATE', 6),
  quoteMs: num('--quote-ms', 'SIM_QUOTE_MS', 3000),
  levels: num('--levels', 'SIM_LEVELS', 2),
  quoteSize: num('--quote-size', 'SIM_QUOTE_SIZE', 120),
  takeSize: num('--take-size', 'SIM_TAKE_SIZE', 70),
  spreadBps: num('--spread-bps', 'SIM_SPREAD_BPS', 8),
  invLimit: num('--inv-limit', 'SIM_INV_LIMIT', 4000),
  maxMove: num('--max-move', 'SIM_MAX_MOVE', 0.10),
  mmAccount: num('--mm-account', 'SIM_MM_ACCOUNT', 42422),
  momAccount: num('--momentum-account', 'SIM_MOMENTUM_ACCOUNT', 22214),
  revAccount: num('--reversion-account', 'SIM_REVERSION_ACCOUNT', 52355),
  // Reported, not driven: run-session.sh submits the parent and the YU08 engine slices it.
  parentAccount: num('--parent-account', 'PARENT_ACCOUNT', 62654),
  tag: arg('--tag', process.env.SIM_TAG || `sim-${process.pid}`),
};

// ---------------------------------------------------------------------------------------------
// Deterministic PRNG (mulberry32). Math.random() would make a session unreproducible, and "run it
// again with the same seed" is how you tell a rig problem from an agent problem.
let seedState = cfg.seed >>> 0;
function rnd() {
  seedState = (seedState + 0x6d2b79f5) >>> 0;
  let t = seedState;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const pick = (arr) => arr[Math.floor(rnd() * arr.length)];
/** Knuth: fine for the small per-tick means this scheduler uses. */
function poisson(lambda) {
  const limit = Math.exp(-lambda);
  let k = 0;
  let p = 1;
  do { k++; p *= rnd(); } while (p > limit);
  return k - 1;
}
/** Heavy-tailed order size: mean ~base, with the occasional block that walks several levels. */
const expSize = (base) => Math.max(1, Math.round(-Math.log(1 - rnd()) * base));

const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));
// Limit prices must sit on the book grid (LimitBook.onGrid: a multiple of 0.001) or the engine
// rejects them INVALID. 2dp is on-grid and is what the algo engine's children use too.
const px2 = (x) => Math.round(x * 100) / 100;

/**
 * Intraday intensity: an open hump, a quiet midday, a close hump. A flat Poisson rate all session
 * produces a flat-looking day and defeats the whole point of the exercise.
 */
const intensity = (t) => 0.35 + 1.15 * Math.exp(-t / 0.10) + 0.85 * Math.exp(-(1 - t) / 0.08);

// ---------------------------------------------------------------------------------------------
const rejects = new Map();
let sent = 0;
let accepted = 0;
let failed = 0;
const tally = (m) => rejects.set(m, (rejects.get(m) || 0) + 1);

async function postJson(url, body, timeoutMs = 10000) {
  const ctl = AbortSignal.timeout(timeoutMs);
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: ctl,
  });
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

/**
 * Returns the engine's orderRef on acceptance, or null. Never throws: one dead call must not end a
 * session, and the reject tally is the diagnostic that matters.
 *
 * Pass the symbol state to attribute a PRICE_COLLAR to it. A collar means the model has drifted to
 * the edge of a band it cannot see, so the model is pulled back toward the probed anchor — the only
 * self-correction available, and better than emitting rejects for the rest of the session.
 */
async function order(accountId, ticker, side, quantity, limitPrice, s = null) {
  sent++;
  try {
    const r = await postJson(`${cfg.matcher}/orders`,
      { accountId, ticker, side, quantity, limitPrice, clientOrderId: `${cfg.tag}-${sent}` });
    if (r.status === 200) { accepted++; return r.body.orderRef; }
    const reason = r.body.reason || `HTTP ${r.status}`;
    tally(reason);
    if (s && reason === 'PRICE_COLLAR') { s.collar++; s.mid = (s.mid + s.anchor) / 2; }
    return null;
  } catch (e) {
    failed++;
    tally(e.name === 'TimeoutError' ? 'timeout' : e.name);
    return null;
  }
}

/** 409/404 are ordinary here — the order filled or was already terminal — so nothing is reported. */
async function cancel(orderRef) {
  try { await postJson(`${cfg.matcher}/cancel`, { orderRef }, 8000); } catch { /* see above */ }
}

async function getJson(url, timeoutMs = 8000) {
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  return res.json();
}

// ---------------------------------------------------------------------------------------------
/** Per-symbol state. `mid` is the sim's model of the mark; under ADR-051 the engine's mark is the
 *  last trade price, and every trade here prints at a level this sim posted — so they track. */
const book = new Map();

/**
 * Probe the band before trading a symbol.
 *
 * The band is invisible: nothing serves it, and a security anchored at 200 by an old fixture will
 * refuse its own real price forever. So establish the fact the only way available — post one share
 * and read the verdict. On an UNANCHORED security this also anchors the band where we want it,
 * which is the other half of why it runs first.
 */
async function probeAnchor(sym, price) {
  // AT the published price, not near it: on an unanchored security the band centres on this order,
  // so probing low would hand the session an off-centre band with no headroom above. One share, so
  // that crossing something already resting costs nothing and moves the mark by nothing that
  // matters.
  const ref = await order(cfg.mmAccount, sym, 'Buy', 1, px2(price));
  if (ref === null) return false;
  await cancel(ref);
  return true;
}

async function buildUniverse() {
  const wanted = cfg.universe.slice(0, Math.max(1, Math.min(cfg.symbols, cfg.universe.length)));
  const dropped = [];
  for (const sym of wanted) {
    let price;
    try {
      price = Number((await getJson(`${cfg.prices}/prices/${sym}`)).price);
    } catch (e) {
      dropped.push(`${sym} (no published price: ${e.message})`);
      continue;
    }
    if (!Number.isFinite(price) || price <= 0) { dropped.push(`${sym} (price ${price})`); continue; }
    if (!await probeAnchor(sym, price)) {
      dropped.push(`${sym} (band refuses ${price.toFixed(2)} — anchored elsewhere this epoch)`);
      continue;
    }
    book.set(sym, {
      sym,
      anchor: price,          // band-safe centre; --max-move is measured from here
      mid: price,
      ema: price,
      open: price,
      hi: price,
      lo: price,
      inv: 0,                 // maker inventory, optimistic between position polls
      quotes: [],             // live maker quotes: {ref, side, px, qty}
      lastQuoteMid: price,
      trades: 0,
      volume: 0,
      collar: 0,
      pulled: 0,
    });
  }
  return dropped;
}

// ---------------------------------------------------------------------------------------------
/**
 * Market maker. Two-sided around its own inventory: the spread widens with |inventory| and the
 * whole quote is skewed AGAINST it (long => quote lower, to get hit on the offer), which is what
 * makes an inventory-managing maker rather than a constant-width quote machine. It pulls entirely
 * when the mark has moved hard since its last refresh — the "do not stand in front of it" reflex,
 * and the thing that stops a trending market from bleeding the maker into a runaway position.
 */
async function requote(s) {
  const stale = s.quotes;
  s.quotes = [];
  await Promise.all(stale.map((q) => cancel(q.ref)));

  const move = Math.abs(s.mid - s.lastQuoteMid) / s.lastQuoteMid;
  s.lastQuoteMid = s.mid;
  if (move > cfg.spreadBps / 1e4 * 6) { s.pulled++; return; }

  const invFrac = clamp(s.inv / cfg.invLimit, -1, 1);
  const half = s.mid * (cfg.spreadBps / 1e4) * (1 + Math.abs(invFrac));
  const skew = -invFrac * half;
  const posted = [];
  for (let i = 0; i < cfg.levels; i++) {
    const off = half * (1 + i);
    const qty = Math.max(1, Math.round(cfg.quoteSize * (1 + i)));   // deeper levels are larger
    posted.push(['Buy', px2(s.mid + skew - off), qty], ['Sell', px2(s.mid + skew + off), qty]);
  }
  const refs = await Promise.all(posted.map(([side, px, qty]) => order(cfg.mmAccount, s.sym, side, qty, px, s)));
  refs.forEach((ref, i) => {
    if (ref !== null) s.quotes.push({ ref, side: posted[i][0], px: posted[i][1], qty: posted[i][2] });
  });
}

/**
 * A taker crossing the maker. The order walks the maker's levels in price order; the mark becomes
 * the DEEPEST level consumed, which is where the engine's last print lands. Depth left unconsumed
 * stays quoted; a taker that exhausts the far side rests instead and moves nothing.
 */
async function take(s, accountId, side, quantity) {
  const cross = s.quotes
    .filter((q) => q.side !== side && q.qty > 0)
    .sort((a, b) => (side === 'Buy' ? a.px - b.px : b.px - a.px));

  // Plan the walk first and commit it only once the engine has accepted the order — a rejected
  // order consumes nothing, and a model that had already eaten the depth would quote into a book
  // that still holds it.
  const plan = [];
  let remaining = quantity;
  let touched = null;
  for (const level of cross) {
    if (remaining <= 0) break;
    const fill = Math.min(remaining, level.qty);
    plan.push([level, fill]);
    remaining -= fill;
    touched = level.px;
  }

  // Marketable at the deepest level touched, so the engine fills the cheaper levels first and the
  // final print lands exactly where this model says it does. With no depth, rest near the mark.
  const limit = px2(touched ?? (side === 'Buy' ? s.mid * 1.001 : s.mid * 0.999));
  if (await order(accountId, s.sym, side, quantity, limit, s) === null) return;

  for (const [level, fill] of plan) {
    level.qty -= fill;
    s.inv += side === 'Buy' ? -fill : fill;   // the maker is the other side of every one of these
    s.volume += fill;
  }
  s.quotes = s.quotes.filter((q) => q.qty > 0);
  if (touched !== null) {
    s.trades++;
    s.mid = touched;                       // ADR-051: the print IS the new mark
  }
  // Keep the model inside the band it was probed in. A mark that walks out of the band collars
  // every order after it, and there is no way back short of a fresh epoch.
  s.mid = clamp(s.mid, s.anchor * (1 - cfg.maxMove), s.anchor * (1 + cfg.maxMove));
  s.hi = Math.max(s.hi, s.mid);
  s.lo = Math.min(s.lo, s.mid);
}

/**
 * Refresh the maker's true inventory from the read model, and take the price signal hiding in it.
 *
 * Inventory the model did not predict is flow the model never saw: the TWAP parent's children,
 * another lane's traffic, anything. It must move the mark — the maker did not choose to sell, it
 * got LIFTED, and someone lifting the offer is precisely the print. Without this the institutional
 * parent's whole market impact would be invisible to every other agent, which is the one thing this
 * generator exists to make visible. The optimistic updates in take() keep the skew responsive
 * between polls; this is what keeps both the inventory and the mark honest.
 */
async function syncInventory() {
  let rows;
  try {
    rows = await getJson(`${cfg.positions}/positions/${cfg.mmAccount}`);
  } catch { return; } // the read model is a projection and lags; the model carries on regardless
  for (const row of rows) {
    const s = book.get(row.security);
    if (!s) continue;
    const external = (Number(row.quantity) || 0) - s.inv;
    s.inv = Number(row.quantity) || 0;
    // Half a quote is the noise floor: projection lag and the modelling error from orders that
    // rested instead of filling should not jitter the mark.
    if (Math.abs(external) < cfg.quoteSize / 2) continue;
    // Our side of their trade, and the best price of ours still standing on it.
    const ourSide = external < 0 ? 'Sell' : 'Buy';
    const prices = s.quotes.filter((q) => q.side === ourSide).map((q) => q.px);
    if (!prices.length) continue;
    s.mid = external < 0 ? Math.max(s.mid, Math.min(...prices)) : Math.min(s.mid, Math.max(...prices));
    s.mid = clamp(s.mid, s.anchor * (1 - cfg.maxMove), s.anchor * (1 + cfg.maxMove));
    s.hi = Math.max(s.hi, s.mid);
    s.lo = Math.min(s.lo, s.mid);
  }
}

// ---------------------------------------------------------------------------------------------
async function main() {
  console.log(`[sim] matcher=${cfg.matcher}  seed=${cfg.seed}  ${cfg.minutes}min compressed session`);
  const dropped = await buildUniverse();
  if (dropped.length) {
    console.log(`[sim] dropped ${dropped.length} symbol(s):`);
    for (const d of dropped) console.log(`        ${d}`);
    console.log('       A band refusal means an old fixture anchored that security elsewhere this');
    console.log('       epoch. Only a fresh epoch re-anchors it; the session runs without it.');
  }
  if (book.size === 0) {
    console.error('[fail] no tradeable symbols — is the matcher seeded (scripts/yu15/seed-proof-fixtures.sh)?');
    process.exit(1);
  }
  const syms = [...book.keys()];
  console.log(`[sim] ${book.size} symbols: ${syms.join(' ')}`);
  console.log(`[sim] maker=${cfg.mmAccount}  momentum=${cfg.momAccount}  reversion=${cfg.revAccount}`);

  await syncInventory();
  await Promise.all([...book.values()].map(requote));

  const totalMs = cfg.minutes * 60_000;
  const t0 = Date.now();
  const tickMs = 250;
  // Stagger the maker across symbols so a refresh cycle is a stream of orders, not a thundering
  // herd every quoteMs — kind's idle CPU is the constraint the whole rate budget is written around.
  const nextQuote = new Map(syms.map((s, i) => [s, t0 + (i * cfg.quoteMs) / syms.length]));
  let nextSync = t0 + 5000;
  let nextReport = t0 + 30_000;
  let stopping = false;
  process.on('SIGINT', () => { stopping = true; console.log('\n[sim] stopping…'); });

  while (!stopping) {
    const now = Date.now();
    const t = (now - t0) / totalMs;
    if (t >= 1) break;
    const shape = intensity(t);

    const work = [];
    for (const [sym, due] of nextQuote) {
      if (now >= due) {
        nextQuote.set(sym, now + cfg.quoteMs);
        work.push(requote(book.get(sym)));
      }
    }

    // Taker arrivals. Both takers read the same signal — the mark's deviation from its own EMA —
    // and lean opposite ways on it, which is what produces trends that then get faded rather than a
    // random walk. tanh keeps a big deviation from turning either agent into a one-way ratchet.
    const n = poisson(cfg.rate * shape * (tickMs / 1000));
    for (let i = 0; i < n; i++) {
      const s = book.get(pick(syms));
      const dev = (s.mid - s.ema) / s.ema;
      const momentum = rnd() < 0.5;
      const bias = 0.42 * Math.tanh(dev / 0.004);
      const buy = momentum ? rnd() < 0.5 + bias : rnd() < 0.5 - bias;
      work.push(take(s, momentum ? cfg.momAccount : cfg.revAccount, buy ? 'Buy' : 'Sell',
        expSize(cfg.takeSize * shape)));
    }

    if (now >= nextSync) { nextSync = now + 5000; work.push(syncInventory()); }
    await Promise.all(work);

    // EMA of the mark, per tick, so "recent" means recent in session time rather than in trades.
    for (const s of book.values()) s.ema += (s.mid - s.ema) * 0.02;

    if (now >= nextReport) {
      nextReport = now + 30_000;
      const moved = [...book.values()].map((s) => (s.mid / s.open - 1) * 100);
      console.log(`[sim] t=${(t * 100).toFixed(0)}%  intensity=${shape.toFixed(2)}  sent=${sent}`
        + `  accepted=${accepted}  rejected=${sent - accepted - failed}`
        + `  move ${Math.min(...moved).toFixed(2)}%..${Math.max(...moved).toFixed(2)}%`);
    }
    await new Promise((r) => setTimeout(r, tickMs));
  }

  // Leave the book QUOTED. "Genuine depth at multiple levels at the cut" is an acceptance criterion
  // for the risk extract, and a maker that tidies up on the way out hands the EOD an empty book.
  await syncInventory();
  console.log('\n[sim] session close\n');
  console.log('  symbol     open      last     chg%      high       low   trades    volume  collar');
  for (const s of book.values()) {
    console.log(`  ${s.sym.padEnd(7)}${s.open.toFixed(2).padStart(9)}${s.mid.toFixed(2).padStart(10)}`
      + `${((s.mid / s.open - 1) * 100).toFixed(2).padStart(9)}${s.hi.toFixed(2).padStart(10)}`
      + `${s.lo.toFixed(2).padStart(10)}${String(s.trades).padStart(9)}${String(s.volume).padStart(10)}`
      + `${String(s.collar).padStart(8)}`);
  }
  const pulled = [...book.values()].reduce((n, s) => n + s.pulled, 0);
  console.log(`\n[sim] orders sent=${sent} accepted=${accepted} transport-failed=${failed}`
    + `  ·  maker pulled its quotes ${pulled}x on adverse moves`);
  if (rejects.size) {
    console.log(`[sim] rejections: ${[...rejects].sort((a, b) => b[1] - a[1]).map(([r, c]) => `${r}=${c}`).join('  ')}`);
  }

  // The evidence, read from the system rather than from this process's own model. What the EOD
  // extract is supposed to stop looking like: N accounts holding exact mirrors, every costBasis the
  // seeded 200.000000, everything netting to zero firm-wide.
  const byAccount = new Map();
  for (const acct of [cfg.mmAccount, cfg.momAccount, cfg.revAccount, cfg.parentAccount]) {
    try {
      const rows = (await getJson(`${cfg.positions}/positions/${acct}`))
        .filter((r) => Number(r.quantity) !== 0);
      byAccount.set(acct, rows);
      console.log(`[sim] ${acct}: ` + (rows.length
        ? rows.map((r) => `${r.security} ${r.quantity}@${Number(r.averageCostBasis).toFixed(2)}`).join('  ')
        : '(flat)'));
    } catch { console.log(`[sim] ${acct}: positions unavailable`); }
  }

  // Say whether the day actually produced a two-sided book, rather than leaving it to be eyeballed.
  const sides = new Map();
  for (const [acct, rows] of byAccount) {
    for (const r of rows) {
      if (!book.has(r.security)) continue;         // fixture holdings this session never touched
      const e = sides.get(r.security) || { long: [], short: [] };
      (Number(r.quantity) > 0 ? e.long : e.short).push(acct);
      sides.set(r.security, e);
    }
  }
  const twoSided = [...sides].filter(([, e]) => e.long.length && e.short.length);
  console.log(twoSided.length
    ? `[sim] two-sided in ${twoSided.length} securities, e.g. ${twoSided[0][0]}: `
      + `long ${twoSided[0][1].long.join(',')} vs short ${twoSided[0][1].short.join(',')}`
    : '[sim] NOTE: no security ended with a long and a short holder — a longer or busier session'
      + ' (--minutes / --rate) gives the agents time to diverge');
}

await main();
process.exit(0);
