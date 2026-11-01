// The real initializer end to end against a faked rig (FR-PRG-30, FR-ERH-F15). Its last line referenced a variable
// 790c245d removed, so every --apply threw after all its checks had passed and `rig.sh up` refused (found 2026-11-01).
import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const json = (body) => new Response(JSON.stringify(body), { status: 200 });
let applied = 10;
function fake(url, init = {}) {
  const u = new URL(url), p = u.pathname, m = /order-matcher-cluster-(\d)/.exec(u.host)?.[1];
  if (p === '/instruments') return json([{ instrumentKey: 'AMZN', matured: false }, { instrumentKey: 'UST-OLD', matured: true }]);
  if (p === '/instruments/control-snapshot') return json({ count: 2, records: [{ ticker: 'AMZN' }, { ticker: 'UST-OLD' }] });
  if (p === '/prices') return json({ prices: [{ ticker: 'AMZN', price: 200, source: 'fake', asOf: 't' }] });
  if (p === '/health') return json({ started: true, phase: 'OPEN', queueDepth: 0, applied });
  if (p === '/bbo') return json({ member: Number(m), applied, books: [{ ticker: 'AMZN', mark: 200 }] });
  if (p === '/risk/control/security') return json({ applied: true });
  if (p === '/session') { applied = 20; return json({ sequence: 20 }); }
  if (p === '/trade-processor/auth/dev-token') return new Response('tok', { status: 200 });
  if (p === '/risk/control/securities') return json({ memberId: Number(m), available: true,
    securities: [{ ticker: 'AMZN', enabled: true }, { ticker: 'UST-OLD', enabled: false }] });
  throw new Error(`unexpected ${init.method ?? 'GET'} ${url}`);
}

test('initialize-reference-data --apply completes and reports verified', async () => {
  const lines = [], log = console.log;
  globalThis.fetch = async (url, init) => fake(url, init);
  process.env.RISK_CONTROL_TOKEN = 'x'; process.env.AUTH_MASTER_SECRET = 'y';
  process.argv.push('--apply');
  console.log = (l) => lines.push(l);
  try { await import(fileURLToPath(new URL('./initialize-reference-data.mjs', import.meta.url))); }
  finally { console.log = log; }
  assert.equal(JSON.parse(lines.at(-1)).phase, 'verified');
});
