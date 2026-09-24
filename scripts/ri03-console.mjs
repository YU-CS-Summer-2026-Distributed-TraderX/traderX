// Isolated read-only loopback host for the existing built Risk UI and existing EOD route.
// No cluster/kubectl/cloud proxy. No synthetic HTTP response replacing coordinator evidence.
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { readEodJobs } from '../web-front-end-console/eod-jobs.mjs';
import { readRiskDemo } from '../web-front-end-console/risk-demo.mjs';
const root = path.resolve(process.env.STATIC_ROOT || 'web-front-end-console/dist/web-front-end-console/browser');
const port = Number(process.env.PORT || 18304);
const json = (res, status, body) => { res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(body)); };
const server = http.createServer(async (req, res) => {
  if (req.method !== 'GET') return json(res, 405, { code: 'READ_ONLY' });
  const url = new URL(req.url, 'http://127.0.0.1');
  if (url.pathname === '/eod/jobs') { const r = await readEodJobs(); return json(res, r.status, r.body); }
  if (url.pathname === '/risk/demo') { const r = await readRiskDemo(); return json(res, r.status, r.body); }
  // These unrelated existing panels are explicitly unconfigured in this local rig.
  if (url.pathname.startsWith('/risk/treasury-demo')) return json(res, 503, { code: 'NOT_CONFIGURED' });
  if (url.pathname.startsWith('/auth/') || url.pathname.startsWith('/admin/') || url.pathname.startsWith('/cluster/')) return json(res, 503, { code: 'NOT_CONFIGURED' });
  if (!['/', '/risk'].includes(url.pathname) && !path.extname(url.pathname)) return json(res, 503, { code: 'NOT_CONFIGURED' });
  const file = path.resolve(root, '.' + decodeURIComponent(url.pathname));
  if (!file.startsWith(root + path.sep)) return json(res, 404, { code: 'NOT_FOUND' });
  try {
    const target = path.extname(file) ? file : path.join(root, 'index.html');
    const data = await fs.readFile(target);
    const type = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' }[path.extname(target)] || 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' }); res.end(data);
  } catch { json(res, 404, { code: 'NOT_FOUND' }); }
});
server.listen(port, '127.0.0.1', () => console.log(`RI-03 existing Risk UI: http://127.0.0.1:${port}/risk`));
