// PROOF FIXTURE ONLY — stands in for edge-proxy on a disposable local rig. It routes paths to the
// REAL generated services and NATS; it answers nothing itself except 404 for unrouted paths, so no
// response the console sees is fabricated here. Not shipped, not imported by the console.
import http from 'node:http';
import net from 'node:net';
const env = (k, d) => Number(process.env[k] ?? d);
const ROUTES = {
  '/position-service': env('POSITION_PORT', 26890),
  '/trade-processor': env('TRADE_PROCESSOR_PORT', 26891),
  '/account-service': env('ACCOUNT_PORT', 26892),
};
const NATS_WS = env('NATS_WS_PORT', 26880);
const route = (url) => Object.entries(ROUTES).find(([p]) => url === p || url.startsWith(p + '/'));
const server = http.createServer((req, res) => {
  const hit = route(req.url ?? '/');
  if (!hit) { res.writeHead(404, { 'content-type': 'application/json' }); return res.end('{"error":"not routed by fixture edge"}'); }
  const up = http.request({ host: '127.0.0.1', port: hit[1], path: req.url.slice(hit[0].length) || '/',
    method: req.method, headers: { ...req.headers, host: `127.0.0.1:${hit[1]}` } }, (r) => {
    res.writeHead(r.statusCode ?? 502, r.headers); r.pipe(res);
  });
  up.on('error', (e) => { res.writeHead(502); res.end(JSON.stringify({ error: String(e.message) })); });
  req.pipe(up);
});
server.on('upgrade', (req, socket, head) => {
  if (!(req.url ?? '').startsWith('/nats-ws')) return socket.destroy();
  const up = net.connect(NATS_WS, '127.0.0.1', () => {
    const lines = [`${req.method} / HTTP/1.1`, ...Object.entries(req.headers).map(([k, v]) => `${k}: ${v}`)];
    up.write(lines.join('\r\n') + '\r\n\r\n'); up.write(head); socket.pipe(up).pipe(socket);
  });
  up.on('error', () => socket.destroy()); socket.on('error', () => up.destroy());
});
server.listen(env('EDGE_PORT', 26870), '127.0.0.1', () => console.log('[fixture-edge] up'));
