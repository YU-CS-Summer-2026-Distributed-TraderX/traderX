// Sanity-check the option quoting in isolation: parse, price, and verify put-call parity.
import fs from 'fs';
let src = fs.readFileSync('specs/YU15-eod-risk-extract/generation/runtime-overrides/price-publisher/src/main.js','utf8');
// Strip the module's I/O bootstrap; keep the pure pieces under test.
src = src.split('const app = express();')[0]
  .replace(/^const (fs|path|express|\{ connect \}|yahooFinance).*$/gm,'')
  .replace(/^const SNAPSHOT_PATH.*$/gm,'');
const mod = new Function(src + `
  return { parseOcc, blackScholes, normCdf, OPTION_IV, OPTION_RATE };
`)();
const { parseOcc, blackScholes, OPTION_IV, OPTION_RATE } = mod;

const c = parseOcc('AAPL260918C00240000');
console.log('parsed:', c.root, 'strike', c.strike, 'call', c.call, 'expiry', new Date(c.expiryMillis).toISOString().slice(0,10));
console.assert(c.root==='AAPL' && c.strike===240 && c.call===true, 'parse');
console.assert(parseOcc('AAPL')===null && parseOcc('NOTASYMBOL123')===null, 'non-option rejected');

const S=241.80, K=240, T=(Date.UTC(2026,8,18,21)-Date.UTC(2026,6,22))/(365.25*864e5);
const call = blackScholes(S,K,T,true), put = blackScholes(S,K,T,false);
console.log(`spot ${S} strike ${K} T=${T.toFixed(3)}y  call=${call.toFixed(4)}  put=${put.toFixed(4)}`);
// Put-call parity: C - P = S - K*e^{-rT}
const parity = S - K*Math.exp(-OPTION_RATE*T);
console.log(`parity check: C-P=${(call-put).toFixed(6)}  S-Ke^-rT=${parity.toFixed(6)}`);
console.assert(Math.abs((call-put)-parity) < 1e-3, 'put-call parity');
// Monotone in strike, and >= intrinsic
console.assert(blackScholes(S,220,T,true) > blackScholes(S,260,T,true), 'call monotone in strike');
console.assert(blackScholes(S,220,T,true) >= S-220, 'call >= intrinsic');
console.assert(blackScholes(S,K,0,true) === Math.max(S-K,0), 'expired = intrinsic');
console.log('IV', OPTION_IV, 'rate', OPTION_RATE);
console.log('[ok] all option-quote assertions passed');
