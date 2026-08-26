// Dev proxy: everything the console needs from one `npm start`.
//  - Spawns and babysits `kubectl port-forward svc/edge-proxy` so the rig is reachable without a
//    separate terminal (the forward dies silently overnight otherwise).
//  - (removed) read the rig's dev-token master secret and injected it on
//    /trade-processor requests, so the EOD panel's auto-mint works with no manual paste.
//  - Proxies /nats-ws as a websocket for the live blotter feed.
// Dev-rig convenience only; none of this exists in a real deployment.
import { execFileSync, execSync, spawn } from 'node:child_process';

const CTX = process.env.RIG_CONTEXT ?? 'kind-traderx-yu12-cluster';
const NS = process.env.RIG_NAMESPACE ?? 'traderx';
const PORT = 30080;
// RIG_URL points the whole console at a remote rig instead of the port-forwarded kind one —
// `RIG_URL=https://yaakovseif.dev npm start`. The state runs in two places now, and routes that
// exist on one and not the other (/gateways, /members, /gw/N, /mem/N) cannot be developed against
// a rig that lacks them. When it is set the port-forward is skipped entirely: nothing local to
// forward to.
const REMOTE = process.env.RIG_URL ?? '';
/**
 * CONSOLE_API points dev at a LOCALLY RUN `node server.mjs` instead of straight at the rig's edge.
 *
 * It closes a real gap. Several routes are the console SERVER's own — `/members`, `/gateways`,
 * `/gw/N`, `/mem/N`, `/auth/*`, `/eod/chain` — and in dev that server is not running, so they were
 * proxied to an edge proxy that has never served them. The symptoms were "the cluster is
 * unavailable in the System tab" and a sign-in form that could not reach its endpoint: both correct
 * reports of a dev environment missing its back end, neither a bug in the rig or the app.
 *
 * Pointing at that server reproduces PRODUCTION's shape exactly — ingress → console server → edge —
 * rather than reimplementing each route as a bypass, so what dev exercises is the code that ships.
 * Run it with:
 *   PORT=8090 EDGE_PROXY=localhost:30080 POD_HTTP_VIA_EXEC=1 \
 *   KUBECONFIG=<kind-only kubeconfig> node server.mjs
 * The kubeconfig must be scoped to the kind context: server.mjs shells `kubectl` with no
 * --context, so on a laptop it would otherwise read whatever context happens to be current.
 */
const CONSOLE_API = process.env.CONSOLE_API ?? '';
const target = REMOTE || CONSOLE_API || `http://localhost:${PORT}`;

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
if (REMOTE) {
  console.log(`[proxy] targeting remote rig ${REMOTE} — no port-forward`);
} else {
  ensureForward();
  setInterval(ensureForward, 3000).unref?.();
  process.on('exit', () => pf?.kill());
}

// The dev-token master secret used to be read off the rig here and injected on /trade-processor so
// the console could mint itself an admin JWT. Both halves are gone: the mint answers 403 for
// everyone, because a page holding that secret would issue admin:true tokens to anyone who loaded
// it. Nothing in this file needs a credential now — the console's own server authenticates the
// reads, and the writes want a sign-in.

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


// ---- TAQ replay tape: per-day opens and closes (ADR-070) --------------------------------------
// Day/range views need prices[SYMBOL][day][0] and [day][last]; the whole extract is a megabyte of
// intraday windows nobody looks at, so the summarising runs where the file already is.
//
// READ FROM THE PUBLISHER'S OWN MOUNT, not from the bucket. The Secret is fetched at bring-up and
// an epoch never refetches, so the bucket object can be a rebuild ahead of what is actually
// replaying — and a day view disagreeing with the clock above it is worse than no day view.
// `node -e` because the pod is a node image with no python, and execFileSync because the snippet
// is full of quotes.
const TAPE_JS = "const z=require('zlib'),f=require('fs');"
  + "const e=JSON.parse(z.gunzipSync(f.readFileSync("
  + "process.env.TAQ_REPLAY_EXTRACT_PATH||'/etc/taq-replay/extract.json.gz')).toString('utf8'));"
  + "const s={};for(const[k,v]of Object.entries(e.prices))s[k]=v.map(d=>[d[0],d[d.length-1]]);"
  + "process.stdout.write(JSON.stringify({source:e.source,windowSeconds:e.windowSeconds,"
  + "sessionSeconds:e.sessionSeconds,compression:e.compression,days:e.days,symbols:s}));";
const tapeCache = { at: 0, body: '' };

function tapeBypass(req, res) {
  try {
    // The extract only changes at a bring-up, so this is cached for minutes, not seconds.
    if (Date.now() - tapeCache.at > 300_000) {
      const out = execFileSync('kubectl', ['--context', CTX, '-n', NS, 'exec', 'deploy/price-publisher',
        '--', 'node', '-e', TAPE_JS], { timeout: 30000, maxBuffer: 32 * 1024 * 1024 }).toString();
      // Parse before caching: a pod that printed anything ahead of the JSON would otherwise be
      // cached as the tape for five minutes.
      JSON.parse(out);
      tapeCache.at = Date.now();
      tapeCache.body = out;
    }
    res.setHeader('Content-Type', 'application/json');
    res.end(tapeCache.body);
  } catch {
    res.statusCode = 502;
    res.end('{"error":"could not read the replay extract off price-publisher"}');
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

// changeOrigin when remote: the rig routes by Host header, and without it every request arrives
// announcing localhost:4200 and misses the ingress rule entirely.
const plain = (ctx) => ({ context: [ctx], target, secure: false, changeOrigin: !!REMOTE });

export default [
  plain('/order-matcher'),
  plain('/reference-data'),
  plain('/account-service'),
  plain('/position-service'),
  // NO master-secret header any more. It existed so the console could mint itself an admin JWT,
  // and that mint now answers 403 for everyone: handing a page the secret made it a confused
  // deputy, issuing admin:true tokens to whoever loaded it. The server the console is served from
  // authenticates these reads on the caller's behalf instead, and refuses the writes without a
  // sign-in — so there is nothing here for a secret to do.
  plain('/trade-processor'),
  plain('/m0'), plain('/m1'), plain('/m2'),
  { context: ['/nats-ws'], target, secure: false, ws: true },
  { context: ['/gcs'], target, secure: false, bypass: gcsBypass },
  // The kdb and extract bridges shell out to `kubectl exec` against the LOCAL kind context. Against
  // a remote rig that would answer from the wrong cluster entirely — the page would show one rig's
  // captures beside another rig's cluster state and look perfectly consistent. The remote already
  // serves both routes itself, so proxy them there and keep the bypasses for the local rig only.
  REMOTE ? plain('/kdbtap') : { context: ['/kdbtap'], target, secure: false, bypass: kdbBypass },
  REMOTE ? plain('/extracts') : { context: ['/extracts'], target, secure: false, bypass: extractBypass },
  REMOTE ? plain('/taq-tape') : { context: ['/taq-tape'], target, secure: false, bypass: tapeBypass },
  { context: ['/fixorder'], target, secure: false, bypass: fixBypass },
  plain('/algo'), plain('/tempo'),
  // Sign-in lives on the console's own server, so dev has to forward it or the login form posts
  // into the SPA fallback. Without this route /auth/me answered 200 WITH THE INDEX PAGE — worse
  // than a 404, because "200 means signed in" reads an unauthenticated operator as an admin. Same
  // fallthrough as /grafana above and /mN before it, third time in this file.
  // cookieDomainRewrite so the rig's Set-Cookie is scoped to localhost rather than the rig host.
  { context: ['/auth'], target, secure: false, changeOrigin: !!REMOTE, cookieDomainRewrite: '' },
  // The EOD chain is served by the console's own server (it shells kubectl and gcloud), NOT by
  // trade-processor — so dev must forward it or the page reads the SPA fallback as a chain. Fourth
  // route in this file to need saying: /mN, /grafana, /auth, and now this one. The call is slow by
  // construction (three kubectl execs and a bucket list), so nothing should poll it tightly.
  // SCOPED TO /eod/chain, NOT /eod — "/eod" is also the console's own ROUTER PATH for the
  // End-of-Day page, so proxying the prefix sends a page navigation to the rig and the browser
  // gets the DEPLOYED console's production index.html back. It renders nothing and looks like a
  // broken bootstrap: hashed asset names that 404 against the dev server, and an empty app-root.
  // curl misses it entirely, because curl asks for / and for the API path and never for the route.
  plain('/eod/chain'),
  // Grafana serves from this sub-path (GF_SERVER_SERVE_FROM_SUB_PATH), so proxying the prefix is
  // enough for the whole app — and without the route the dev server answers its SPA fallback with
  // a 200, which any "is it up?" check reads as healthy. Same fallthrough that made /mN lie.
  plain('/grafana'),
  // Per-pod fan-out and discovery, added on the cloud rig: one row per gateway and per member.
  plain('/gateways'), plain('/members'), plain('/gw'), plain('/mem'), plain('/legacy'),
  // price-publisher's own /health carries the TAQ replay position. The edge routes it; without this
  // the dev server answers with its SPA fallback and the replay clock reads a page as a clock.
  plain('/price-publisher'),
];
