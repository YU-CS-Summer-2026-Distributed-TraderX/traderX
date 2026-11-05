// FR-FTL-09: a local web page on your Mac that sends FIX orders straight to the external FIX-over-TLS entry (ADR-095),
// one QuickFIX/J session per order through fix-order.sh — no port-forward. Listens on 127.0.0.1 only; the session's
// password and pinned trust store stay in PRIVATE_DIR (default ~/traderx-private), never in the page.
//   node scripts/fix-tls/fix-web.mjs            then open http://localhost:7070
//   FIX_HOST (default 35.196.31.22) FIX_PORT (18143) PORT (7070)
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const SCRIPT = fileURLToPath(new URL('./fix-order.sh', import.meta.url));
const PORT = Number(process.env.PORT ?? 7070);
const FIX_HOST = process.env.FIX_HOST ?? '35.196.31.22';
// "market" = a Limit DAY at the engine's mark, as the Desk's "Price at market" does; a bare Market IOC cancels on an empty book.
const MARK_URL = process.env.MARK_URL ?? 'https://demo.yaakovseif.dev/mem/0/bbo';

export function markFor(bbo, ticker) {
  const m = bbo?.books?.find((b) => b.ticker === ticker)?.mark;
  return typeof m === 'number' && m > 0 ? String(Number(m.toFixed(6))) : null;
}

export function orderArgs(b) {
  const ok = b && /^\d{1,9}$/.test(String(b.account)) && ['Buy', 'Sell'].includes(b.side) && /^[1-9]\d{0,8}$/.test(String(b.qty))
    && /^[A-Z][A-Z0-9.\-]{0,31}$/.test(String(b.ticker ?? '')) && (b.px === undefined || b.px === '' || /^\d+(\.\d{1,6})?$/.test(String(b.px)));
  return ok ? [String(b.account), b.side, String(b.qty), String(b.ticker), ...(b.px ? [String(b.px)] : [])] : null;
}

let busy = false;   // one session at a time: each order logs on, sends, reads its reports and logs out
function send(args) {
  return new Promise((resolve) => {
    const p = spawn('bash', [SCRIPT, ...args], { env: { ...process.env, FIX_HOST } });
    let out = '';
    p.stdout.on('data', (d) => { out += d; }); p.stderr.on('data', (d) => { out += d; });
    p.on('close', (code) => resolve({ exit: code, lines: out.split('\n').filter((l) => /^\[(ok|sent|report|FAIL)\]|Logout/.test(l.trim())) }));
  });
}

const PAGE = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FIX from this Mac</title><style>
:root{--bg:#f4f5f7;--card:#fff;--border:#e2e5ea;--text:#1b2430;--muted:#64707f;--accent:#0b57d0;--good:#067647;--bad:#b42318}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.45 -apple-system,system-ui,sans-serif}
main{max-width:760px;margin:24px auto;padding:0 16px}h1{font-size:18px;margin:0 0 4px}.sub{color:var(--muted);margin:0 0 16px}
form,.log{background:var(--card);border:1px solid var(--border);border-radius:10px;padding:16px;margin-bottom:16px}
form{display:grid;grid-template-columns:repeat(auto-fit,minmax(110px,1fr));gap:10px;align-items:end}
label{display:grid;gap:4px;font-size:12px;color:var(--muted)}input,select{font:inherit;padding:7px 8px;border:1px solid var(--border);border-radius:6px}
button{font:inherit;font-weight:600;padding:8px 12px;border:0;border-radius:6px;background:var(--accent);color:#fff;cursor:pointer}button:disabled{opacity:.5}
.entry{border-top:1px solid var(--border);padding:10px 0}.entry:first-child{border-top:0}.head{font-weight:600}.ok{color:var(--good)}.bad{color:var(--bad)}
pre{margin:6px 0 0;white-space:pre-wrap;font:12px ui-monospace,Menlo,monospace;color:var(--muted)}</style></head><body><main>
<h1>FIX 4.4 over TLS from this Mac</h1><p class="sub">Each order opens its own session as EXTQFJ to ${FIX_HOST}:${process.env.FIX_PORT ?? 18143} (any account). Every order is a Limit DAY; no price = at the market price (the engine's mark).</p>
<form id="f"><label>Account<input name="account" inputmode="numeric" required placeholder="65000"></label>
<label>Side<select name="side"><option>Buy</option><option>Sell</option></select></label>
<label>Quantity<input name="qty" inputmode="numeric" required value="10"></label>
<label>Ticker<input name="ticker" required value="MSFT" autocapitalize="characters"></label>
<label>Limit price<input name="px" inputmode="decimal" placeholder="market"></label>
<button id="b">Send over FIX</button></form>
<section class="log" id="log"><p class="sub" style="margin:0">Orders you send appear here with every ExecutionReport.</p></section>
<script>
const f=document.getElementById('f'),b=document.getElementById('b'),log=document.getElementById('log');let first=true;
f.addEventListener('submit',async e=>{e.preventDefault();const d=Object.fromEntries(new FormData(f));d.ticker=d.ticker.toUpperCase();
 b.disabled=true;b.textContent='Sending…';
 try{const r=await fetch('/order',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)});const j=await r.json();
  if(first){log.textContent='';first=false;}
  const e2=document.createElement('div');e2.className='entry';const h=document.createElement('div');h.className='head '+(j.exit===0?'ok':'bad');
  h.textContent=new Date().toLocaleTimeString()+'  '+d.side+' '+d.qty+' '+d.ticker+(d.px?' @ '+d.px:' at market')+' · account '+d.account+(j.error?' — '+j.error:'');
  const p=document.createElement('pre');p.textContent=(j.lines||[]).join('\\n');e2.append(h,p);log.prepend(e2);}
 finally{b.disabled=false;b.textContent='Send over FIX';}});
</script></main></body></html>`;

const json = (res, code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  http.createServer(async (req, res) => {
    if (req.method === 'GET' && (req.url === '/' || req.url === '/index.html')) { res.writeHead(200, { 'Content-Type': 'text/html' }); return res.end(PAGE); }
    if (req.method === 'POST' && req.url === '/order') {
      // Same-origin only: a page on another site must not be able to make this Mac send orders.
      if (req.headers.origin && new URL(req.headers.origin).host !== req.headers.host) return json(res, 403, { error: 'cross-origin request refused' });
      let raw = ''; for await (const c of req) { raw += c; if (raw.length > 4096) return json(res, 413, { error: 'too large' }); }
      let body; try { body = JSON.parse(raw); } catch { return json(res, 400, { error: 'bad JSON' }); }
      const args = orderArgs(body);
      if (!args) return json(res, 400, { error: 'account, Buy|Sell, quantity, ticker and an optional price' });
      if (args.length === 4) {
        let px; try { px = markFor(await (await fetch(MARK_URL, { signal: AbortSignal.timeout(5000) })).json(), args[3]); } catch {}
        if (!px) return json(res, 502, { error: `no market price for ${args[3]}; enter a price` });
        args.push(px);
      }
      if (busy) return json(res, 409, { error: 'an order is still in flight' });
      busy = true;
      try { return json(res, 200, await send(args)); } finally { busy = false; }
    }
    json(res, 404, { error: 'not found' });
  }).listen(PORT, '127.0.0.1', () => console.log(`FIX from this Mac: http://localhost:${PORT}  (entry ${FIX_HOST})`));
}
