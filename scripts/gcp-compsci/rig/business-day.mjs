// FR-PRG-31: open the business date DATE (yyyy-mm-dd) on the engine, so DAY orders are admitted (FR-OT37/FR-OT08).
// Run inside the console pod by rig.sh up (its environment holds RISK_CONTROL_TOKEN). SESSION_START is sequenced
// and idempotent: APPLIED opens DATE, STALE means DATE (or a later one) is already the engine's date. Anything else exits 1.
const date = process.argv[2];
if (!/^\d{4}-\d{2}-\d{2}$/.test(date ?? '')) { console.log('usage: business-day.mjs yyyy-mm-dd'); process.exit(2); }
if (!process.env.RISK_CONTROL_TOKEN) { console.log('missing private control credential'); process.exit(2); }
const r = await fetch('http://order-matcher:18110/session/business-day', {method: 'POST',
  headers: {'Content-Type': 'application/json', 'X-Risk-Control-Token': process.env.RISK_CONTROL_TOKEN,
    'X-Risk-Operator': 'rig-business-day'},
  body: JSON.stringify({businessDate: date}), signal: AbortSignal.timeout(20000)});
const body = await r.text();
console.log(`BUSINESS-DAY ${r.status} ${body}`);
let outcome = '';
try { outcome = JSON.parse(body).outcome; } catch { /* reported above */ }
process.exit(r.status === 200 && (outcome === 'APPLIED' || outcome === 'STALE') ? 0 : 1);
