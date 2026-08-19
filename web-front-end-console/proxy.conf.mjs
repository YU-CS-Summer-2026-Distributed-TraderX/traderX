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

function portForward() {
  const pf = spawn('kubectl', ['--context', CTX, '-n', NS, 'port-forward', `svc/edge-proxy`, `${PORT}:8080`],
    { stdio: 'ignore' });
  pf.on('exit', () => setTimeout(portForward, 2000));
  process.on('exit', () => { pf.removeAllListeners('exit'); pf.kill(); });
}
try {
  execSync(`nc -z localhost ${PORT}`, { stdio: 'ignore' });
  console.log(`[proxy] localhost:${PORT} already forwarded — reusing it`);
} catch {
  portForward();
  console.log(`[proxy] port-forwarding svc/edge-proxy via ${CTX}`);
}

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
];
