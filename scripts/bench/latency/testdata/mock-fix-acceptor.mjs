// A scripted FIX 4.4 acceptor for test_fix_latency_probe.py. Answers Logon and Logout; acks every NewOrderSingle
// New after --ack-ms; an order that meets a resting one of the other side (one book, every session) fills both after
// --fill-ms from its arrival. --filled-ack: the crossing order gets no New, only its Filled (the gateway's answer to an
// aggressor filled on arrival, FR-FFR-01). --drop-after N closes the connection that sent the Nth order.
// Prints "PORT <n>" once listening.
import net from 'node:net';

const argv = process.argv.slice(2);
const flag = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const ackMs = Number(flag('--ack-ms', 0)), fillMs = Number(flag('--fill-ms', 0)), dropAfter = Number(flag('--drop-after', 0));
const filledAck = argv.includes('--filled-ack');
const SOH = '\x01';
const frame = (fields) => {
  const body = fields.map(([k, v]) => `${k}=${v}`).join(SOH) + SOH;
  const pre = `8=FIX.4.4${SOH}9=${Buffer.byteLength(body)}${SOH}`;
  return `${pre}${body}10=${String([...Buffer.from(pre + body)].reduce((a, c) => (a + c) & 0xff, 0)).padStart(3, '0')}${SOH}`;
};
const book = {1: [], 2: []};   // side -> resting [conn, ClOrdID]
let orders = 0;
const server = net.createServer((sock) => {
  let seq = 1, buf = '';
  sock.setNoDelay(true);
  const reply = (type, fields) => { if (!sock.destroyed) sock.write(frame([['35', type], ['49', 'TRADERX'], ['56', sock.comp], ['34', seq++], ...fields])); };
  sock.reply = reply;
  sock.on('error', () => {});
  sock.on('data', (chunk) => {
    buf += chunk.toString('latin1');
    for (let e; (e = buf.indexOf(`${SOH}10=`)) >= 0;) {
      const cut = buf.indexOf(SOH, e + 1) + 1; if (cut <= 0) break;
      const f = Object.fromEntries(buf.slice(0, cut).split(SOH).filter(Boolean).map((kv) => kv.split(/=(.*)/s)));
      buf = buf.slice(cut);
      if (f['35'] === 'A') { sock.comp = f['49']; reply('A', [['98', 0], ['108', 30]]); }
      if (f['35'] === '5') { reply('5', []); sock.end(); }
      if (f['35'] !== 'D') continue;
      if (dropAfter && ++orders === dropAfter) { sock.destroy(); return; }
      const cl = f['11'], side = Number(f['54']), other = book[3 - side].shift();
      const er = (s, id, st) => s.reply('8', [['11', id], ['17', `e${seq}`], ['39', st], ['150', st === '2' ? 'F' : '0']]);
      if (!other) { book[side].push([sock, cl]); setTimeout(() => er(sock, cl, '0'), ackMs); continue; }
      if (!filledAck) setTimeout(() => er(sock, cl, '0'), ackMs);
      setTimeout(() => { er(sock, cl, '2'); er(other[0], other[1], '2'); }, fillMs);
    }
  });
});
server.listen(0, '127.0.0.1', () => console.log(`PORT ${server.address().port}`));
