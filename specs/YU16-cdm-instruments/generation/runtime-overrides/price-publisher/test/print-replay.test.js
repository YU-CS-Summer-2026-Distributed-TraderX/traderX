// ADR-072: the replayed-print order flow, tested where it can actually be decided.
//
// The claims this file exists to hold, in the ADR's own terms:
//
//   * DETERMINISM. "Two runs from the same epoch should produce the same orders." That is a claim
//     about a pure function of tape position, and it is checked here as one — on a live rig it can
//     only be observed, never falsified, because a rig runs the clock once.
//   * THE TICK RULE. Uptick buy, downtick sell, zero-tick carries. Invented, and therefore worth
//     more scrutiny than a real field, not less.
//   * THE PAIR IS ONE CLOCK. A sample cut on a different window/session/calendar than the median
//     extract must be REFUSED, not replayed — the failure it prevents is order flow at one tape
//     instant against a reference at another, which nothing downstream could diagnose.
//   * THE ACCOUNT RANGE IS LOAD-BEARING. Below REPLAY_ACCOUNT_BASE the members do not attribute
//     this flow as external and every proof that brackets its own orders starts counting ours.
const test = require('node:test');
const assert = require('node:assert');
const zlib = require('zlib');
const fs = require('fs');
const os = require('os');
const path = require('path');

const replay = require('../src/print-replay');

const SESSION = 23400;
const WINDOW = 195;

/** Encode a sample the way scripts/yu17/build-taq-print-sample.py does, so the decoder is tested
 *  against the layout rather than against itself. */
function encode({ slots, window = WINDOW, session = SESSION, days, symbols, scale = 1000, plane }) {
  const windowsPerDay = session / window;
  const perSymbol = days.length * windowsPerDay * slots;
  const head = [];
  const magic = Buffer.from('TAQP1', 'latin1');
  const fixed = Buffer.alloc(16);
  fixed.writeUInt16LE(slots, 0);
  fixed.writeUInt16LE(window, 2);
  fixed.writeUInt32LE(session, 4);
  fixed.writeUInt16LE(days.length, 8);
  fixed.writeUInt16LE(symbols.length, 10);
  fixed.writeUInt32LE(scale, 12);
  head.push(magic, fixed);
  for (const d of days) head.push(Buffer.from(d, 'latin1'));
  for (const s of symbols) {
    head.push(Buffer.from([s.length]), Buffer.from(s, 'latin1'));
  }
  const prices = Buffer.alloc(symbols.length * perSymbol * 4);
  for (let i = 0; i < symbols.length * perSymbol; i++) {
    prices.writeInt32LE(plane[i] === undefined ? 0 : plane[i], i * 4);
  }
  head.push(prices);
  return Buffer.concat(head);
}

const DAYS = ['2025-02-03', '2025-02-04'];
const SYMS = ['AAA', 'BBB'];
const SLOTS = 4;
const PER_SYMBOL = DAYS.length * (SESSION / WINDOW) * SLOTS;

function flatSample(fill) {
  const plane = new Array(SYMS.length * PER_SYMBOL).fill(0).map((_, i) => fill(i));
  return encode({ slots: SLOTS, days: DAYS, symbols: SYMS, plane });
}

function fakeTaqReplay(tapeSeconds, overrides = {}) {
  return {
    state: {
      extract: {
        windowSeconds: WINDOW,
        sessionSeconds: SESSION,
        compression: 13,
        days: DAYS.map((date) => ({ date, openMs: 1 })),
        prices: Object.fromEntries(SYMS.map((s) => [s, []])),
        ...overrides
      },
      error: null
    },
    positionAt: () => ({ tapeSeconds: tapeSeconds() }),
    priceAt: (t) => (SYMS.includes(t) ? { price: 1 } : null)
  };
}

function reset() {
  Object.assign(replay.state, {
    attempted: false, sample: null, replayed: [], unpriced: [], slotPos: null,
    submitted: 0, accepted: 0, rejected: 0, skipped: 0, failed: 0, byReason: {},
    lastOrder: null, error: null
  });
}

// ----- decode -----------------------------------------------------------------------------------

test('decode reads the layout the builder writes', () => {
  const s = replay.decode(flatSample((i) => 100000 + i));
  assert.strictEqual(s.slots, SLOTS);
  assert.deepStrictEqual(s.days, DAYS);
  assert.deepStrictEqual(s.symbols, SYMS);
  assert.strictEqual(s.totalSlots, PER_SYMBOL);
  // Symbol 1's plane starts after symbol 0's, which is what makes ticksAt's indexing right.
  assert.strictEqual(replay.ticksAt(s, 0, 0), 100000);
  assert.strictEqual(replay.ticksAt(s, 1, 0), 100000 + PER_SYMBOL);
});

test('decode refuses a truncated price plane rather than replaying a short one', () => {
  const buf = flatSample(() => 1000);
  assert.throws(() => replay.decode(buf.subarray(0, buf.length - 8)), /price plane is/);
});

test('decode refuses a file that is not a print sample', () => {
  assert.throws(() => replay.decode(Buffer.from('not a sample at all, really')), /not a TAQP1/);
});

// ----- the tick rule ----------------------------------------------------------------------------

test('the tick rule: uptick Buy, downtick Sell, zero-tick carries the last non-zero tick', () => {
  //          slot: 0     1     2     3     4     5
  const px = [100000, 100500, 100500, 100500, 99000, 99000];
  const s = replay.decode(flatSample((i) => (i < px.length ? px[i] : 100000)));
  assert.strictEqual(replay.sideAt(s, 0, 1, 1), 'Buy');   // 100.500 > 100.000
  assert.strictEqual(replay.sideAt(s, 0, 2, 1), 'Buy');   // flat: carries the uptick
  assert.strictEqual(replay.sideAt(s, 0, 3, 1), 'Buy');   // still flat, still carrying
  assert.strictEqual(replay.sideAt(s, 0, 4, 1), 'Sell');  // 99.000 < 100.500
  assert.strictEqual(replay.sideAt(s, 0, 5, 1), 'Sell');  // flat: carries the downtick
});

test('the tick rule defaults Buy at the first print, deterministically', () => {
  const s = replay.decode(flatSample(() => 100000));
  assert.strictEqual(replay.sideAt(s, 0, 0, 1), 'Buy');
  // A dead-flat run longer than the lookback also defaults rather than scanning the whole tape.
  assert.strictEqual(replay.sideAt(s, 0, replay.TICK_LOOKBACK + 5, 1), 'Buy');
});

test('the tick rule follows the STRIDED series, not the prints nobody replayed', () => {
  // Slot 2 is above slot 1 but BELOW slot 0. At stride 1 that is an uptick; at stride 2 slot 1 is
  // never sent, so the comparison is against slot 0 and the same print is a downtick.
  const px = [100500, 99000, 100000];
  const s = replay.decode(flatSample((i) => (i < px.length ? px[i] : 100000)));
  assert.strictEqual(replay.sideAt(s, 0, 2, 1), 'Buy');
  assert.strictEqual(replay.sideAt(s, 0, 2, 2), 'Sell');
});

test('a slot with no print yields no order, and the tick rule steps over it', () => {
  const px = [100000, 0, 100500];
  const s = replay.decode(flatSample((i) => (i < px.length ? px[i] : 100000)));
  assert.strictEqual(replay.orderAt(s, 0, 1, 1, [900001], 10), null);
  assert.strictEqual(replay.sideAt(s, 0, 2, 1), 'Buy');   // compared against slot 0, not the hole
});

// ----- the order ---------------------------------------------------------------------------------

test('orderAt is a pure function of tape position — the ADR-072 determinism claim', () => {
  const s = replay.decode(flatSample((i) => 100000 + (i % 7) * 10));
  const accounts = [900001, 900002, 900003];
  for (const slot of [0, 1, 17, 240, PER_SYMBOL - 1]) {
    const a = replay.orderAt(s, 0, slot, 1, accounts, 10);
    const b = replay.orderAt(s, 0, slot, 1, accounts, 10);
    assert.deepStrictEqual(a, b);
  }
  const o = replay.orderAt(s, 1, 5, 1, accounts, 10);
  assert.strictEqual(o.clientOrderId, 'taq-BBB-5');
  assert.strictEqual(o.ticker, 'BBB');
  assert.strictEqual(o.quantity, 10);
  assert.ok(accounts.includes(o.accountId));
});

test('consecutive slots of one symbol land on DIFFERENT accounts', () => {
  // Not cosmetic: the crossing pair this flow exists to create is a resting order hit by the next
  // slot's opposite side. Same account on both and ADR-057 self-trade prevention cancels the
  // resting order instead — a replay that books no trades and looks like it is working.
  const s = replay.decode(flatSample((i) => 100000 + (i % 5) * 10));
  const accounts = [900001, 900002, 900003];
  for (let slot = 0; slot < 12; slot++) {
    const a = replay.orderAt(s, 0, slot, 1, accounts, 10);
    const b = replay.orderAt(s, 0, slot + 1, 1, accounts, 10);
    assert.notStrictEqual(a.accountId, b.accountId, `slots ${slot}/${slot + 1} share an account`);
  }
});

test('the limit price comes back off the venue grid the sample was rounded to', () => {
  const s = replay.decode(flatSample(() => 229812));
  assert.strictEqual(replay.orderAt(s, 0, 0, 1, [900001], 10).limitPrice, 229.812);
});

// ----- scheduling ---------------------------------------------------------------------------------

test('dueSlots emits every slot exactly once across consecutive polls', () => {
  const seen = [];
  let prev = 0;
  for (let t = 0; t <= 10; t += 0.25) {
    seen.push(...replay.dueSlots(0, 2, prev, t, 1, 1000));
    prev = t;
  }
  assert.deepStrictEqual(seen, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
});

test('symbols are staggered across a slot, so one print per window is not a burst', () => {
  // Two symbols, half a slot apart. Symbol 0 fires at integer positions, symbol 1 at the halves.
  assert.deepStrictEqual(replay.dueSlots(0, 2, 0.9, 1.1, 1, 100), [1]);
  assert.deepStrictEqual(replay.dueSlots(1, 2, 0.9, 1.1, 1, 100), []);
  assert.deepStrictEqual(replay.dueSlots(1, 2, 1.4, 1.6, 1, 100), [1]);
});

test('stride only ever removes slots', () => {
  assert.deepStrictEqual(replay.dueSlots(0, 1, -1, 6.5, 1, 100), [0, 1, 2, 3, 4, 5, 6]);
  assert.deepStrictEqual(replay.dueSlots(0, 1, -1, 6.5, 3, 100), [0, 3, 6]);
});

test('dueSlots stops at the end of the tape', () => {
  assert.deepStrictEqual(replay.dueSlots(0, 1, 7.5, 20, 1, 10), [8, 9]);
});

// ----- load refusals -------------------------------------------------------------------------------

function writeSample(buf) {
  const p = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'prints-')), 'prints.bin.gz');
  fs.writeFileSync(p, zlib.gzipSync(buf));
  process.env.PRINT_REPLAY_SAMPLE_PATH = p;
  return p;
}

test('load refuses a sample cut on a different clock from the median extract', () => {
  reset();
  writeSample(encode({ slots: SLOTS, window: 60, session: 23400, days: DAYS, symbols: SYMS,
    plane: [] }));
  delete require.cache[require.resolve('../src/print-replay')];
  const fresh = require('../src/print-replay');
  assert.strictEqual(fresh.load(fakeTaqReplay(() => 0)), null);
  assert.match(fresh.state.error, /not cut on the same clock/);
});

test('load refuses a sample whose calendar diverges from the extract', () => {
  const fresh = require('../src/print-replay');
  writeSample(encode({ slots: SLOTS, days: ['2025-02-03', '2025-09-09'], symbols: SYMS, plane: [] }));
  delete require.cache[require.resolve('../src/print-replay')];
  const m = require('../src/print-replay');
  assert.strictEqual(m.load(fakeTaqReplay(() => 0)), null);
  assert.match(m.state.error, /calendar diverges/);
  void fresh;
});

test('load refuses a symbol the median extract does not carry', () => {
  writeSample(encode({ slots: SLOTS, days: DAYS, symbols: ['AAA', 'ZZZ'], plane: [] }));
  delete require.cache[require.resolve('../src/print-replay')];
  const m = require('../src/print-replay');
  assert.strictEqual(m.load(fakeTaqReplay(() => 0)), null);
  assert.match(m.state.error, /does not/);
});

test('load refuses accounts below the range the members attribute as external', () => {
  writeSample(flatSample(() => 100000));
  process.env.PRINT_REPLAY_ACCOUNTS = '22214';
  delete require.cache[require.resolve('../src/print-replay')];
  const m = require('../src/print-replay');
  assert.strictEqual(m.load(fakeTaqReplay(() => 0)), null);
  assert.match(m.state.error, /must all be >= 900000/);
  delete process.env.PRINT_REPLAY_ACCOUNTS;
});

test('load refuses to replay prints when there is no tape reference to replay against', () => {
  writeSample(flatSample(() => 100000));
  delete require.cache[require.resolve('../src/print-replay')];
  const m = require('../src/print-replay');
  const noExtract = fakeTaqReplay(() => 0);
  noExtract.state.extract = null;
  noExtract.state.error = 'no extract at /etc/taq-replay/extract.json.gz';
  assert.strictEqual(m.load(noExtract), null);
  assert.match(m.state.error, /no tape reference/);
});

// ----- the whole loop, twice, from the same epoch ---------------------------------------------------

test('two runs from the same epoch produce byte-identical order flow', async () => {
  writeSample(flatSample((i) => 100000 + ((i * 37) % 23) * 10));
  const runs = [];
  for (const _ of [1, 2]) {
    delete require.cache[require.resolve('../src/print-replay')];
    const m = require('../src/print-replay');
    const sent = [];
    globalThis.fetch = async (url, init) => {
      sent.push(JSON.parse(init.body));
      return { status: 200, json: async () => ({ orderRef: sent.length, kind: 1 }) };
    };
    let tape = 0;
    const clock = fakeTaqReplay(() => tape);
    m.load(clock);
    m.selectUniverse(clock, [...SYMS, 'NOT-ON-TAPE'], 0);
    assert.deepStrictEqual(m.state.replayed, SYMS);
    // Poll the way the interval would, over a stretch of tape that spans several windows.
    for (let poll = 0; poll < 40; poll++) {
      tape = poll * 30;
      await m.step(clock, 0);
    }
    runs.push(sent);
  }
  assert.ok(runs[0].length > 20, `only ${runs[0].length} orders replayed — nothing was measured`);
  assert.deepStrictEqual(runs[0], runs[1]);
  // And it is real two-sided flow, not one side repeated: the tick rule has to produce both.
  const sides = new Set(runs[0].map((o) => o.side));
  assert.deepStrictEqual([...sides].sort(), ['Buy', 'Sell']);
  // Every order is attributable at the source, which is the whole ADR-072 remedy.
  assert.ok(runs[0].every((o) => o.accountId >= replay.REPLAY_ACCOUNT_BASE));
});

test('a stalled poll is capped rather than discharged as a burst', async () => {
  writeSample(flatSample((i) => 100000 + (i % 11) * 10));
  delete require.cache[require.resolve('../src/print-replay')];
  const m = require('../src/print-replay');
  const sent = [];
  globalThis.fetch = async (url, init) => {
    sent.push(JSON.parse(init.body));
    return { status: 200, json: async () => ({}) };
  };
  let tape = 0;
  const clock = fakeTaqReplay(() => tape);
  m.load(clock);
  m.selectUniverse(clock, SYMS, 0);
  await m.step(clock, 0);          // establishes the position; sends nothing
  tape = 20 * (WINDOW / SLOTS);    // twenty slots of tape in one poll
  await m.step(clock, 0);
  assert.ok(sent.length <= SYMS.length * 2, `${sent.length} orders in one poll — the cap did not hold`);
  assert.ok(m.state.skipped > 0, 'the skipped slots were not counted, so the gap is invisible');
});

test('UNKNOWN_ACCOUNT re-issues the controls once — a fresh epoch must not silence the replay', async () => {
  // The only routine way the accounts stop existing is a fresh-epoch mint, which wipes the PVCs
  // and with them the account-control commands this module sequenced at startup. Nothing here
  // FAILS when that happens — every order is simply refused — so without the self-heal the rig
  // comes back with a blotter that never moves and a /health block reporting no error at all.
  writeSample(flatSample(() => 100000));
  delete require.cache[require.resolve('../src/print-replay')];
  const m = require('../src/print-replay');
  const controls = [];
  globalThis.fetch = async (url, init) => {
    if (String(url).includes('/risk/control')) {
      controls.push(String(url));
      return { status: 200, json: async () => ({}) };
    }
    return { status: 422, json: async () => ({ reason: 'UNKNOWN_ACCOUNT' }) };
  };
  let tape = 0;
  const clock = fakeTaqReplay(() => tape);
  m.load(clock);
  m.selectUniverse(clock, SYMS, 0);
  await m.step(clock, 0);
  tape = WINDOW;
  await m.step(clock, 0);
  assert.ok(m.state.rejected > 0, 'nothing was rejected, so nothing could have healed');
  assert.strictEqual(m.state.reEnabled, 1, 'the controls must be re-issued exactly once, not once '
    + 'per refused order — a rejection storm re-enabling per order is its own outage');
  // The heal is deliberately NOT awaited by submit() — an order path that blocks on a control
  // round trip is a worse failure than the one being healed — so let it drain before reading it.
  for (let i = 0; i < 200 && !controls.some((u) => u.endsWith('/risk/control/security')); i++) {
    await new Promise((r) => setImmediate(r));
  }
  assert.ok(controls.some((u) => u.endsWith('/risk/control/account')));
  assert.ok(controls.some((u) => u.endsWith('/risk/control/security')),
    'the heal must re-issue the SECURITY controls too — a fresh epoch forgets those as well, and '
    + 'accounts alone leaves every order refused for a different reason');
  // ...and a second burst inside the window does not re-issue again.
  tape = WINDOW * 2;
  await m.step(clock, 0);
  assert.strictEqual(m.state.reEnabled, 1);
});

test('a collar rejection does NOT re-issue the controls', async () => {
  // PRICE_COLLAR is the demonstration, not a fault. Healing on it would hammer the sequenced
  // control path all day on a rig that is working exactly as ADR-072 says it should.
  writeSample(flatSample(() => 100000));
  delete require.cache[require.resolve('../src/print-replay')];
  const m = require('../src/print-replay');
  let controls = 0;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/risk/control')) {
      controls++;
      return { status: 200, json: async () => ({}) };
    }
    return { status: 422, json: async () => ({ reason: 'PRICE_COLLAR' }) };
  };
  let tape = 0;
  const clock = fakeTaqReplay(() => tape);
  m.load(clock);
  m.selectUniverse(clock, SYMS, 0);
  await m.step(clock, 0);
  tape = WINDOW;
  await m.step(clock, 0);
  assert.ok(m.state.rejected > 0);
  assert.strictEqual(m.state.reEnabled, 0);
  assert.strictEqual(controls, 0);
});

test('a rejected order is counted by REASON — the collar refusing a print is the demonstration', async () => {
  writeSample(flatSample(() => 100000));
  delete require.cache[require.resolve('../src/print-replay')];
  const m = require('../src/print-replay');
  globalThis.fetch = async () => ({ status: 422, json: async () => ({ reason: 'PRICE_COLLAR' }) });
  let tape = 0;
  const clock = fakeTaqReplay(() => tape);
  m.load(clock);
  m.selectUniverse(clock, SYMS, 0);
  await m.step(clock, 0);
  tape = WINDOW;
  await m.step(clock, 0);
  assert.ok(m.state.rejected > 0);
  assert.strictEqual(m.state.byReason.PRICE_COLLAR, m.state.rejected);
  assert.strictEqual(m.state.accepted, 0);
});
