// ADR-096 (FR-PRG-04): the bring-up smoke order. Run inside web-front-end-console, which holds the FIX session
// password: `kubectl exec -i deploy/web-front-end-console -c console -- node - [account] [ticker] < fix-smoke.mjs`.
// One FIX session to the gateways: Logon, a resting Buy 1 two percent under the mark (inside the price collar),
// its cancel, Logout. Prints "NEW <orderRef>" and "CANCELED <orderRef>"; the caller then checks the read model.
// The password is never printed: 554 is redacted from every line.
import net from 'node:net';

const [account = '42422', ticker = 'MSFT'] = process.argv.slice(2);
const pw = process.env.FIX_SESSION_PASSWORD;
if (!pw) { console.log('FAIL no FIX_SESSION_PASSWORD in this pod'); process.exit(2); }
const quote = await (await fetch(`${process.env.PRICE_URL || 'http://price-publisher:18100'}/prices/${ticker}`)).json();
const px = (Math.floor(quote.price * 0.98 * 100) / 100).toFixed(2);
const cl = `rig-${Date.now()}`;

const SOH = '\x01';
const t = () => new Date().toISOString().replace(/-/g, '').replace('T', '-').slice(0, 21);   // SendingTime(52): YYYYMMDD-HH:MM:SS.sss
const frame = (fields) => {
  const body = fields.map(([k, v]) => `${k}=${v}`).join(SOH) + SOH;
  const head = `8=FIX.4.4${SOH}9=${Buffer.byteLength(body)}${SOH}`;
  const sum = [...Buffer.from(head + body)].reduce((a, c) => (a + c) & 0xff, 0);
  return `${head}${body}10=${String(sum).padStart(3, '0')}${SOH}`;
};
const show = (m) => m.replace(/\x01554=[^\x01]*/g, '|554=********').replace(/\x01/g, '|');
let seq = 1, buf = '', ref = null, canceled = false;
const sock = net.connect(Number(process.env.FIX_PORT || 18130), process.env.FIX_HOST || 'order-matcher-gw-fix');
const send = (type, fields) => {
  const m = frame([['35', type], ['49', 'CLIENT1'], ['56', 'TRADERX'], ['34', seq++], ['52', t()], ...fields]);
  console.log('SENT', show(m)); sock.write(m);
};
const done = (code, why) => { console.log(why); sock.destroy(); process.exit(code); };
setTimeout(() => done(1, 'FAIL no answer within 15 s'), 15_000);
sock.on('connect', () => send('A', [['98', 0], ['108', 30], ['141', 'Y'], ['554', pw]]));
sock.on('error', (e) => done(1, `FAIL socket ${e.message}`));
sock.on('data', (chunk) => {
  buf += chunk.toString('latin1');
  for (let e; (e = buf.indexOf(`${SOH}10=`)) >= 0;) {
    const cut = buf.indexOf(SOH, e + 1) + 1; if (cut <= 0) break;
    const m = buf.slice(0, cut); buf = buf.slice(cut);
    console.log('RECV', show(m));
    const f = Object.fromEntries(m.split(SOH).filter(Boolean).map((kv) => [kv.slice(0, kv.indexOf('=')), kv.slice(kv.indexOf('=') + 1)]));
    if (f['35'] === 'A') {
      send('D', [['11', cl], ['1', account], ['55', ticker], ['54', 1], ['38', 1], ['40', 2], ['44', px], ['21', 1], ['60', t()]]);
    } else if (f['35'] === '8' && f['11'] === cl && f['39'] === '0') {
      ref = f['37'].replace(/^ord-/, '');
      console.log(`NEW ${ref} ${ticker} @ ${px}`);
      send('F', [['41', cl], ['11', `${cl}-c`], ['1', account], ['55', ticker], ['54', 1], ['60', t()]]);
    } else if (f['35'] === '8' && f['11'] === `${cl}-c` && f['39'] === '4') {
      console.log(`CANCELED ${ref}`); canceled = true;
      send('5', []);
      setTimeout(() => done(0, 'OK'), 300);
    } else if (f['35'] === '5' && canceled) {
      done(0, 'OK');   // the gateway's answer to our Logout
    } else if (f['35'] === '8' && f['39'] === '8' || f['35'] === '9' || f['35'] === '5' || f['35'] === '3') {
      done(1, 'FAIL refused: ' + show(m));
    }
  }
});
