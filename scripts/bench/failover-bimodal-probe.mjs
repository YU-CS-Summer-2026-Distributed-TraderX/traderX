#!/usr/bin/env node
// failover-bimodal-probe.mjs — separate "the gateway lost its cluster session" from
// "the cluster is not serving" during a leader kill.
//
// Two independent probes at the same cadence:
//   /orders  — full path: needs a live gateway session AND a serving leader
//   /ready   — gateway-local: 200 only while the gateway's cluster session is connected,
//              and it never touches the leader, so it isolates the gateway side
//
// If /ready keeps returning 200 through an /orders outage, the gateway kept its session and the
// delay is cluster-side (election/catch-up). If /ready goes 503/fails, the gateway itself had to
// re-establish — which is the suspected slow path when connectCycling() retries the DEAD member
// first (its ingress endpoint list is ordered 0,1,2 and it starts at index 0).

import http from 'node:http';

const cfg = {
  host: process.env.GW_HOST || 'order-matcher-gw',
  port: Number(process.env.GW_PORT || 18110),
  secs: Number(process.env.SECS || 40),
  everyMs: Number(process.env.EVERY_MS || 20),
  acct: Number(process.env.ACCOUNT || 42422),
};
const agent = new http.Agent({ keepAlive: true, maxSockets: 64 });
const orders = [], ready = [];
let seq = 0;

function hitOrders() {
  const sell = (seq++ & 1) === 1;
  const t = Date.now();
  const b = Buffer.from(JSON.stringify({ accountId: cfg.acct, ticker: 'AAPL',
    side: sell ? 'Sell' : 'Buy', quantity: 100, limitPrice: 150.0 }));
  const r = http.request({ host: cfg.host, port: cfg.port, path: '/orders', method: 'POST', agent,
    headers: { 'Content-Type': 'application/json', 'Content-Length': b.length }, timeout: 10000 },
    (res) => { res.resume(); res.on('end', () => orders.push({ t, ok: res.statusCode === 200 })); });
  r.on('error', () => orders.push({ t, ok: false }));
  r.on('timeout', () => r.destroy());
  r.write(b); r.end();
}

function hitReady() {
  const t = Date.now();
  const r = http.get({ host: cfg.host, port: cfg.port, path: '/ready', agent, timeout: 10000 },
    (res) => { res.resume(); res.on('end', () => ready.push({ t, ok: res.statusCode === 200 })); });
  r.on('error', () => ready.push({ t, ok: false }));
  r.on('timeout', () => r.destroy());
}

function gap(arr) {
  arr.sort((a, b) => a.t - b.t);
  const bad = arr.filter((x) => !x.ok), good = arr.filter((x) => x.ok);
  if (!bad.length) return { failed: 0, gapMs: 0, total: arr.length };
  const before = good.filter((x) => x.t < bad[0].t).pop();
  const after = good.find((x) => x.t > bad[bad.length - 1].t);
  return { failed: bad.length, total: arr.length,
           gapMs: before && after ? after.t - before.t : -1,
           firstFail: bad[0].t, lastFail: bad[bad.length - 1].t };
}

const t1 = setInterval(hitOrders, cfg.everyMs);
const t2 = setInterval(hitReady, cfg.everyMs);
setTimeout(() => {
  clearInterval(t1); clearInterval(t2);
  setTimeout(() => {
    const o = gap(orders), r = gap(ready);
    console.log(`BIMODAL orders failed=${o.failed}/${o.total} gapMs=${o.gapMs}`);
    console.log(`BIMODAL ready  failed=${r.failed}/${r.total} gapMs=${r.gapMs}`);
    console.log(`BIMODAL verdict=${r.failed > 0 ? 'GATEWAY-SESSION-LOST (reconnect path)' : 'gateway session HELD -> delay is cluster-side'}`);
    if (o.failed && r.failed) console.log(`BIMODAL readyFailWindow=${r.lastFail - r.firstFail}ms ordersFailWindow=${o.lastFail - o.firstFail}ms`);
  }, 12000);
}, cfg.secs * 1000);
