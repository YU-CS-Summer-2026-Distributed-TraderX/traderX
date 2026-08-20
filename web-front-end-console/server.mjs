// In-cluster server for the TraderX console.
//
// The dev setup is `ng serve` + proxy.conf.mjs, whose four bypasses shell out to `gcloud` and
// `kubectl` on the developer's laptop and open a port-forward for FIX. None of that survives being
// containerised as-is, and those four surfaces (GCS archive, kdb tap, EOD extract bridge, FIX) are
// most of the "this is a real system" half of the demo — so this file is their in-cluster twin.
//
// WHAT CHANGES IN-CLUSTER, and it is deliberately little:
//   * kubectl needs no --context and no kubeconfig; it uses the pod's ServiceAccount token.
//   * gcloud needs no `auth login`; Workload Identity answers from the metadata server.
//   * FIX needs no port-forward — the acceptor is a Service (order-matcher-gw-fix:18130).
// Everything else is the same shape as the dev bypasses on purpose: two implementations that drift
// are worse than one that is slightly awkward, and this pair is already the pair we have.
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const PORT = Number(process.env.PORT ?? 8080);
const NS = process.env.NAMESPACE ?? 'traderx';
const EDGE = process.env.EDGE_PROXY ?? 'edge-proxy:8080';
const FIX_HOST = process.env.FIX_HOST ?? 'order-matcher-gw-fix';
const FIX_PORT = Number(process.env.FIX_PORT ?? 18130);
const BUCKET = process.env.EXTRACT_BUCKET ?? 'gs://traderx-505400-risk-extracts';
const ROOT = path.resolve(process.env.STATIC_ROOT ?? './dist/web-front-end-console/browser');
// The dev proxy reads this off the rig with kubectl and injects it on /trade-processor so the EOD
// panel can mint its own admin token. In-cluster it arrives as an env var from the same Secret
// (auth-secrets/dev-token-master-secret) — no kubectl, no extra RBAC. Without it the panel falls
// back to "paste the master secret" and any guess returns "invalid master secret", which reads as
// a broken auth path rather than a header that was never sent.
const MASTER_SECRET = process.env.AUTH_MASTER_SECRET ?? '';
const SOH = '\x01';

// Paths the edge proxy already knows how to route. Identical list to proxy.conf.mjs's plain()
// entries — if one gains a route, so must the other.
const PROXY_PREFIXES = ['/order-matcher', '/reference-data', '/account-service', '/position-service',
  '/trade-processor', '/m0', '/m1', '/m2', '/nats-ws', '/algo', '/tempo', '/grafana'];

const json = (res, code, body) => {
  res.statusCode = code;
  res.setHeader('Content-Type', 'application/json');
  res.end(typeof body === 'string' ? body : JSON.stringify(body));
};

// ---- GCS archive (dev: gcloud on the laptop; here: Workload Identity) --------------------------
const gcsCache = new Map();
const gcs = (cmd) => execSync(`gcloud storage ${cmd}`, { shell: '/bin/sh', timeout: 30000 }).toString();

function gcsBypass(req, res, url) {
  try {
    if (url.pathname === '/gcs/extracts') {
      const hit = gcsCache.get('ls');
      if (!hit || Date.now() - hit.at > 60_000) {
        const files = gcs(`ls -r '${BUCKET}/**'`).split('\n').filter(l => l.endsWith('.cut') || l.endsWith('.csv'));
        gcsCache.set('ls', { at: Date.now(), body: JSON.stringify({ bucket: BUCKET, files }) });
      }
      return json(res, 200, gcsCache.get('ls').body);
    }
    if (url.pathname === '/gcs/read') {
      const p = url.searchParams.get('path') ?? '';
      // Same guard as the dev bypass: one bucket, no quote injection into the shell command.
      if (!p.startsWith(`${BUCKET}/`) || p.includes("'")) return json(res, 400, {});
      if (!gcsCache.has(p)) {
        const content = gcs(`cat '${p}'`);
        gcsCache.set(p, { body: JSON.stringify({ path: p, sha256: createHash('sha256').update(content).digest('hex'), content }) });
      }
      return json(res, 200, gcsCache.get(p).body);
    }
    return json(res, 404, {});
  } catch {
    return json(res, 502, { error: 'gcloud failed — is Workload Identity bound for this pod?' });
  }
}

// ---- kdb capture tap (leader-side tickerplant logs, no HTTP surface of their own) --------------
const kdbCache = { at: 0, body: '' };
function kdbBypass(req, res) {
  try {
    if (Date.now() - kdbCache.at > 10_000) {
      const members = [0, 1, 2].map(m => {
        try {
          const out = execSync(
            `kubectl -n ${NS} exec order-matcher-cluster-${m} -- sh -c ` +
            `'for f in /data/kdb-capture/*.csv; do echo "==FILE $f $(wc -l < $f)"; tail -n 300 $f; done 2>/dev/null'`,
            { shell: '/bin/sh', timeout: 20000 }).toString();
          return { member: m, capture: out };
        } catch { return { member: m, capture: '' }; }
      });
      kdbCache.at = Date.now();
      kdbCache.body = JSON.stringify({ members });
    }
    return json(res, 200, kdbCache.body);
  } catch {
    return json(res, 502, { error: 'kubectl exec failed' });
  }
}

// ---- EOD extract bridge: the rig's OWN cut sink, not the GCS archive --------------------------
// sha256 is taken ON THE POD so it is the same number the proofs and the three members report.
const extractCache = { at: 0, body: '' };
function extractBypass(req, res) {
  try {
    if (Date.now() - extractCache.at > 30_000) {
      const pod = execSync(`kubectl -n ${NS} get pods -l app=risk-extract -o jsonpath='{.items[0].metadata.name}'`,
        { shell: '/bin/sh', timeout: 20000 }).toString().trim();
      const out = execSync(
        `kubectl -n ${NS} exec ${pod} -- sh -c ` +
        `'for f in /data/risk-extracts/*/*/*; do [ -f "$f" ] || continue; ` +
        `echo "==FILE $f $(sha256sum $f | cut -d\\  -f1)"; cat $f; done'`,
        { shell: '/bin/sh', timeout: 30000 }).toString();
      const files = out.split('==FILE ').slice(1).map(chunk => {
        const nl = chunk.indexOf('\n');
        const [p, sha256] = chunk.slice(0, nl).trim().split(' ');
        return { path: p, sha256, content: chunk.slice(nl + 1) };
      });
      extractCache.at = Date.now();
      extractCache.body = JSON.stringify({ pod, files });
    }
    return json(res, 200, extractCache.body);
  } catch {
    return json(res, 502, { error: 'kubectl exec failed — is the risk-extract pod up?' });
  }
}

// ---- FIX 4.4 bridge: the gateway's second ingress ----------------------------------------------
const fixFrame = (fields) => {
  const body = fields.map(([t, v]) => `${t}=${v}`).join(SOH) + SOH;
  const head = `8=FIX.4.4${SOH}9=${Buffer.byteLength(body)}${SOH}`;
  const sum = [...Buffer.from(head + body)].reduce((a, b) => (a + b) & 0xff, 0);
  return head + body + `10=${String(sum).padStart(3, '0')}${SOH}`;
};
// YYYYMMDD-HH:MM:SS.sss — colons are STRUCTURAL. Stripping them with the dashes gets the Logon
// refused outright ("Incorrect data format for value, field=52"), which reads as a broken acceptor.
const fixTime = () => {
  const s = new Date().toISOString();
  return s.slice(0, 10).replace(/-/g, '') + '-' + s.slice(11, 23);
};

function fixOrder(o) {
  return new Promise((resolve) => {
    const sent = [], received = [];
    const sock = net.connect(FIX_PORT, FIX_HOST);
    let buf = '', seq = 1, done = false;
    const finish = (error) => {
      if (done) return;
      done = true; sock.destroy();
      resolve({ sent, received, ...(error ? { error } : {}) });
    };
    const timer = setTimeout(() => finish('no ExecutionReport within 8s'), 8000);
    const send = (f) => {
      const msg = fixFrame([['35', f.type], ['49', 'CLIENT1'], ['56', 'TRADERX'], ['34', seq++], ['52', fixTime()], ...f.body]);
      sent.push(msg); sock.write(msg);
    };
    sock.on('connect', () => send({ type: 'A', body: [['98', 0], ['108', 30], ['141', 'Y']] }));
    sock.on('data', (chunk) => {
      buf += chunk.toString('latin1');
      for (let end; (end = buf.indexOf(`${SOH}10=`)) >= 0;) {
        const cut = buf.indexOf(SOH, end + 1) + 1;
        if (cut <= 0) break;
        const msg = buf.slice(0, cut); buf = buf.slice(cut); received.push(msg);
        const type = /\x0135=([^\x01]+)/.exec(msg)?.[1];
        if (type === 'A') {
          send({ type: 'D', body: [['11', o.clOrdId], ['1', o.accountId], ['55', o.symbol],
            ['54', o.side === 'Sell' ? 2 : 1], ['38', o.quantity], ['40', 2], ['44', o.limitPrice],
            ['21', 1], ['60', fixTime()]] });
        } else if (type === '8' || type === '3' || type === 'j' || type === '5') {
          clearTimeout(timer); finish();
        }
      }
    });
    sock.on('error', (e) => { clearTimeout(timer); finish(`socket: ${e.message}`); });
  });
}

const readBody = (req) => new Promise((resolve) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => resolve(Buffer.concat(chunks).toString()));
});


// ---- gateways: discovered, not counted in advance ---------------------------------------------
// The gateway is a Deployment behind a Service, so there is no stable per-pod DNS the way the
// members (a StatefulSet + headless Service) have. Two things follow, and the second one is a bug
// that only appears once you run more than one gateway:
//
//   1. A panel cannot hardcode "the gateway". The replica count is a scaling decision — this rig
//      runs three — so the console has to ASK how many there are.
//   2. EVERY GATEWAY KEEPS ITS OWN COUNTERS. Polling /metrics through the Service round-robins
//      across pods, so a rate computed from consecutive samples is differencing two DIFFERENT
//      gateways' counters: acks/s swings positive and negative around zero and fills read
//      2 -> 0 -> 2 -> 0. Nothing is wrong with the cluster; the sampler is not sampling one thing.
//      aggregateGatewayMetrics() sums every pod, so a counter only ever goes up.
const podHttp = (ip, path, timeout = 6000) => new Promise((resolve) => {
  const req = http.request({ host: ip, port: 18110, path, method: 'GET', timeout }, (r) => {
    const chunks = [];
    r.on('data', (c) => chunks.push(c));
    r.on('end', () => resolve({ code: r.statusCode ?? 0, body: Buffer.concat(chunks).toString() }));
  });
  req.on('timeout', () => { req.destroy(); resolve({ code: 0, body: '' }); });
  req.on('error', () => resolve({ code: 0, body: '' }));
  req.end();
});

// Cached for 5s. The status panel probes every gateway CONCURRENTLY, so without this each probe
// shells its own kubectl and they contend — observed as one ordinal out of three intermittently
// failing with a kubectl error while the other two answered, which looks like a flaky gateway and
// is really a flaky lookup. Sorted here, once, so every caller agrees on which pod is ordinal N.
const podsCache = { at: 0, list: [] };
function gatewayPods() {
  if (Date.now() - podsCache.at < 5000 && podsCache.list.length) return podsCache.list;
  const out = execSync(
    // Semicolon-separated, NOT newline-separated. `{"\n"}` inside a JS template literal is a REAL
    // newline by the time kubectl sees it, and kubectl then rejects the jsonpath as an unterminated
    // quoted string. The failure was invisible from outside: gatewayPods() threw, the /gw route
    // 502'd, and /gw/<n> requests that arrived before this route existed fell through to the SPA
    // fallback and returned index.html with HTTP 200 — a route that did not exist reporting success.
    `kubectl -n ${NS} get pods -l app=cluster-gateway ` +
    `-o jsonpath='{range .items[*]}{.metadata.name} {.status.podIP} {.status.phase};{end}'`,
    { shell: '/bin/sh', timeout: 15000 }).toString();
  const parsed = out.split(';').map(l => l.trim()).filter(Boolean).map(l => {
    const [name, ip, phase] = l.split(/\s+/);
    return { name, ip, phase };
  }).filter(g => g.ip).sort((a, b) => a.name.localeCompare(b.name));
  podsCache.at = Date.now(); podsCache.list = parsed;
  return parsed;
}

const gwCache = { at: 0, body: '' };
async function gatewaysBypass(req, res) {
  try {
    if (Date.now() - gwCache.at > 5000) {
      const pods = gatewayPods();
      const gateways = await Promise.all(pods.map(async (g, i) => {
        const ready = await podHttp(g.ip, '/ready');
        let health = {};
        try { health = JSON.parse((await podHttp(g.ip, '/health')).body || '{}'); } catch { /* keep {} */ }
        return { ordinal: i, name: g.name, ip: g.ip, phase: g.phase,
                 code: ready.code, ready: ready.code === 200, health };
      }));
      gwCache.at = Date.now();
      gwCache.body = JSON.stringify({ count: gateways.length, gateways });
    }
    return json(res, 200, gwCache.body);
  } catch (e) {
    return json(res, 502, { error: `could not list gateways: ${e}` });
  }
}

/** Sum every gateway's Prometheus counters so a rate is differencing ONE series, not three. */
async function aggregateGatewayMetrics(req, res) {
  try {
    const pods = gatewayPods();
    const bodies = (await Promise.all(pods.map(g => podHttp(g.ip, '/metrics'))))
      .filter(r => r.code === 200).map(r => r.body);
    if (!bodies.length) return json(res, 502, { error: 'no gateway answered /metrics' });
    const sum = new Map(), order = [];
    for (const body of bodies) {
      for (const line of body.split('\n')) {
        const t = line.trim();
        if (!t || t.startsWith('#')) continue;
        const sp = t.lastIndexOf(' ');
        if (sp < 0) continue;
        const key = t.slice(0, sp), val = Number(t.slice(sp + 1));
        if (!Number.isFinite(val)) continue;
        if (!sum.has(key)) order.push(key);
        sum.set(key, (sum.get(key) ?? 0) + val);
      }
    }
    res.setHeader('Content-Type', 'text/plain; version=0.0.4');
    res.setHeader('X-Traderx-Gateways-Aggregated', String(bodies.length));
    res.end(order.map(k => `${k} ${sum.get(k)}`).join('\n') + '\n');
  } catch (e) {
    return json(res, 502, { error: String(e) });
  }
}

// ---- static + proxy ---------------------------------------------------------------------------
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };

function serveStatic(req, res, url) {
  let rel = decodeURIComponent(url.pathname);
  if (rel.endsWith('/')) rel += 'index.html';
  let file = path.join(ROOT, rel);
  // Path traversal guard: resolve first, then require the result to still be inside ROOT.
  if (!file.startsWith(ROOT)) return json(res, 400, {});
  // SPA fallback — a client route is not a file. Kept LAST so a genuinely missing asset does not
  // silently return index.html with HTTP 200, which is the trap that made a dev-server health
  // check pass against a route that did not exist.
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    if (path.extname(rel)) { res.statusCode = 404; return res.end('not found'); }
    file = path.join(ROOT, 'index.html');
  }
  res.setHeader('Content-Type', MIME[path.extname(file)] ?? 'application/octet-stream');
  fs.createReadStream(file).pipe(res);
}

const [EDGE_HOST, EDGE_PORT] = EDGE.split(':');
function proxyToEdge(req, res, rewrite) {
  const headers = { ...req.headers };
  // Same injection the dev proxy does, on the same route.
  if (MASTER_SECRET && (req.url ?? '').startsWith('/trade-processor')) {
    headers['x-auth-master-secret'] = MASTER_SECRET;
  }
  const upstreamPath = rewrite ? rewrite(req.url ?? '/') : (req.url ?? '/');
  const up = http.request({ host: EDGE_HOST, port: Number(EDGE_PORT), path: upstreamPath, method: req.method, headers },
    (r) => { res.writeHead(r.statusCode ?? 502, r.headers); r.pipe(res); });
  up.on('error', () => json(res, 502, { error: `edge-proxy unreachable at ${EDGE}` }));
  req.pipe(up);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://x');
  const p = url.pathname;
  if (p.startsWith('/gcs/')) return gcsBypass(req, res, url);
  if (p.startsWith('/kdbtap')) return kdbBypass(req, res);
  if (p.startsWith('/extracts')) return extractBypass(req, res);
  if (p.startsWith('/fixorder')) {
    if (req.method !== 'POST') return json(res, 405, {});
    try { return json(res, 200, await fixOrder(JSON.parse(await readBody(req)))); }
    catch (e) { return json(res, 502, { error: String(e) }); }
  }
  if (p === '/healthz') return json(res, 200, { ok: true });
  if (p === '/gateways') return gatewaysBypass(req, res);
  // /gw/<n>/... — a stable per-gateway route. Gateway pods have random names and no per-pod DNS, so
  // the ordinal is positional over the pod list sorted by name: stable while the ReplicaSet is, and
  // re-derived on every call so scaling up or down is picked up without redeploying anything.
  const gw = /^\/gw\/(\d+)(\/.*)?$/.exec(p);
  if (gw) {
    (async () => {
      try {
        const pods = gatewayPods();
        const g = pods[Number(gw[1])];
        if (!g) return json(res, 404, { error: `no gateway ordinal ${gw[1]} (${pods.length} running)` });
        const r = await podHttp(g.ip, (gw[2] || '/') + (url.search || ''));
        res.statusCode = r.code || 502;
        res.setHeader('Content-Type', 'application/json');
        res.end(r.body || '{}');
      } catch (e) { json(res, 502, { error: String(e) }); }
    })();
    return;
  }
  // Intercepted BEFORE the generic /order-matcher proxy: through the Service this would answer
  // from one arbitrary gateway, and the caller cannot tell which.
  if (p === '/order-matcher/metrics') return aggregateGatewayMetrics(req, res);
  // The original TraderX UI, served same-origin under /legacy/. This works ONLY because that app
  // ships <base href="."> — its assets resolve relative to whatever path it is served under, so no
  // rewriting of the HTML is needed and no second hostname (and no second DNS record and TLS
  // certificate) either. Strip the prefix; the edge proxy serves that app at its root.
  if (p === '/legacy' ) { res.statusCode = 302; res.setHeader('Location', '/legacy/'); return res.end(); }
  if (p.startsWith('/legacy/')) return proxyToEdge(req, res, (u) => u.slice('/legacy'.length) || '/');
  if (PROXY_PREFIXES.some(x => p === x || p.startsWith(`${x}/`))) return proxyToEdge(req, res);
  return serveStatic(req, res, url);
});

// WebSocket upgrade (the blotter's NATS feed) — proxied raw to the edge proxy.
server.on('upgrade', (req, socket, head) => {
  const up = http.request({ host: EDGE_HOST, port: Number(EDGE_PORT), path: req.url, method: 'GET',
    headers: req.headers });
  up.on('upgrade', (r, upSock, upHead) => {
    socket.write(`HTTP/1.1 101 Switching Protocols\r\n` +
      Object.entries(r.headers).map(([k, v]) => `${k}: ${v}`).join('\r\n') + '\r\n\r\n');
    if (upHead?.length) socket.unshift(upHead);
    upSock.pipe(socket).pipe(upSock);
  });
  up.on('error', () => socket.destroy());
  if (head?.length) up.write(head);
  up.end();
});

server.listen(PORT, () => console.log(`[console] :${PORT} static=${ROOT} edge=${EDGE} fix=${FIX_HOST}:${FIX_PORT}`));
