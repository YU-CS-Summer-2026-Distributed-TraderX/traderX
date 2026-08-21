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
import { createHash, createHmac, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';

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

// ---- admin auth: READ is open, CHANGE needs a login -------------------------------------------
// Deliberately NOT a route guard. The admin page stays viewable by anyone — the TCA reports, recon
// status, parent orders and band scan are all reads, and hiding them behind a login would only make
// the demo harder to show. What needs an identity is a CHANGE, so the gate is on the mutating
// endpoints themselves rather than on the page that happens to call them.
//
// That distinction matters for more than tidiness: a client-side route guard is not a control at
// all — the endpoints are reachable with curl whether or not an Angular route rendered. Gating here
// is the part that actually holds, and the UI work is presentation on top of it.
const ADMIN_USER = process.env.ADMIN_USER ?? 'admin';
// scrypt$<salt-hex>$<64-byte-hash-hex>. Seeded default; override with ADMIN_PASSWORD_HASH from the
// auth-secrets Secret to change the credential without rebuilding the image. The PLAINTEXT is never
// stored, here or anywhere else — a wrong password and an unknown user are indistinguishable to a
// caller, and both take the same work to answer.
const ADMIN_HASH = process.env.ADMIN_PASSWORD_HASH
  ?? 'scrypt$d237dc4715a581e8aa52fe69e851c068$24425c6a2a0db01442036d58957824cab947b45bbc35be6debd2839d0ec452650171b95b6d353f668391e97b33b3e9e669a3e790036991b153ea8233c65b6adf';
// A random per-process secret is the right DEFAULT (a restart invalidating sessions is a re-login,
// not an outage) but it is wrong the moment there is a second replica, because the two would reject
// each other's cookies with no symptom but a random logout. Set AUTH_SESSION_SECRET before scaling
// the console past one pod.
const SESSION_SECRET = process.env.AUTH_SESSION_SECRET ?? randomBytes(32).toString('hex');
const SESSION_TTL_MS = Number(process.env.AUTH_SESSION_TTL_MS ?? 8 * 60 * 60 * 1000);
const COOKIE = 'tx_admin';

function checkPassword(user, password) {
  // Compare BOTH halves in constant time and always do the scrypt work, so a bad username and a bad
  // password cost the same and neither can be found by timing.
  const [scheme, salt, want] = String(ADMIN_HASH).split('$');
  if (scheme !== 'scrypt' || !salt || !want) return false;
  const got = scryptSync(String(password ?? ''), salt, 32);
  const wantBuf = Buffer.from(want, 'hex').subarray(0, 32);
  const passwordOk = got.length === wantBuf.length && timingSafeEqual(got, wantBuf);
  const userBuf = Buffer.from(String(user ?? '').padEnd(64).slice(0, 64));
  const adminBuf = Buffer.from(ADMIN_USER.padEnd(64).slice(0, 64));
  return timingSafeEqual(userBuf, adminBuf) && passwordOk;
}

const sign = (v) => createHmac('sha256', SESSION_SECRET).update(v).digest('hex');

function issueToken(user) {
  const body = `${user}.${Date.now() + SESSION_TTL_MS}`;
  return `${body}.${sign(body)}`;
}

function readToken(req) {
  const raw = req.headers.cookie ?? '';
  const hit = raw.split(';').map(s => s.trim()).find(s => s.startsWith(`${COOKIE}=`));
  if (!hit) return null;
  const token = decodeURIComponent(hit.slice(COOKIE.length + 1));
  const i = token.lastIndexOf('.');
  if (i < 0) return null;
  const body = token.slice(0, i), mac = token.slice(i + 1);
  const expect = sign(body);
  // Length-check first: timingSafeEqual THROWS on a length mismatch, and an exception here would be
  // a 500 on every request carrying a malformed cookie.
  if (mac.length !== expect.length || !timingSafeEqual(Buffer.from(mac), Buffer.from(expect))) return null;
  const [user, exp] = body.split('.');
  if (!Number(exp) || Number(exp) < Date.now()) return null;
  return { user };
}

// OVERRIDES, not changes. The first cut of this list gated everything the admin page could mutate,
// which sounded right and was drawn from the wrong map: three of those four actions live on the
// TRADING page (cancel in activity-panel and blotter-panel, force-settle in blotter-panel, algo
// create in ticket-panel). Only the orphan sweep is admin-only.
//
// Gating by "is it a change" therefore produced a cliff on a page nobody had in view: a plain order
// went through signed-out and a TWAP/VWAP order on the SAME ticket 401'd, because algo create was
// gated and order entry was not. yaakov's call, 2026-08-20: gate the OVERRIDES.
//
// The line that survives: an override makes the system depart from what it would have done by
// itself. Force-settle jumps a trade past its settlement cycle; the orphan sweep rewrites
// reconciliation state. Cancelling your own order and scheduling a TWAP are ordinary trading, and
// the trading page offers both to anyone — so gating them there would be a fence with no field
// behind it, which is the same reason order entry was never on this list.
const ADMIN_MUTATIONS = [
  { method: 'POST', re: /^\/trade-processor\/trades\/[^/]+\/settlement\/force$/ },
  { method: null,   re: /^\/trade-processor\/recon\/orphan-sweep$/ },
  // The end-of-day chain. These three are the clearest overrides in the system: closing a session
  // mints the day's price version, an override replaces a price the system derived, and publishing
  // emits `eod.prices.ready`, which starts a chain nobody can call back —
  //   prices published -> position-service PnL -> eod.pnl.done -> a sequenced risk-extract cut
  //   -> risk.extract.ready -> write-once to gs://…/risk-extracts (immutable: objectCreator only,
  //   so a second write is a 403, not an overwrite).
  //
  // These endpoints already required a trade-processor admin JWT, which is why they LOOKED
  // protected. They were not: the console mints that token itself from the master secret it is
  // handed, so it was granted to anyone who loaded the page. An automatic credential authenticates
  // the SERVICE, never the person — and this is the action that ends in an artifact an external
  // consumer treats as the firm's official numbers.
  { method: 'POST', re: /^\/trade-processor\/eod\/session\/close$/ },
  { method: 'POST', re: /^\/trade-processor\/eod\/prices\/[^/]+\/(override|publish)$/ },
];

const needsAuth = (method, p) =>
  ADMIN_MUTATIONS.some(m => (m.method === null || m.method === method) && m.re.test(p));

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
const podHttp = (ip, path, port = 18110, timeout = 6000) => new Promise((resolve) => {
  const req = http.request({ host: ip, port, path, method: 'GET', timeout }, (r) => {
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
const podsCache = new Map();
function podsFor(label) {
  const hit = podsCache.get(label);
  if (hit && Date.now() - hit.at < 5000 && hit.list.length) return hit.list;
  const out = execSync(
    // Semicolon-separated, NOT newline-separated. `{"\n"}` inside a JS template literal is a REAL
    // newline by the time kubectl sees it, and kubectl then rejects the jsonpath as an unterminated
    // quoted string. The failure was invisible from outside: gatewayPods() threw, the /gw route
    // 502'd, and /gw/<n> requests that arrived before this route existed fell through to the SPA
    // fallback and returned index.html with HTTP 200 — a route that did not exist reporting success.
    `kubectl -n ${NS} get pods -l app=${label} ` +
    `-o jsonpath='{range .items[*]}{.metadata.name} {.status.podIP} {.status.phase};{end}'`,
    { shell: '/bin/sh', timeout: 15000 }).toString();
  const parsed = out.split(';').map(l => l.trim()).filter(Boolean).map(l => {
    const [name, ip, phase] = l.split(/\s+/);
    return { name, ip, phase };
  }).filter(g => g.ip).sort((a, b) => a.name.localeCompare(b.name));
  podsCache.set(label, { at: Date.now(), list: parsed });
  return parsed;
}
const gatewayPods = () => podsFor('cluster-gateway');
// Members are a StatefulSet, so their names already sort into ordinal order.
const memberPods = () => podsFor('order-matcher-cluster');

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

// ---- the EOD chain, as four stages the console can actually see ------------------------------
// The day's two EOD artifacts are not two features, they are one pipeline, and until now no single
// surface showed it. The price report is served over HTTP; the PnL stage has a repository and a
// consumer and NO endpoint at all, so it exists only as rows; the extract lands on a pod volume;
// the published copy lands in GCS. Four services, four different read mechanisms, and only this
// process can reach all four.
//
// Read from SQL rather than proxying the price report, deliberately: that endpoint wants a
// trade-processor admin JWT, which only the BROWSER mints today. Minting a second one here would
// put a third 8-hour clock in the system and make a status read fail for a credential reason —
// which is exactly the confusion the bands panel just spent a round untangling. The session table
// is the same fact one layer down and needs no token.
//
// Every stage answers with a STATE, never a bare count, and `pending` is distinguishable from
// `unreadable`. A chain view whose stages can only say "yes" or "nothing" reports a broken rig and
// an idle one identically — and an idle rig is by far the more common reading.
// Resolve the POD by label and exec on the pod, never `exec deploy/...`. This pod's Role grants
// pods get/list and pods/exec create — it cannot read a Deployment, and `kubectl exec deploy/x`
// must GET the Deployment first to pick a pod. Written from a laptop with cluster-admin it worked
// perfectly and reported `unreadable` for both SQL stages the moment it ran in-cluster. Same shape
// as every other "true where it was authored" defect: the privilege, not the code, was the thing
// that differed.
const podByLabel = (label) => execSync(
  `kubectl -n ${NS} get pods -l ${label} --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}'`,
  { shell: '/bin/sh', timeout: 20000 }).toString().trim();

const sqlQuery = (q) => execSync(
  `kubectl -n ${NS} exec ${podByLabel('app=eod-price-db')} -- mariadb -utraderx -ptraderx traderx -N -B -e ${JSON.stringify(q)}`,
  { shell: '/bin/sh', timeout: 25000 }).toString().trim();

function eodChain(req, res, url) {
  const date = (url.searchParams.get('date') ?? '').trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return json(res, 400, { error: 'date=YYYY-MM-DD required' });
  const out = { date, prices: null, pnl: null, extract: null, published: null };

  try {
    const rows = sqlQuery(
      `SELECT version, status, instrument_count, flagged_count, published_at FROM eod_price_session `
      + `WHERE session_date='${date}' ORDER BY version DESC LIMIT 1;`);
    if (!rows) {
      out.prices = { state: 'pending', detail: 'no session for this date — close one to mint a version' };
    } else {
      const [version, status, instruments, flagged, publishedAt] = rows.split('\t');
      out.prices = {
        state: status === 'PUBLISHED' ? 'ok' : 'draft',
        version: Number(version), status, instruments: Number(instruments), flagged: Number(flagged),
        publishedAt: publishedAt === 'NULL' ? null : publishedAt,
        // The gate is the point of the DRAFT state, so say what clears it rather than only that it exists.
        detail: status === 'PUBLISHED' ? `v${version} published`
          : Number(flagged) > 0 ? `v${version} draft — ${flagged} flagged instrument(s) must be overridden before it can publish`
          : `v${version} draft — nothing flagged, ready to publish`,
      };
    }
  } catch {
    out.prices = { state: 'unreadable', detail: 'could not read eod_price_session' };
  }

  // ROW COUNT ALONE CANNOT ANSWER THIS. YU06's fail-safe HALTS an account whose positions include a
  // security with no closing price, marks nothing for it, and still publishes eod.pnl.done — so a
  // completed, correct, deliberately-refusing run and a run that never happened both leave zero
  // rows. Reported as "pending" that reads as "still waiting", which is the one thing it is not.
  // Observed live: 2 accounts halted on ZTS, 0 rows, chain finished.
  //
  // The count is the wrong instrument, so ask the service what it did. The summary line carries the
  // verdict; only fall back to "pending" when there is genuinely no run to describe.
  try {
    const n = Number(sqlQuery(`SELECT COUNT(*) FROM eod_position_pnl WHERE session_date='${date}';`));
    let summary = '';
    try {
      summary = execSync(
        `kubectl -n ${NS} logs ${podByLabel('app=position-service')} --tail=4000 2>/dev/null `
        + `| grep "eod pnl marked" | grep "date=${date}" | tail -1 || true`,
        { shell: '/bin/sh', timeout: 25000 }).toString().trim();
    } catch { /* the log is a bonus signal; the row count still stands on its own */ }
    const marked = Number(/accounts=(\d+)/.exec(summary)?.[1] ?? NaN);
    const halted = Number(/halted=(\d+)/.exec(summary)?.[1] ?? NaN);
    if (Number.isFinite(n) && n > 0) {
      out.pnl = { state: 'ok', rows: n, halted: Number.isFinite(halted) ? halted : null,
        detail: `${n} position PnL row(s)` + (halted > 0 ? `, ${halted} account(s) halted` : '') };
    } else if (Number.isFinite(halted) && halted > 0) {
      out.pnl = { state: 'halted', rows: 0, halted,
        detail: `ran and refused: ${halted} account(s) halted on a position whose security has no `
          + `closing price in this snapshot. The chain continued — this is the fail-safe working, `
          + `not a stall.` };
    } else if (summary) {
      out.pnl = { state: 'ok', rows: 0, halted: 0, detail: 'ran with nothing to mark' };
    } else {
      out.pnl = { state: 'pending', rows: 0, detail: 'position-service has not marked this session yet' };
    }
  } catch {
    out.pnl = { state: 'unreadable', detail: 'could not read eod_position_pnl' };
  }

  // READ THE SINK FIRST — both stages below mean different things depending on it, and this bit
  // three times while building this endpoint. Where the cut LANDS is configuration, so "the local
  // volume is empty" and "no cut was produced" are the same observation only when the sink is local.
  let sink = '';
  try {
    sink = execSync(
      `kubectl -n ${NS} get pod ${podByLabel('app=risk-extract')} -o jsonpath=`
      + `'{.spec.containers[0].env[?(@.name=="RISK_EXTRACT_SINK_URI")].value}'`,
      { shell: '/bin/sh', timeout: 20000 }).toString().trim();
  } catch { /* unknown sink is reported as unknown, never assumed */ }
  const remoteSink = sink.startsWith('gs://');

  try {
    const pod = execSync(`kubectl -n ${NS} get pods -l app=risk-extract -o jsonpath='{.items[0].metadata.name}'`,
      { shell: '/bin/sh', timeout: 20000 }).toString().trim();
    const listed = execSync(
      `kubectl -n ${NS} exec ${pod} -- sh -c 'ls -1 /data/risk-extracts/${date}/*/* 2>/dev/null || true'`,
      { shell: '/bin/sh', timeout: 25000 }).toString().trim();
    const files = listed ? listed.split('\n').filter(Boolean) : [];
    out.extract = files.length
      ? { state: 'ok', pod, sink, files, detail: `${files.length} artifact(s) cut on ${pod}` }
      : remoteSink
        // With a gs:// sink the cut never touches this volume, so an empty directory says nothing
        // about whether a cut happened — `published` is the stage that knows. Saying "no cut yet"
        // here would report a completed chain as a stalled one.
        ? { state: 'remote', pod, sink, files: [],
            detail: `the sink is ${sink}, so cuts go straight to the bucket and never land on this `
              + `volume — see the published stage for whether one was produced` }
        : { state: 'pending', pod, sink, files: [], detail: 'no cut for this date yet — it follows eod.pnl.done' };
  } catch {
    out.extract = { state: 'unreadable', detail: 'could not read the risk-extract volume' };
  }

  try {
    const listed = execSync(`gcloud storage ls -r '${BUCKET}/**' 2>/dev/null || true`,
      { shell: '/bin/sh', timeout: 30000 }).toString();
    const files = listed.split('\n').map(s => s.trim()).filter(s => s.includes(date) && !s.endsWith(':') && !s.endsWith('/'));
    // "Nothing in the bucket" means two different things, and only one of them is waiting. If the
    // sink is not pointed at gs:// then nothing will EVER arrive, however long anyone watches — so
    // read the sink the extract is actually configured with rather than assuming it is this bucket.
    out.published = files.length
      ? { state: 'ok', bucket: BUCKET, sink, files, detail: `${files.length} object(s) in the bucket` }
      : sink && !remoteSink
        ? { state: 'not-configured', bucket: BUCKET, sink, files: [],
            detail: `the extract's sink is ${sink}, so the cut stays on the pod and nothing is `
              + `published externally. Not a wait — point RISK_EXTRACT_SINK_URI at the bucket (and `
              + `give it a real HMAC credential) to change that.` }
        : { state: 'pending', bucket: BUCKET, sink, files: [],
            detail: 'nothing written to the bucket for this date' };
  } catch {
    out.published = { state: 'unreadable', detail: 'could not list the bucket' };
  }

  return json(res, 200, out);
}

// ---- the console USES a credential; it never ISSUES one ---------------------------------------
// This pod is handed AUTH_MASTER_SECRET so the EOD and regulatory panels can authenticate. It used
// to inject that secret on every `/trade-processor` path — INCLUDING `/auth/dev-token`, the mint —
// so a plain unauthenticated POST from any visitor returned a signed `admin:true` JWT. Measured
// 2026-08-21: `curl -X POST https://…/trade-processor/auth/dev-token` -> 200 and an admin token.
//
// That is a confused deputy. The console held a powerful credential and handed it out to anyone who
// asked, which is strictly worse than the endpoints it was protecting: a token outlives the page,
// works against any service sharing the JWT secret, and carries no trace of who obtained it.
//
// The fix is not to gate the mint — the console's own READS need admin (the gateway answers
// `{"error":"admin JWT required"}` on /regulatory), and gating it would take the admin page's
// read-only panels away from the anonymous viewers we deliberately serve. The fix is that the
// console mints for ITSELF, in-process, keeps the token here, and attaches it on the caller's
// behalf. Reads stay open because the console does the authenticating; changes still need a login
// because ADMIN_MUTATIONS refuses them BEFORE the proxy ever runs. The credential stops being an
// ambient grant handed to the browser and becomes something this process spends on request.
let INTERNAL_JWT = '';
const JWT_TTL_S = 3600;

function mintInternalToken() {
  if (!MASTER_SECRET) return;
  const body = JSON.stringify({ subject: 'console', accounts: [], admin: true, ttlSeconds: JWT_TTL_S });
  const req = http.request({
    host: EDGE_HOST, port: Number(EDGE_PORT), path: '/trade-processor/auth/dev-token', method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body),
               'x-auth-master-secret': MASTER_SECRET },
  }, (r) => {
    const chunks = [];
    r.on('data', (c) => chunks.push(c));
    r.on('end', () => {
      const t = Buffer.concat(chunks).toString().trim();
      // Shape-test, don't truthiness-test: an error page is a non-empty string too, and storing one
      // would send garbage as a Bearer token on every read for the next half hour.
      if (r.statusCode === 200 && t.split('.').length === 3) INTERNAL_JWT = t;
      else console.log(`[console] internal token mint failed: HTTP ${r.statusCode}`);
    });
  });
  req.on('error', (e) => console.log(`[console] internal token mint unreachable: ${e.message}`));
  req.end(body);
}
// Routes whose upstream demands an admin JWT. The console attaches its own on the caller's behalf;
// a WRITE among them has already been refused by ADMIN_MUTATIONS unless the caller signed in.
const NEEDS_INTERNAL_JWT = (p) =>
  p.startsWith('/trade-processor/') || p.startsWith('/order-matcher/regulatory');

const [EDGE_HOST, EDGE_PORT] = EDGE.split(':');

mintInternalToken();
// Refresh well inside the TTL. A token that expires mid-demo presents as a panel losing its data
// for no visible reason, which is the failure this whole file keeps being written to avoid.
setInterval(mintInternalToken, (JWT_TTL_S / 2) * 1000).unref?.();

function proxyToEdge(req, res, rewrite) {
  const headers = { ...req.headers };
  // Attach the console's OWN token, overwriting anything the client sent. Overwriting is the point:
  // a stale or forged Authorization header from the page must not decide what the upstream sees, and
  // a client whose own token expired must not lose a read it is entitled to.
  const reqPath = req.url ?? '';
  if (INTERNAL_JWT && NEEDS_INTERNAL_JWT(reqPath)) {
    headers['authorization'] = `Bearer ${INTERNAL_JWT}`;
    delete headers['Authorization'];
  }
  // The master secret is NEVER forwarded. It is this process's credential for minting its own
  // token, not a header to sprinkle on proxied traffic.
  delete headers['x-auth-master-secret'];
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

  // ---- auth ----
  if (p === '/auth/me') {
    const s = readToken(req);
    return s ? json(res, 200, { user: s.user }) : json(res, 401, { code: 'signed_out', error: 'not signed in' });
  }
  if (p === '/auth/logout') {
    res.setHeader('Set-Cookie', `${COOKIE}=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0`);
    return json(res, 200, { ok: true });
  }
  if (p === '/auth/login') {
    if (req.method !== 'POST') return json(res, 405, { error: 'POST only' });
    let body = {};
    try { body = JSON.parse(await readBody(req)); } catch { /* fall through to a 401 */ }
    if (!checkPassword(body.user, body.password)) {
      // One message for both failures. "No such user" tells an attacker which half to keep trying.
      return json(res, 401, { code: 'bad_credentials', error: 'invalid username or password' });
    }
    // Secure is set from the proto the CLIENT saw, not from ours: the pod speaks plain HTTP behind
    // the load balancer, so hardcoding Secure would work on the rig and silently drop the cookie on
    // a local http:// run — and hardcoding it off would ship a cookie over plaintext on the rig.
    const secure = (req.headers['x-forwarded-proto'] ?? '').split(',')[0].trim() === 'https';
    res.setHeader('Set-Cookie', `${COOKIE}=${encodeURIComponent(issueToken(ADMIN_USER))}; HttpOnly; `
      + `SameSite=Strict; Path=/; Max-Age=${Math.floor(SESSION_TTL_MS / 1000)}${secure ? '; Secure' : ''}`);
    return json(res, 200, { user: ADMIN_USER });
  }
  // The mint is not a route this console offers. It answered an unauthenticated POST with a signed
  // admin JWT for as long as this file forwarded the master secret, and a token, once issued, is
  // outside every control here — it works against any service sharing the JWT secret, for its full
  // TTL, with nothing recording who asked. Refused for everyone, signed in or not: the console
  // authenticates on your behalf now, so there is no reason for a browser to hold one.
  if (p === '/trade-processor/auth/dev-token') {
    return json(res, 403, { code: 'mint_disabled',
      error: 'the console authenticates on your behalf; it does not issue tokens' });
  }
  // The gate itself. Placed before every proxy path below, so a change cannot reach the cluster by
  // any route this server offers.
  if (needsAuth(req.method, p) && !readToken(req)) {
    // A STABLE marker, because the console has to tell this 401 apart from the risk-control 401 and
    // was reduced to matching on the path. Prose is the worst possible discriminator: it is the part
    // most likely to be reworded, and a reword would silently turn a sign-in prompt into a dead
    // button. `code` is the contract; the message stays free to change.
    res.setHeader('WWW-Authenticate', 'Cookie realm="traderx-console"');
    return json(res, 401, { code: 'admin_auth_required', error: 'sign in as an administrator to make this change' });
  }
  if (p === '/gateways') return gatewaysBypass(req, res);
  if (p === '/eod/chain') return eodChain(req, res, url);
  // THE MEMBER COUNT IS ALSO A CONFIGURATION, not a constant. An Aeron cluster is normally 3 or 5;
  // the console hardcoded exactly three member rows, so a 5-member cluster would have shown 3 and a
  // shrunk one would have shown two dead rows for ever. Same shape as the gateway bug, different
  // resource — found by auditing for hardcoded values rather than by anything failing.
  if (p === '/members') {
    (async () => {
      try {
        const pods = memberPods();
        const members = await Promise.all(pods.map(async (m, i) => {
          const r = await podHttp(m.ip, '/health', 8080);
          let health = {};
          try { health = JSON.parse(r.body || '{}'); } catch { /* keep {} */ }
          return { ordinal: i, name: m.name, ip: m.ip, phase: m.phase, code: r.code, health };
        }));
        json(res, 200, { count: members.length, members });
      } catch (e) { json(res, 502, { error: `could not list members: ${e}` }); }
    })();
    return;
  }
  const mem = /^\/mem\/(\d+)(\/.*)?$/.exec(p);
  if (mem) {
    (async () => {
      try {
        const pods = memberPods();
        const m = pods[Number(mem[1])];
        if (!m) return json(res, 404, { error: `no member ordinal ${mem[1]} (${pods.length} running)` });
        const r = await podHttp(m.ip, (mem[2] || '/') + (url.search || ''), 8080);
        res.statusCode = r.code || 502;
        res.setHeader('Content-Type', 'application/json');
        res.end(r.body || '{}');
      } catch (e) { json(res, 502, { error: String(e) }); }
    })();
    return;
  }
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
