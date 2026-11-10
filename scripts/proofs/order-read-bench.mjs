// order-read-bench.mjs - FR-DRS-46: the order view's read, GET /accounts/{id}/orders, timed three ways from inside the
// cluster, so the router is measured against the alternative it replaced (ADR-103 §3, yaakov's condition):
//   router  the clients' address (Service trade-processor): a replica answers its own accounts, forwards the rest
//   fanout  the caller asks every replica (pod DNS) at once and merges; the owner answers, the others refuse (421)
//   owner   the caller computes the owner itself and asks only it: the floor either design is compared with
// Each mode runs --secs with --concurrency workers over --accounts; one RESULT line per mode (latency in ms). --query
// picks the shape: unset = open orders (small), ?status=all = every order the view holds (megabytes on a loaded rig).
//   node order-read-bench.mjs --replicas 4 --partitions 8 --accounts 9900010-9900017 --secs 30 --concurrency 16 [--query ?status=all]
const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d; };
const N = +arg('replicas', 4), P = +arg('partitions', 8), SECS = +arg('secs', 30), C = +arg('concurrency', 16);
const [lo, hi] = arg('accounts', '9900010-9900017').split('-').map(Number);
const MODES = arg('modes', 'router,fanout,owner').split(',');
const SVC = arg('service', 'http://trade-processor:18091');
const QUERY = arg('query', '');
const pod = k => `http://trade-processor-${k}.trade-processor-replicas:18091`;
const accounts = Array.from({ length: hi - lo + 1 }, (_, i) => lo + i);
const ownerOf = a => Math.floor((((a % P) + P) % P) * N / P);

async function read(mode, a) {
  const path = `/accounts/${a}/orders${QUERY}`;
  if (mode === 'router' || mode === 'owner') {
    const r = await fetch((mode === 'router' ? SVC : pod(ownerOf(a))) + path);
    const body = await r.text();
    if (r.status !== 200) throw new Error(`${mode} ${a}: ${r.status}`);
    return JSON.parse(body).length;
  }
  const rs = await Promise.all(Array.from({ length: N }, (_, k) =>
    fetch(pod(k) + path, { headers: { 'X-TraderX-Routed': 'fanout' } }).then(async r => ({ status: r.status, body: await r.text() }))));
  const ok = rs.filter(r => r.status === 200);
  if (ok.length !== 1 || rs.some(r => r.status !== 200 && r.status !== 421)) throw new Error(`fanout ${a}: ${rs.map(r => r.status)}`);
  return ok.flatMap(r => JSON.parse(r.body)).length;   // the merge
}

const pct = (xs, q) => xs[Math.min(xs.length - 1, Math.floor(q * xs.length))];
for (const mode of MODES) {
  for (const a of accounts) await read(mode, a);   // warm: connections, JIT on both ends
  const lat = [], end = Date.now() + SECS * 1000; let errors = 0, n = 0, rows = 0, lastError = '';
  await Promise.all(Array.from({ length: C }, async () => {
    while (Date.now() < end) {
      const a = accounts[n++ % accounts.length], t = process.hrtime.bigint();
      try { rows += await read(mode, a); lat.push(Number(process.hrtime.bigint() - t) / 1e6); }
      catch (e) { errors++; lastError = String(e); }
    }
  }));
  lat.sort((x, y) => x - y);
  const r2 = x => Math.round(x * 100) / 100;
  console.log('RESULT ' + JSON.stringify({ mode, query: QUERY || 'open', replicas: N, concurrency: C, secs: SECS, reads: lat.length, errors, lastError,
    readsPerSec: Math.round(lat.length / SECS), p50: r2(pct(lat, 0.5)), p90: r2(pct(lat, 0.9)), p99: r2(pct(lat, 0.99)),
    max: r2(lat[lat.length - 1] ?? 0), rowsPerRead: r2(rows / Math.max(1, lat.length)) }));
}
