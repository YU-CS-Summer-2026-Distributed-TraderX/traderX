// lib-cdp.mjs — headless Chrome over the DevTools protocol, no dependencies (the pattern of
// specs/YU18-risk-integration/components/managed-run-ui/proofs/browser-proof.mjs, shared by the
// browser-driven yu18-* proofs). Writes PNGs and log.json to OUT; check() exits non-zero on the
// first failed hard check, soft() records a SUSPECT and lets the run continue (exit 1 at done()).
//
//   import { browser } from './lib-cdp.mjs';
//   const b = await browser({ out: OUT, port: 26851 });
//   await b.go(URL); b.check(await b.js(`return document.title`) === 'x', 'title'); b.done();
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

export const sleep = ms => new Promise(r => setTimeout(r, ms));

export async function browser({ out, port = 26851, width = 1280, height = 1000,
  chrome = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' }) {
  fs.mkdirSync(out, { recursive: true });
  const log = [];
  let suspects = 0;
  const save = () => fs.writeFileSync(path.join(out, 'log.json'), JSON.stringify(log, null, 1));
  const proc = spawn(chrome, ['--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${out}/chrome-profile`,
    '--no-first-run', '--no-default-browser-check', `--window-size=${width},${height}`, 'about:blank'], { stdio: 'ignore' });
  const cleanups = [() => proc.kill()];
  process.on('exit', () => { for (const c of cleanups.reverse()) { try { c(); } catch { /* best effort */ } } });
  let targets;
  for (let i = 0; i < 60 && !targets; i++) { try { targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json(); } catch { await sleep(250); } }
  if (!targets) { console.error('FAIL chrome did not start'); process.exit(1); }
  const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
  await new Promise(r => ws.addEventListener('open', r));
  let id = 0; const waiting = new Map(); const events = [];
  ws.addEventListener('message', e => {
    const m = JSON.parse(e.data);
    if (m.id) { waiting.get(m.id)?.(m); waiting.delete(m.id); } else events.push(m);
  });
  const cdp = (method, params = {}) => new Promise(r => { const i = ++id; waiting.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });

  const b = {
    log, cdp, events,
    onExit: f => cleanups.push(f),
    fail(m) { log.push({ FAIL: m }); save(); console.error('[FAIL]', m); process.exit(1); },
    check(ok, m) { log.push({ check: m, ok: !!ok }); console.log(ok ? '[ok]  ' : '[FAIL]', m); if (!ok) b.fail(m); },
    soft(ok, m, evidence) {
      log.push({ check: m, ok: !!ok, soft: true, evidence });
      if (ok) console.log('[ok]  ', m); else { suspects++; console.log('[SUSPECT]', m, evidence ? JSON.stringify(evidence).slice(0, 400) : ''); }
      return ok;
    },
    note(m, v) { log.push({ note: m, v }); console.log('[note]', m, v === undefined ? '' : JSON.stringify(v).slice(0, 300)); },
    async js(expr) {
      const r = await cdp('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true });
      if (r.result?.exceptionDetails) b.fail('js: ' + (r.result.exceptionDetails.exception?.description ?? JSON.stringify(r.result.exceptionDetails)).slice(0, 600));
      return r.result?.result?.value;
    },
    async go(url, settle = 2500) { await cdp('Page.navigate', { url }); await sleep(settle); },
    async viewport(w, h, mobile = false) { await cdp('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile }); },
    async shot(name, selector) {
      const params = { format: 'png', captureBeyondViewport: true };
      if (selector) {
        const r = await b.js(`const e=document.querySelector(${JSON.stringify(selector)}); if(!e) return null; const r=e.getBoundingClientRect(); return {x:r.x,y:r.y+scrollY,w:r.width,h:r.height}`);
        if (r) params.clip = { x: r.x, y: r.y, width: r.w, height: Math.min(r.h, 1600), scale: 1 };
      }
      const r = await cdp('Page.captureScreenshot', params);
      if (r.result?.data) fs.writeFileSync(path.join(out, name + '.png'), Buffer.from(r.result.data, 'base64'));
      log.push({ screenshot: name + '.png' });
    },
    // until(fn, what, ms): poll an async predicate; returns its last truthy value or fails naming what it last saw.
    async until(fn, what, ms = 10000, { soft = false } = {}) {
      const end = Date.now() + ms; let v, seen;
      while (Date.now() < end) { [v, seen] = await fn(); if (v) return seen ?? v; await sleep(250); }
      if (soft) return null;
      b.fail(`timeout (${ms} ms): ${what}\n--- last seen ---\n${String(seen ?? v).slice(0, 1500)}`);
    },
    done() { save(); if (suspects) { console.log(`[SUSPECT] ${suspects} soft check(s) failed`); process.exit(1); } console.log('ALL CHECKS PASSED'); process.exit(0); },
  };
  await cdp('Page.enable'); await cdp('Runtime.enable'); await cdp('Network.enable');
  await b.viewport(width, height);
  return b;
}
