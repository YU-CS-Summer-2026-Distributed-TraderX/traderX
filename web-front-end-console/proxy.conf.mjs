// Dev proxy: everything the console needs from one `npm start`.
//  - Spawns and babysits `kubectl port-forward svc/edge-proxy` so the rig is reachable without a
//    separate terminal (the forward dies silently overnight otherwise).
//  - Reads the rig's dev-token master secret (same method as the proof scripts) and injects it on
//    /trade-processor requests, so the EOD panel's auto-mint works with no manual paste.
//  - Proxies /nats-ws as a websocket for the live blotter feed.
// Dev-rig convenience only; none of this exists in a real deployment.
import { execSync, spawn } from 'node:child_process';

const CTX = process.env.RIG_CONTEXT ?? 'kind-traderx-yu12-cluster';
const NS = process.env.RIG_NAMESPACE ?? 'traderx';
const PORT = 30080;
const target = `http://localhost:${PORT}`;

/**
 * Keep localhost:PORT answering, whoever owns the forward.
 *
 * A watchdog rather than spawn-once, because "reuse the existing forward" and "respawn my own on
 * exit" are the same job and splitting them left a hole: when the console borrowed a forward from a
 * previous run and THAT process died — which is what a rig roll does to every forward — nothing was
 * watching it, and the console sat on "rig unreachable" until someone restarted the dev server.
 * Measured during exactly that: a peer lane rolled the gateway, the borrowed forward went with it.
 * Polling a port every few seconds is cheaper than reasoning about ownership.
 */
let pf = null;
function ensureForward() {
  try {
    execSync(`nc -z localhost ${PORT}`, { stdio: 'ignore' });
    return;                                   // something is answering; leave it alone
  } catch { /* nothing there — take it over */ }
  if (pf && pf.exitCode === null) return;     // ours is starting up
  pf = spawn('kubectl', ['--context', CTX, '-n', NS, 'port-forward', 'svc/edge-proxy', `${PORT}:8080`],
    { stdio: 'ignore' });
  console.log(`[proxy] port-forwarding svc/edge-proxy via ${CTX}`);
}
ensureForward();
setInterval(ensureForward, 3000).unref?.();
process.on('exit', () => pf?.kill());

let secret = process.env.AUTH_MASTER_SECRET ?? '';
if (!secret) {
  try {
    secret = execSync(
      `kubectl --context ${CTX} -n ${NS} get secret auth-secrets -o jsonpath='{.data.dev-token-master-secret}' | base64 -d`,
      { shell: '/bin/sh' }).toString().trim();
    console.log('[proxy] dev-token master secret loaded from the rig — EOD auto-mint enabled');
  } catch {
    console.log('[proxy] could not read auth-secrets — EOD panel will ask for the secret');
  }
}

// ---- GCS bridge: read-only window onto the risk-extract archive bucket -----------------------
// The EOD cut provenance (consensus seq, session date, price version) lives in gs:// objects and
// nowhere HTTP-reachable; this serves them to the provenance panel using the developer's own
// gcloud auth. Dev-only, list+cat only, one bucket only.
import { createHash } from 'node:crypto';

const BUCKET = process.env.EXTRACT_BUCKET ?? 'gs://traderx-505400-risk-extracts';
const gcsCache = new Map();

function gcs(cmd) {
  return execSync(`gcloud storage ${cmd}`, { shell: '/bin/sh', timeout: 30000 }).toString();
}

function gcsBypass(req, res) {
  const url = new URL(req.url, 'http://x');
  try {
    if (url.pathname === '/gcs/extracts') {
      const key = 'ls';
      const hit = gcsCache.get(key);
      if (!hit || Date.now() - hit.at > 60_000) {
        const files = gcs(`ls -r '${BUCKET}/**'`).split('\n').filter(l => l.endsWith('.cut') || l.endsWith('.csv'));
        gcsCache.set(key, { at: Date.now(), body: JSON.stringify({ bucket: BUCKET, files }) });
      }
      res.setHeader('Content-Type', 'application/json');
      res.end(gcsCache.get(key).body);
      return false;
    }
    if (url.pathname === '/gcs/read') {
      const path = url.searchParams.get('path') ?? '';
      if (!path.startsWith(`${BUCKET}/`) || path.includes("'")) { res.statusCode = 400; res.end('{}'); return false; }
      if (!gcsCache.has(path)) {
        const content = gcs(`cat '${path}'`);
        const sha256 = createHash('sha256').update(content).digest('hex');
        gcsCache.set(path, { body: JSON.stringify({ path, sha256, content }) });
      }
      res.setHeader('Content-Type', 'application/json');
      res.end(gcsCache.get(path).body);
      return false;
    }
  } catch (e) {
    res.statusCode = 502;
    res.end(JSON.stringify({ error: 'gcloud failed — is the CLI authenticated?' }));
    return false;
  }
  res.statusCode = 404; res.end('{}');
  return false;
}

// ---- kdb capture-tap bridge -------------------------------------------------------------------
// The KDB-X analytical path (brief 06) is a leader-side tap writing tickerplant capture logs to
// each member's /data/kdb-capture — files q loads directly, with no HTTP surface. This serves a
// bounded tail of each member's captures for the Kdb page. Read-only kubectl exec, dev-only.
const kdbCache = { at: 0, body: '' };

function kdbBypass(req, res) {
  try {
    if (Date.now() - kdbCache.at > 10_000) {
      const members = [0, 1, 2].map(m => {
        try {
          const out = execSync(
            `kubectl --context ${CTX} -n ${NS} exec order-matcher-cluster-${m} -- sh -c ` +
            `'for f in /data/kdb-capture/*.csv; do echo "==FILE $f $(wc -l < $f)"; tail -n 300 $f; done 2>/dev/null'`,
            { shell: '/bin/sh', timeout: 20000 }).toString();
          return { member: m, capture: out };
        } catch { return { member: m, capture: '' }; }
      });
      kdbCache.at = Date.now();
      kdbCache.body = JSON.stringify({ members });
    }
    res.setHeader('Content-Type', 'application/json');
    res.end(kdbCache.body);
  } catch {
    res.statusCode = 502;
    res.end('{"error":"kubectl exec failed"}');
  }
  return false;
}

// ---- EOD extract bridge: the rig's OWN cut sink -----------------------------------------------
// The cluster writes its risk extracts to file:///data/risk-extracts on the risk-extract pod, not
// to the GCS archive — the archive holds older, uploaded cuts. So this is where a cut taken on this
// rig actually lands, and it is the only place the YU17 CONTRACTS artifact exists at all.
// Each cut is three files: seq-N.cut (the committed cut both artifacts rebuild from), seq-N.csv
// (netted positions) and seq-N-contracts.csv (OTC contracts, carried at contract grain). The
// sha256 is taken ON THE POD so it is the same number the proofs and the members report.
// Read-only kubectl exec, dev-only. Depth is fixed (date/version/file), so a glob beats find.
const extractCache = { at: 0, body: '' };

function extractBypass(req, res) {
  try {
    if (Date.now() - extractCache.at > 30_000) {
      const pod = execSync(
        `kubectl --context ${CTX} -n ${NS} get pods -l app=risk-extract -o jsonpath='{.items[0].metadata.name}'`,
        { shell: '/bin/sh', timeout: 20000 }).toString().trim();
      const out = execSync(
        `kubectl --context ${CTX} -n ${NS} exec ${pod} -- sh -c ` +
        `'for f in /data/risk-extracts/*/*/*; do [ -f "$f" ] || continue; ` +
        `echo "==FILE $f $(sha256sum $f | cut -d\\  -f1)"; cat $f; done'`,
        { shell: '/bin/sh', timeout: 30000 }).toString();
      const files = out.split('==FILE ').slice(1).map(chunk => {
        const nl = chunk.indexOf('\n');
        const [path, sha256] = chunk.slice(0, nl).trim().split(' ');
        return { path, sha256, content: chunk.slice(nl + 1) };
      });
      extractCache.at = Date.now();
      extractCache.body = JSON.stringify({ pod, files });
    }
    res.setHeader('Content-Type', 'application/json');
    res.end(extractCache.body);
  } catch {
    res.statusCode = 502;
    res.end('{"error":"kubectl exec failed — is the risk-extract pod up?"}');
  }
  return false;
}

// ---- FIX 4.4 bridge: the gateway's SECOND ingress ---------------------------------------------
// The gateway terminates a FIX 4.4 session on its own port (ADR-047): FIX_ACCEPTOR_PORT=18130,
// SenderCompID TRADERX, counterparty CLIENT1, session state ephemeral (MemoryStoreFactory) and
// wholly independent of the cluster client — which is the failover-transparency property, since a
// leader change never touches the counterparty's session.
//
// A browser cannot open a TCP socket, so the console cannot speak FIX itself. This bridge logs on,
// sends one NewOrderSingle, waits for the ExecutionReport, and hands BOTH raw messages back so the
// panel can show the actual wire text rather than a summary of it. One socket per request, closed
// straight after: sessions are ephemeral by design, so there is nothing to keep alive.
import net from 'node:net';

const FIX_PORT = 30130;
const FIX_HOST = 'localhost';
const SOH = '\x01';

function fixPortForward() {
  const pf = spawn('kubectl', ['--context', CTX, '-n', NS, 'port-forward', 'svc/order-matcher',
    `${FIX_PORT}:18130`], { stdio: 'ignore' });
  pf.on('exit', () => setTimeout(fixPortForward, 2000));
  process.on('exit', () => { pf.removeAllListeners('exit'); pf.kill(); });
}
try {
  execSync(`nc -z localhost ${FIX_PORT}`, { stdio: 'ignore' });
} catch {
  fixPortForward();
  console.log(`[proxy] port-forwarding svc/order-matcher:18130 (FIX acceptor) to ${FIX_PORT}`);
}

/** Frame a FIX message: body length and checksum are computed over the encoded bytes, not fields. */
function fixFrame(bodyFields) {
  const body = bodyFields.map(([t, v]) => `${t}=${v}`).join(SOH) + SOH;
  const head = `8=FIX.4.4${SOH}9=${Buffer.byteLength(body)}${SOH}`;
  const sum = [...Buffer.from(head + body)].reduce((a, b) => (a + b) & 0xff, 0);
  return head + body + `10=${String(sum).padStart(3, '0')}${SOH}`;
}

/** FIX UTCTimestamp: YYYYMMDD-HH:MM:SS.sss — no dashes in the date, colons kept, no trailing Z.
 *  (Stripping the colons too gets the whole Logon refused: "Incorrect data format ... field=52".) */
const fixTime = () => {
  const s = new Date().toISOString();
  return s.slice(0, 10).replace(/-/g, '') + '-' + s.slice(11, 23);
};

function fixOrder(o) {
  return new Promise((resolve) => {
    const sent = [];
    const received = [];
    const sock = net.connect(FIX_PORT, FIX_HOST);
    let buf = '';
    let seq = 1;
    let done = false;
    const finish = (error) => {
      if (done) return;
      done = true;
      sock.destroy();
      resolve({ sent, received, ...(error ? { error } : {}) });
    };
    const timer = setTimeout(() => finish('no ExecutionReport within 8s'), 8000);
    const send = (fields) => {
      const msg = fixFrame([['35', fields.type], ['49', 'CLIENT1'], ['56', 'TRADERX'],
        ['34', seq++], ['52', fixTime()], ...fields.body]);
      sent.push(msg);
      sock.write(msg);
    };
    sock.on('connect', () => send({ type: 'A', body: [['98', 0], ['108', 30], ['141', 'Y']] }));
    sock.on('data', (chunk) => {
      buf += chunk.toString('latin1');
      // Frames are self-delimiting: a message ends at its own checksum field.
      for (let end; (end = buf.indexOf(`${SOH}10=`)) >= 0;) {
        const cut = buf.indexOf(SOH, end + 1) + 1;
        if (cut <= 0) break;
        const msg = buf.slice(0, cut);
        buf = buf.slice(cut);
        received.push(msg);
        const type = /\x0135=([^\x01]+)/.exec(msg)?.[1];
        if (type === 'A') {
          send({ type: 'D', body: [
            ['11', o.clOrdId], ['1', o.accountId], ['55', o.symbol], ['54', o.side === 'Sell' ? 2 : 1],
            ['38', o.quantity], ['40', 2], ['44', o.limitPrice], ['21', 1], ['60', fixTime()],
          ] });
        } else if (type === '8' || type === '3' || type === 'j' || type === '5') {
          clearTimeout(timer);
          finish();
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

/**
 * Async on purpose, and it must stay that way: the dev server awaits the bypass result and answers
 * 404 itself when it gets `false` back, so a bypass that returns before writing its response loses
 * the race and the caller sees a 404 with no clue why. Write first, return false after.
 */
async function fixBypass(req, res) {
  try {
    const out = await fixOrder(JSON.parse((await readBody(req)) || '{}'));
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify(out));
  } catch (e) {
    res.statusCode = 502;
    res.end(JSON.stringify({ error: String((e && e.message) || e) }));
  }
  return false;
}

const plain = (ctx) => ({ context: [ctx], target, secure: false });

export default [
  plain('/order-matcher'),
  plain('/reference-data'),
  plain('/account-service'),
  plain('/position-service'),
  { context: ['/trade-processor'], target, secure: false,
    ...(secret ? { headers: { 'X-Auth-Master-Secret': secret } } : {}) },
  plain('/m0'), plain('/m1'), plain('/m2'),
  { context: ['/nats-ws'], target, secure: false, ws: true },
  { context: ['/gcs'], target, secure: false, bypass: gcsBypass },
  { context: ['/kdbtap'], target, secure: false, bypass: kdbBypass },
  { context: ['/extracts'], target, secure: false, bypass: extractBypass },
  { context: ['/fixorder'], target, secure: false, bypass: fixBypass },
  plain('/algo'), plain('/tempo'),
  // Grafana serves from this sub-path (GF_SERVER_SERVE_FROM_SUB_PATH), so proxying the prefix is
  // enough for the whole app — and without the route the dev server answers its SPA fallback with
  // a 200, which any "is it up?" check reads as healthy. Same fallthrough that made /mN lie.
  plain('/grafana'),
];
