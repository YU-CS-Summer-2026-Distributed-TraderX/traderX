#!/usr/bin/env node
// failover-client-probe.mjs — client-facing failover impact, measured the way the YU12
// failover-measurement issue prescribes:
//   * one order every ~200ms, each timestamped
//   * report FAILED REQUEST COUNT and the gap between last-success-before and first-success-after
//   * NOT "first-fail to last-fail", which overstates the outage (scattered failures inside a
//     window are not continuous downtime)
//
// Self-crossing single account so positions stay flat and we measure the booking path, not
// POSITION_LIMIT rejections.
//
// Usage: SECS=120 node failover-client-probe.mjs   (kill the leader partway through)

import http from 'node:http';

const cfg = {
  url: (process.env.MATCHER_URL || 'http://order-matcher-gw:18110').replace(/\/$/, ''),
  secs: Number(process.env.SECS || 120),
  everyMs: Number(process.env.EVERY_MS || 200),
  acct: Number(process.env.ACCOUNT || 42422),
  ticker: process.env.TICKER || 'AAPL',
  px: Number(process.env.PX || 150.0),
};
const u = new URL(cfg.url + '/orders');
const agent = new http.Agent({ keepAlive: true, maxSockets: 32 });

const results = []; // {t, ok, ms}
let seq = 0;

function one() {
  const sell = (seq++ & 1) === 1;
  const t = Date.now();
  const started = process.hrtime.bigint();
  const body = Buffer.from(JSON.stringify({
    accountId: cfg.acct, ticker: cfg.ticker, side: sell ? 'Sell' : 'Buy',
    quantity: 100, limitPrice: cfg.px,
  }));
  const done = (ok) => results.push({ t, ok, ms: Number(process.hrtime.bigint() - started) / 1e6 });
  const req = http.request({
    hostname: u.hostname, port: u.port, path: u.pathname, method: 'POST', agent,
    headers: { 'Content-Type': 'application/json', 'Content-Length': body.length },
    timeout: 15000,
  }, (res) => { res.resume(); res.on('end', () => done(res.statusCode === 200)); });
  req.on('error', () => done(false));
  req.on('timeout', () => { req.destroy(); });
  req.write(body); req.end();
}

const timer = setInterval(one, cfg.everyMs);
setTimeout(() => {
  clearInterval(timer);
  setTimeout(() => {
    results.sort((a, b) => a.t - b.t);
    const failed = results.filter((r) => !r.ok);
    const okr = results.filter((r) => r.ok);
    let gap = null, lastBefore = null, firstAfter = null;
    if (failed.length) {
      const f0 = failed[0].t;
      lastBefore = okr.filter((r) => r.t < f0).pop();
      firstAfter = okr.find((r) => r.t > failed[failed.length - 1].t);
      if (lastBefore && firstAfter) gap = firstAfter.t - lastBefore.t;
    }
    console.log(`PROBE total=${results.length} ok=${okr.length} failed=${failed.length} successRate=${((okr.length / results.length) * 100).toFixed(2)}%`);
    if (gap !== null) {
      console.log(`PROBE lastSuccessBefore=${lastBefore.t} firstSuccessAfter=${firstAfter.t} clientGapMs=${gap}`);
      console.log(`PROBE failedSpanMs=${failed[failed.length - 1].t - failed[0].t} (span, NOT continuous downtime)`);
    } else if (!failed.length) {
      console.log('PROBE no failures observed');
    }
  }, 16000);
}, cfg.secs * 1000);
