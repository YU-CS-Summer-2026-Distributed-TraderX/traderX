// YU17 (ADR-072): replayed prints become order flow.
//
// ADR-070 made the tape the *reference*; this makes it *activity*. A deterministic sample of the
// real TAQ prints is submitted to the gateway as ordinary orders, so the engine matches, fills,
// moves positions and P&L, and exercises the collar — driven by trades that genuinely happened.
//
// The four properties that are load-bearing, so nobody removes one by simplifying:
//
//   ONE CLOCK. Position comes from taq-replay.js's positionAt(), never from a second derivation
//   of (now - epochStart) x compression. Two derivations are two clocks and they can disagree;
//   the reference series and the order flow would then be replaying different instants.
//
//   DETERMINISTIC BY POSITION, not by draw. Which print is submitted, on which side, for which
//   account, under which clientOrderId, is a pure function of (symbol, tape slot). Two runs from
//   the same epoch produce the same orders — ADR-072 asks for exactly that, and it is what makes
//   anything built on top reproducible. There is no Math.random() in this file and there must not
//   be one.
//
//   THE SIDE IS INVENTED AND SAYS SO. TAQ trades carry no side and no NBBO survived our ingest,
//   so buy/sell comes from the TICK RULE (uptick buy, downtick sell, zero-tick carries the last
//   non-zero tick). It is an approximation. `sideRule` on /health is the label, and it is there
//   for the same reason ADR-070 put `asOf` on the wire: a number real in one respect and
//   fabricated in another gets a label, not silence.
//
//   REPLAYED FLOW IS ATTRIBUTABLE AT THE SOURCE. Every order goes out on a dedicated account
//   (REPLAY_ACCOUNT_BASE and up), which is a tag the gateway already carries into consensus on
//   InputEvent.accountId. The members count order-shaped commands and trade legs from those
//   accounts separately, so a proof can still bracket its own work while this runs. That is
//   ADR-072's decision and it is why the account range is not a cosmetic choice.
//
// FAILURE CONTRACT — the same as taq-replay.js and previous-close.js, for the same reason: a
// publisher that quietly replayed nothing looks exactly like one with no sample. Every path that
// ends with the replay off records a SENTENCE in `error`, reported on /health.printReplay. No
// sample, no tape reference, no gateway: no orders, and the publisher starts exactly as before.
const fs = require('fs');
const zlib = require('zlib');

const SAMPLE_PATH = process.env.PRINT_REPLAY_SAMPLE_PATH || '/etc/taq-print-sample/prints.bin.gz';
const MAGIC = 'TAQP1';
const HEADER_FIXED = MAGIC.length + 2 + 2 + 4 + 2 + 2 + 4;

// The dedicated accounts. >= 900000 by construction, because that is the predicate the MEMBERS
// use to attribute replayed flow (MatchingEngineClusteredService.REPLAY_ACCOUNT_BASE) — the two
// must agree or the operator counters stop excluding what they exist to exclude. The seeded
// fixture accounts are all five digits, so the range is disjoint from every real one.
const REPLAY_ACCOUNT_BASE = 900000;
const ACCOUNTS = (process.env.PRINT_REPLAY_ACCOUNTS || '900001,900002,900003')
  .split(',').map((a) => Number(a.trim())).filter((a) => Number.isInteger(a) && a > 0);

const GATEWAY = process.env.PRINT_REPLAY_GATEWAY || 'http://order-matcher:18110';
// The gateway's compiled-in default. It is a dev-rig credential, not a secret, and it is here so
// the replayer can enable its own accounts and instruments rather than depending on the proof
// seeder having run.
const RISK_TOKEN = process.env.RISK_CONTROL_TOKEN || 'dev-risk-control';
const QTY = Number(process.env.PRINT_REPLAY_QTY || '10');
// Sample DOWN from what the artifact carries: submit only every STRIDE-th slot. The build already
// solved slots for a target rate (build-taq-print-sample.sh), so this is the live-tuning knob and
// it can only ever reduce. The tick rule follows the strided series, so the replayed side is
// derived from the prints actually replayed rather than from ones nobody sent.
const STRIDE = Math.max(1, Math.floor(Number(process.env.PRINT_REPLAY_STRIDE || '1')));
const POLL_MS = Number(process.env.PRINT_REPLAY_POLL_MS || '1000');
// A stalled poll (a long GC, a paused container, a slow gateway) must not discharge its whole
// backlog at once — the gateway was measured wedging at ~20 orders/s. Skipped slots are counted,
// not queued: this is a demonstration of live activity, not a delivery guarantee.
const MAX_CATCHUP = Math.max(1, Math.floor(Number(process.env.PRINT_REPLAY_MAX_CATCHUP || '2')));
const REQUEST_TIMEOUT_MS = Number(process.env.PRINT_REPLAY_TIMEOUT_MS || '5000');
const ENABLED = (process.env.PRINT_REPLAY || '1') !== '0';

const SIDE_RULE = 'tick-rule (uptick=Buy, downtick=Sell, zero-tick carries the last non-zero tick)'
  + ' — INVENTED: TAQ trades carry no side and no NBBO survives our ingest (ADR-072)';

// How far back the tick rule may walk looking for a price that differs. A run of identical prints
// longer than this takes the default side; bounded so one flat symbol cannot cost a linear scan.
const TICK_LOOKBACK = 16;

const state = {
  attempted: false,
  samplePath: SAMPLE_PATH,
  enabled: ENABLED,
  sample: null,
  // Symbols the sample carries AND the publisher actually holds a tape reference for. A symbol
  // with no replayed reference would put real prices into a book whose collar is anchored by an
  // invented walk, which is a defect neither artifact can show on its own.
  replayed: [],
  unpriced: [],
  slotPos: null,
  submitted: 0,
  accepted: 0,
  rejected: 0,
  skipped: 0,
  failed: 0,
  byReason: {},
  lastOrder: null,
  error: null
};

function fail(sentence) {
  state.sample = null;
  state.error = sentence;
  console.warn(`[print-replay] ${sentence}; no replayed order flow (ADR-068 rule 1)`);
  return null;
}

/** Decode the binary sample. Layout is documented in scripts/yu17/build-taq-print-sample.py; it is
 *  binary rather than JSON because it is `slots` times the size of the median extract and has to
 *  fit a Kubernetes Secret. 0 in the price plane means NO PRINT IN THAT SLOT — never a price. */
function decode(buf) {
  if (buf.length < HEADER_FIXED || buf.toString('latin1', 0, MAGIC.length) !== MAGIC) {
    throw new Error('not a TAQP1 print sample');
  }
  let off = MAGIC.length;
  const slots = buf.readUInt16LE(off); off += 2;
  const windowSeconds = buf.readUInt16LE(off); off += 2;
  const sessionSeconds = buf.readUInt32LE(off); off += 4;
  const dayCount = buf.readUInt16LE(off); off += 2;
  const symbolCount = buf.readUInt16LE(off); off += 2;
  const scale = buf.readUInt32LE(off); off += 4;
  if (slots < 1 || windowSeconds < 1 || sessionSeconds < 1 || dayCount < 1 || symbolCount < 1
      || scale < 1 || sessionSeconds % windowSeconds !== 0) {
    throw new Error(`header is not usable (slots ${slots}, window ${windowSeconds}, session `
      + `${sessionSeconds}, days ${dayCount}, symbols ${symbolCount}, scale ${scale})`);
  }
  const days = [];
  for (let i = 0; i < dayCount; i++, off += 10) {
    days.push(buf.toString('latin1', off, off + 10));
  }
  const symbols = [];
  for (let i = 0; i < symbolCount; i++) {
    const len = buf.readUInt8(off); off += 1;
    symbols.push(buf.toString('latin1', off, off + len)); off += len;
  }
  const windowsPerDay = sessionSeconds / windowSeconds;
  const perSymbol = dayCount * windowsPerDay * slots;
  const want = off + symbolCount * perSymbol * 4;
  if (buf.length !== want) {
    throw new Error(`price plane is ${buf.length - off} bytes, want ${want - off} `
      + `(${symbolCount} x ${dayCount} x ${windowsPerDay} x ${slots} int32)`);
  }
  const prices = new Int32Array(perSymbol * symbolCount);
  for (let i = 0; i < prices.length; i++) {
    prices[i] = buf.readInt32LE(off + i * 4);
  }
  return { slots, windowSeconds, sessionSeconds, days, symbols, scale, prices, perSymbol,
    windowsPerDay, totalSlots: perSymbol };
}

/** Price in whole ticks at one absolute slot, or 0 for "no print in that slot". */
function ticksAt(sample, symIndex, slot) {
  if (slot < 0 || slot >= sample.totalSlots) {
    return 0;
  }
  return sample.prices[symIndex * sample.perSymbol + slot];
}

/**
 * The tick rule, as a pure function of tape position — which is what makes the side reproducible.
 * It compares against the previous slot IN THE REPLAYED SERIES (i.e. stepping by `stride`), not
 * the previous print in the corpus, so the side follows the orders actually sent.
 *
 * An uptick is a buy and a downtick a sell. A zero tick carries the last non-zero tick, which is
 * what "the tick rule" means and why this walks back rather than defaulting on the first equal
 * pair. It is an APPROXIMATION and SIDE_RULE says so.
 */
function sideAt(sample, symIndex, slot, stride) {
  const px = ticksAt(sample, symIndex, slot);
  if (px <= 0) {
    return null;
  }
  for (let i = 1; i <= TICK_LOOKBACK; i++) {
    const prev = ticksAt(sample, symIndex, slot - i * stride);
    if (prev > 0 && prev !== px) {
      return px > prev ? 'Buy' : 'Sell';
    }
  }
  // No differing print within the lookback (the very first slot of the tape, or a dead-flat run).
  // Buy, deterministically — a coin flip here would be the one non-reproducible thing in the file.
  return 'Buy';
}

/**
 * The order one slot produces, or null if that slot carries no print. Pure: everything below is a
 * function of (sample, symIndex, slot) alone, which is the whole determinism claim.
 *
 * accountId mixes the symbol in so two symbols at the same slot do not land on the same account —
 * within a symbol, consecutive slots must differ, or the crossing pair this flow exists to create
 * would be self-trade-prevented instead of traded.
 */
function orderAt(sample, symIndex, slot, stride, accounts, qty) {
  const ticks = ticksAt(sample, symIndex, slot);
  if (ticks <= 0) {
    return null;
  }
  return {
    clientOrderId: `taq-${sample.symbols[symIndex]}-${slot}`,
    accountId: accounts[(slot + symIndex) % accounts.length],
    ticker: sample.symbols[symIndex],
    side: sideAt(sample, symIndex, slot, stride),
    quantity: qty,
    limitPrice: ticks / sample.scale
  };
}

/**
 * Which slots are due for one symbol between two clock readings.
 *
 * Symbols are STAGGERED across each slot's duration (symbol j of S fires at j/S through it). With
 * one print per window and a hundred symbols the un-staggered form is a hundred-order burst every
 * fifteen seconds — the same order count, delivered in the shape most likely to wedge the gateway.
 * Staggering makes the same rate smooth, and it is deterministic: the offset is the symbol's index
 * in the sample, not its arrival order.
 */
function dueSlots(symIndex, symbolCount, prevSlotPos, slotPos, stride, totalSlots) {
  const offset = symIndex / symbolCount;
  const from = Math.floor(prevSlotPos - offset);
  const to = Math.floor(slotPos - offset);
  const out = [];
  for (let n = from + 1; n <= to; n++) {
    if (n >= 0 && n < totalSlots && n % stride === 0) {
      out.push(n);
    }
  }
  return out;
}

// ----- the impure half: load, enable, submit ---------------------------------------------------

/** Load and validate the sample against the ALREADY-LOADED median extract. Sync and called once:
 *  the file is a local Secret mount, not a network read. All-or-nothing, for the same reason
 *  taq-replay.js is: half a replay is a provenance mess nobody can reason about afterwards. */
function load(taqReplay) {
  state.attempted = true;
  if (!ENABLED) {
    return fail('PRINT_REPLAY=0');
  }
  if (!ACCOUNTS.length || ACCOUNTS.some((a) => a < REPLAY_ACCOUNT_BASE)) {
    return fail(`PRINT_REPLAY_ACCOUNTS must all be >= ${REPLAY_ACCOUNT_BASE} (got `
      + `${ACCOUNTS.join(',') || '<empty>'}) — below that the members do not attribute this flow `
      + 'as external, and every proof that brackets its own orders starts counting ours');
  }
  if (!Number.isFinite(QTY) || QTY <= 0) {
    return fail(`PRINT_REPLAY_QTY ${process.env.PRINT_REPLAY_QTY} is not a positive quantity`);
  }
  const extract = taqReplay.state.extract;
  if (!extract) {
    // Not this module's failure, but it IS this module's problem: replaying prints against a
    // synthetic-walk collar reference is the one combination that must never run.
    return fail('the median extract is not loaded, so there is no tape reference to replay '
      + `against (taq-replay says: ${taqReplay.state.error || 'no reason recorded'})`);
  }
  if (!fs.existsSync(SAMPLE_PATH)) {
    return fail(`no print sample at ${SAMPLE_PATH}`);
  }
  let sample;
  try {
    sample = decode(zlib.gunzipSync(fs.readFileSync(SAMPLE_PATH)));
  } catch (err) {
    return fail(`${SAMPLE_PATH} did not gunzip+decode: ${String((err && err.message) || err)}`);
  }
  // The two artifacts are one clock and one universe or they are nothing. A window or session
  // mismatch puts the order flow at a different tape instant from the reference; a symbol the
  // extract does not carry replays real prices against an invented anchor. Both are refusals.
  if (sample.windowSeconds !== extract.windowSeconds
      || sample.sessionSeconds !== extract.sessionSeconds
      || sample.days.length !== extract.days.length) {
    return fail(`the print sample (window ${sample.windowSeconds}s, session `
      + `${sample.sessionSeconds}s, ${sample.days.length} days) is not cut on the same clock as `
      + `the median extract (${extract.windowSeconds}s, ${extract.sessionSeconds}s, `
      + `${extract.days.length} days) — rebuild both`);
  }
  const mismatched = sample.days.filter((d, i) => d !== extract.days[i].date);
  if (mismatched.length) {
    return fail(`the print sample's calendar diverges from the extract's at ${mismatched[0]}`);
  }
  const missing = sample.symbols.filter((s) => !extract.prices[s]);
  if (missing.length) {
    return fail(`the print sample carries ${missing.length} symbol(s) the median extract does `
      + `not (${missing.slice(0, 5).join(',')}) — a replayed order needs a replayed reference`);
  }
  state.sample = sample;
  state.error = null;
  return sample;
}

/** Narrow the sample to the symbols this publisher actually holds a tape reference for. Called
 *  after bootstrapPrices, because that is when the ticker universe exists. */
function selectUniverse(taqReplay, tickers, nowMs) {
  if (!state.sample) {
    return;
  }
  const priced = new Set(tickers.filter((t) => taqReplay.priceAt(t, nowMs)));
  state.replayed = state.sample.symbols.filter((s) => priced.has(s));
  state.unpriced = state.sample.symbols.filter((s) => !priced.has(s));
  if (!state.replayed.length) {
    fail('none of the sampled symbols are in this publisher\'s tape-priced universe '
      + `(PRICE_TICKERS carries none of ${state.sample.symbols.slice(0, 5).join(',')}…)`);
  }
}

async function gatewayPost(path, body, headers) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const res = await fetch(`${GATEWAY}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(headers || {}) },
      body: JSON.stringify(body),
      signal: controller.signal
    });
    let json = null;
    try { json = await res.json(); } catch (err) { json = null; }
    return { status: res.status, body: json };
  } finally {
    clearTimeout(timer);
  }
}

/** Enable the replay accounts and the instruments this flow trades, through the sequenced control
 *  path. Idempotent and cheap (a handful of commands at startup); it exists so the replay does not
 *  depend on the proof-rig seeder having run, and so a fresh epoch replays without a human. None
 *  of these are order-shaped, so none of them move any counter a proof brackets its work with. */
async function enable() {
  const headers = { 'X-Risk-Control-Token': RISK_TOKEN, 'X-Risk-Operator': 'print-replay' };
  const problems = [];
  for (const accountId of ACCOUNTS) {
    const r = await gatewayPost('/risk/control/account', { accountId, enabled: true }, headers)
      .catch((err) => ({ status: 0, body: { error: String((err && err.message) || err) } }));
    if (r.status !== 200) {
      problems.push(`account ${accountId}: ${r.status} ${JSON.stringify(r.body)}`);
    }
  }
  for (const ticker of state.replayed) {
    const r = await gatewayPost('/risk/control/security', { ticker, enabled: true }, headers)
      .catch((err) => ({ status: 0, body: { error: String((err && err.message) || err) } }));
    if (r.status !== 200) {
      problems.push(`security ${ticker}: ${r.status} ${JSON.stringify(r.body)}`);
    }
  }
  if (problems.length) {
    // NOT fatal: the fixture seeder enables the same things, so a rig that has been seeded replays
    // fine without this. It IS loud, because "no orders are being accepted" and "no orders are
    // being sent" look identical from outside and this is the reading that separates them.
    console.warn(`[print-replay] ${problems.length} control(s) refused: ${problems[0]}`);
  }
  return problems;
}

async function submit(order) {
  state.submitted++;
  state.lastOrder = order;
  let r;
  try {
    r = await gatewayPost('/orders', order);
  } catch (err) {
    state.failed++;
    return;
  }
  if (r.status === 200) {
    state.accepted++;
    return;
  }
  state.rejected++;
  // The collar refusing a replayed print is ADR-072's stated DEMONSTRATION, not a defect, so the
  // reasons are counted by name rather than lumped into one number — PRICE_COLLAR appearing here
  // is the band doing its job against a real print that moved too far from the tape median.
  const reason = (r.body && (r.body.reason || r.body.error)) || `HTTP ${r.status}`;
  state.byReason[reason] = (state.byReason[reason] || 0) + 1;
}

/** One tick of the loop: submit whatever the clock says is due. Exported for the tests, which
 *  drive it with a fake clock rather than waiting on wall time. */
async function step(taqReplay, nowMs) {
  if (!state.sample || !state.replayed.length) {
    return [];
  }
  const pos = taqReplay.positionAt(nowMs);
  if (!pos) {
    return [];
  }
  const slotSeconds = state.sample.windowSeconds / state.sample.slots;
  const slotPos = pos.tapeSeconds / slotSeconds;
  const prev = state.slotPos === null ? slotPos : state.slotPos;
  state.slotPos = slotPos;
  const orders = [];
  for (const ticker of state.replayed) {
    const symIndex = state.sample.symbols.indexOf(ticker);
    const due = dueSlots(symIndex, state.sample.symbols.length, prev, slotPos, STRIDE,
      state.sample.totalSlots);
    if (due.length > MAX_CATCHUP) {
      state.skipped += due.length - MAX_CATCHUP;
      due.splice(0, due.length - MAX_CATCHUP);
    }
    for (const slot of due) {
      const order = orderAt(state.sample, symIndex, slot, STRIDE, ACCOUNTS, QTY);
      if (order) {
        orders.push(order);
      }
    }
  }
  await Promise.all(orders.map(submit));
  return orders;
}

function start(taqReplay, tickers, now) {
  load(taqReplay);
  selectUniverse(taqReplay, tickers, now());
  if (!state.sample) {
    return;
  }
  const rate = state.replayed.length * state.sample.slots
    / (state.sample.windowSeconds / Number(taqReplay.state.extract.compression) * STRIDE);
  console.log(`[print-replay] replaying ${state.replayed.length} of ${state.sample.symbols.length} `
    + `sampled symbols at ~${rate.toFixed(1)} order/s, accounts ${ACCOUNTS.join(',')}, qty ${QTY}`);
  enable().then(() => {
    setInterval(() => { step(taqReplay, now()).catch((err) => console.warn(`[print-replay] ${err}`)); },
      POLL_MS).unref();
  });
}

/** The /health block. Same posture as taq-replay's: `error` is a sentence, never a boolean, and it
 *  is null ONLY while orders are actually being replayed. */
function status(taqReplay) {
  const base = {
    attempted: state.attempted,
    enabled: state.enabled,
    samplePath: state.samplePath,
    accountBase: REPLAY_ACCOUNT_BASE,
    accounts: ACCOUNTS,
    error: state.error
  };
  if (!state.sample) {
    return base;
  }
  const compression = Number(taqReplay.state.extract && taqReplay.state.extract.compression) || 0;
  return {
    ...base,
    // ADR-072: the side is an approximation and this is where it says so.
    sideRule: SIDE_RULE,
    quantity: QTY,
    quantityNote: 'FIXED, not the print\'s TAQ size — 54% of prints are odd lots and a real size '
      + 'would need clamping against the order cap, so it would not be real either (ADR-072)',
    slotsPerWindow: state.sample.slots,
    stride: STRIDE,
    symbols: state.replayed.length,
    sampledSymbols: state.sample.symbols.length,
    // Sampled symbols this publisher holds no tape price for. Non-empty means the artifacts and
    // PRICE_TICKERS have drifted; the rate below is what it costs.
    unpricedSymbols: state.unpriced,
    ordersPerSecond: compression
      ? Number((state.replayed.length * state.sample.slots
          / (state.sample.windowSeconds / compression * STRIDE)).toFixed(2))
      : null,
    submitted: state.submitted,
    accepted: state.accepted,
    rejected: state.rejected,
    rejectedByReason: state.byReason,
    skipped: state.skipped,
    failed: state.failed,
    slotPos: state.slotPos === null ? null : Math.floor(state.slotPos),
    lastOrder: state.lastOrder
  };
}

module.exports = { decode, ticksAt, sideAt, orderAt, dueSlots, load, selectUniverse, step, start,
  status, state, SIDE_RULE, REPLAY_ACCOUNT_BASE, TICK_LOOKBACK };
