// yu18-desk.mjs — the browser half of yu18-desk.sh: the hosted operator Desk (YU18 trader-workspace-
// combined, FR-CUI-01..16, 20, 21) driven in headless Chrome against a live side namespace. Elements are
// found by their visible labels and roles, the way a user finds them. Effects are read back by the
// wrapper (SQL, gateway); this file prints the facts it needs as `[fact] key=value` lines.
// Env: DESK_URL PASSWORD USER_A OUT GW_CAPS (JSON of GET /capabilities) [DESTRUCTIVE HOLD_CMD]
import fs from 'node:fs';
import { spawn } from 'node:child_process';
import { browser, sleep } from './lib-cdp.mjs';

const E = process.env;
const DESK = E.DESK_URL.replace(/\/$/, '');
const fact = (k, v) => console.log(`[fact] ${k}=${v}`);
const b = await browser({ out: E.OUT, port: 26853 });

// --- helpers: labels, buttons, text --------------------------------------------------------------
const H = `const lab=e=>(e.getAttribute('aria-label')||e.labels?.[0]?.innerText||'').trim();
  const field=l=>[...document.querySelectorAll('input,select,textarea')].find(e=>lab(e).startsWith(l));
  const btn=t=>[...document.querySelectorAll('button,a')].find(e=>(e.innerText||'').trim().startsWith(t));`;
const main = () => b.js(`return (document.querySelector('main')||document.body).innerText`);
const set = (l, v) => b.js(`${H} const e=field(${JSON.stringify(l)}); if(!e) throw new Error('no field ${l}');
  if(e.tagName==='SELECT'){const o=[...e.options].find(o=>o.value===${JSON.stringify(v)}||o.text===${JSON.stringify(v)}||o.text.startsWith(${JSON.stringify(v)}+' ')); if(!o) throw new Error('no option ${v} in ${l}'); e.value=o.value;}
  else e.value=${JSON.stringify(v)};
  e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); return e.value;`);
const get = l => b.js(`${H} const e=field(${JSON.stringify(l)}); return e ? (e.tagName==='SELECT' ? e.options[e.selectedIndex]?.text ?? '' : e.value) : null;`);
const disabled = t => b.js(`${H} const e=btn(${JSON.stringify(t)}); return e ? !!(e.disabled || e.getAttribute('aria-disabled')==='true') : null;`);
const click = t => b.js(`${H} const e=btn(${JSON.stringify(t)}); if(!e) throw new Error('no button ${t}'); e.click(); return 1;`);
const waitText = (pred, what, ms = 12000) => b.until(async () => { const t = await main(); return [pred(t), t]; }, what, ms);
const nav = async p => { await b.go(`${DESK}${p}`, 2500); };
const login = async (user, pw) => {
  await nav('/'); await b.until(async () => [await b.js(`${H} return !!field('Username')`)], 'login form', 15000);
  await set('Username', user); if (pw !== null) await set('Operator password', pw);
  await sleep(300);
};

// 1. Login (FR-CUI-01, FR-CUI-11): brand, the password is required by the server, a fresh workspace has no accounts.
await nav('/');
const title = await b.js(`return document.title`);
b.check(/TraderX Desk/.test(title), `FR-CUI-01: TraderX Desk brand (${title})`);
const loginText = await b.js(`return document.body.innerText`);
b.check(/public selectors/i.test(loginText), 'FR-CUI-01: the login says workspace names are public selectors (no authentication claim)');
await login(E.USER_A, null);
b.check(await disabled('Open workspace') === true, 'FR-CUI-11 (hosted): username alone cannot open a workspace');
await set('Operator password', 'definitely-not-the-password'); await sleep(200);
await click('Open workspace'); await sleep(2500);
b.check((await b.js(`return location.pathname`)).startsWith('/login'), 'FR-CUI-11: a wrong password is refused by the server (still on /login)');
await b.shot('01-wrong-password');
await login(E.USER_A, E.PASSWORD); await click('Open workspace');
await b.until(async () => [(await b.js(`return location.pathname`)).startsWith('/desk')], 'signed in to /desk', 15000);
const accts = await b.js(`${H} return [...(field('Account')?.options ?? [])].map(o=>o.text)`);
b.check(accts.length === 0, `FR-CUI-11: a new workspace starts with no trading accounts (${JSON.stringify(accts)})`);
await b.shot('02-new-workspace');

// FR-CUI-11: no password or hash in anything the browser downloads.
const bundles = await b.js(`return [...new Set([...document.scripts].map(s=>s.src).filter(Boolean).concat(performance.getEntriesByType('resource').map(e=>e.name).filter(n=>/\\.js(\\?|$)/.test(n))))]`);
let leaked = [];
for (const u of bundles) { const s = await (await fetch(u)).text(); for (const [k, v] of Object.entries({ password: E.PASSWORD, hash: E.PW_HASH, salt: E.PW_SALT })) if (v && s.includes(v)) leaked.push(`${k} in ${u}`); }
b.check(bundles.length > 0 && leaked.length === 0, `FR-CUI-11: none of ${bundles.length} JS bundles carries the password, its hash or salt ${leaked.join(', ')}`);

// 2. Navigation (FR-CUI-02, FR-CUI-09).
const tabs = await b.js(`return [...document.querySelectorAll('a[href^="/desk/"]')].filter(a=>a.closest('nav,header')).map(a=>a.innerText.trim()).filter(Boolean)`);
b.check(JSON.stringify(tabs.slice(0, 5)) === JSON.stringify(['Overview', 'Markets', 'Orders', 'Positions', 'Risk']), `FR-CUI-02: five primary tabs (${JSON.stringify(tabs)})`);
await click('☰ More'); await sleep(600);
const more = await b.js(`return [...document.querySelectorAll('nav[aria-label="More tools"] a')].map(a=>(a.querySelector('b')?.textContent ?? a.textContent).trim())`);
b.check(more.length > 3 && /^Demo console/.test(more.at(-1)), `FR-CUI-09: Demo console is last in More (${JSON.stringify(more)})`);
const titles = more;
b.note('More menu', titles);
await b.js(`document.body.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true})); document.body.click(); return 1`);
await click(E.USER_A); await sleep(500);
b.check(/Switch to Admin workspace/.test(await b.js(`return document.body.innerText`)), 'FR-CUI-02: a separate Admin workspace is offered to the admin session');
await nav('/desk/overview');

// 3. Risk (FR-CUI-05): unavailable for the account; reference examples labelled as not its valuation.
await nav('/desk/risk');
let t = await waitText(x => /No portfolio risk result is available/.test(x), 'risk unavailable');
b.check(/do not value your holdings/.test(t) && /Reference examples/.test(t) && /[Ss]ynthetic/.test(t), 'FR-CUI-05: portfolio risk unavailable; reference examples separate and synthetic');
await b.shot('03-risk');

// 4. Markets sort (FR-CUI-14).
await nav('/desk/markets');
await b.until(async () => [await b.js(`return document.querySelectorAll('tbody tr').length`) > 50], 'markets rows', 20000);
await b.until(async () => [await b.js(`return [...document.querySelectorAll('tbody tr')].filter(r=>!/no price/.test(r.innerText)).length`) >= 3], 'three priced rows', 60000);
const col = name => b.js(`const ths=[...document.querySelectorAll('thead th')]; const i=ths.findIndex(h=>h.innerText.trim().startsWith(${JSON.stringify(name)}));
  return {sort: ths[i].getAttribute('aria-sort'), vals: [...document.querySelectorAll('tbody tr')].map(r=>r.cells[i].innerText.trim())}`);
const clickHead = name => b.js(`const h=[...document.querySelectorAll('thead th')].find(h=>h.innerText.trim().startsWith(${JSON.stringify(name)})); (h.querySelector('button')||h).click(); return 1`);
const numericOk = (vals, dir) => {
  const nums = vals.map(v => (/^[-\d.,]+$/.test(v) ? Number(v.replace(/,/g, '')) : null));
  const firstMissing = nums.findIndex(n => n === null);
  const present = firstMissing < 0 ? nums : nums.slice(0, firstMissing);
  const missingLast = firstMissing < 0 || nums.slice(firstMissing).every(n => n === null);
  const ordered = present.every((n, i) => i === 0 || (dir > 0 ? present[i - 1] <= n : present[i - 1] >= n));
  return { ok: missingLast && ordered && present.length >= 3, present: present.length, missingLast, ordered };
};
await clickHead('Last'); await sleep(500);
let c1 = await col('Last'); const r1 = numericOk(c1.vals, c1.sort === 'descending' ? -1 : 1);
await clickHead('Last'); await sleep(500);
let c2 = await col('Last'); const r2 = numericOk(c2.vals, c2.sort === 'descending' ? -1 : 1);
b.check(['ascending', 'descending'].includes(c1.sort) && c2.sort !== c1.sort && ['ascending', 'descending'].includes(c2.sort), `FR-CUI-14: aria-sort reports the direction and a second click reverses it (${c1.sort} -> ${c2.sort})`);
b.check(r1.ok && r2.ok, `FR-CUI-14: numeric order, missing values last, both directions (${JSON.stringify([r1, r2])})`);
const inst = await col('Instrument');
b.check(inst.sort === 'none', 'FR-CUI-14: only the sorted column reports aria-sort');
for (const name of ['Watch', 'Instrument', 'Unit', 'Source', 'Age']) { await clickHead(name); await sleep(300); const c = await col(name); b.check(['ascending', 'descending'].includes(c.sort), `FR-CUI-14: column ${name} sorts on click (${c.sort})`); }
await b.shot('04-markets-sorted');

// 5. Add account (FR-CUI-12): the wrapper checks SQL and admission from the id printed here.
await nav('/desk/overview');
await b.until(async () => [await b.js(`${H} return !!btn('+ Add account')`)], 'the + Add account button', 20000);
await click('+ Add account');
await b.until(async () => [await b.js(`${H} return !!field('Account name')`)], 'the Account name field', 10000);
await set('Account name', `PA desk ${E.USER_A}`); await sleep(200);
await click('Create account');
const newAcct = await b.until(async () => { const o = await b.js(`${H} return [...(field('Account')?.options ?? [])].map(o=>o.text)`); return [o.length === 1 && o[0], JSON.stringify(o)]; }, 'one account after + Add account', 30000);
const acctId = (newAcct.match(/\((\d+)\)/) ?? newAcct.match(/(\d{3,})/) ?? [])[1];
b.check(!!acctId, `FR-CUI-12: the workspace now lists its new account (${newAcct})`);
fact('account', acctId);
await b.shot('05-account-added');

// FR-CUI-21: account reads require membership. Replay the desk's own account read for a foreign account.
await nav('/desk/positions'); await sleep(3000);
const reads = await b.js(`return [...new Set(performance.getEntriesByType('resource').map(e=>e.name).filter(n=>n.includes(${JSON.stringify(acctId)}) && !/\\.js/.test(n)))]`);
b.note('desk reads naming the account', reads);
b.check(reads.length > 0, 'the desk reads its account through a URL naming it (needed for the membership check)');
const foreign = await b.js(`const out=[]; for (const u of ${JSON.stringify(reads)}) { const f=u.split(${JSON.stringify(acctId)}).join(${JSON.stringify(E.FOREIGN_ACCT)}); const r=await fetch(f); out.push([f.replace(location.origin,''), r.status]); } return out`);
const own = await b.js(`const out=[]; for (const u of ${JSON.stringify(reads)}) { const r=await fetch(u); out.push(r.status); } return out`);
// Every hosted session holds the admin role (the operator password is required), and FR-CUI-21's membership
// rule is for non-admin local-mode sessions, so on this tier it can only be observed, not asserted.
b.note('FR-CUI-21 (local-mode, non-admin rule; hosted sessions are admin): own reads / foreign reads', { own, foreign });
const wsUrl = b.events.filter(e => e.method === 'Network.webSocketCreated').map(e => e.params.url).find(Boolean);
fact('ws', wsUrl ?? '');

// 6. Ticket (FR-CUI-15, -13, -04, -16).
await nav('/desk/orders');
await b.until(async () => [await b.js(`${H} return !!field('Order type')`)], 'order ticket', 15000);
const types = await b.js(`${H} return [...field('Order type').options].map(o=>({t:o.text,dis:o.disabled}))`);
const caps = JSON.parse(E.GW_CAPS || '{}');
const advertised = JSON.stringify(caps).match(/MARKET|LIMIT|STOP_LIMIT|STOP|ICEBERG|PEG\w*|TRAILING\w*/g) ?? [];
const enabled = types.filter(o => !o.dis).map(o => o.t);
fact('ticket-types', enabled.join('|'));
b.check(new Set(advertised).size >= 7 ? enabled.length >= 7 : enabled.length <= 1, `FR-CUI-15: typed orders enabled iff the gateway advertises all seven (gateway ${new Set(advertised).size}, ticket ${enabled.length}: ${enabled.join(', ')})`);

const priceOf = async tk => { await set('Instrument', tk); await sleep(1500); return b.js(`return document.querySelector('main').innerText`); };
await set('Order type', 'Limit');
await priceOf(E.FRESH_TICKER);
await b.until(async () => [await disabled('Price at market') === false], `a fresh mark for ${E.FRESH_TICKER} enables Price at market`, 60000);
await set('Quantity', '3');
await click('Price at market'); await sleep(500);
const lp = await b.js(`return document.querySelector('main input[type=number]:not([aria-label^="Quantity"])') && [...document.querySelectorAll('main input[type=number]')].map(e=>e.value)`);
const priceField = (lp ?? []).find((v, i) => i > 0 && v !== '');
fact('autofill', priceField);
b.check(priceField && Number(priceField) > 0 && await get('Order type') === 'Limit', `FR-CUI-13: Price at market copies the mark into the limit field and keeps the order type (${priceField}, ${await get('Order type')})`);
await set('Instrument', E.STALE_TICKER); await sleep(1500);
const qtyAfter = await get('Quantity (shares)') ?? await get('Quantity');
const pxAfter = await b.js(`return [...document.querySelectorAll('main input[type=number]')].slice(1).map(e=>e.value).join('')`);
b.check(qtyAfter !== '3' && pxAfter === '', `FR-CUI-04: changing the instrument clears quantity and price (qty '${qtyAfter}', price '${pxAfter}')`);
b.check(await disabled('Price at market') === true, `FR-CUI-13: no fresh mark for ${E.STALE_TICKER}: Price at market cannot autofill`);

// Prices from the fresh mark: a passive limit inside the collar, and one far outside it (PRICE_COLLAR).
const tick = v => (Math.round(v * 100) / 100).toFixed(2);
E.ACCEPT_PX = tick(Number(priceField) * 0.97);

// FR-CUI-12 admission at the gateway: the new account trades. One resting REST Buy 1 (a bearer bound to the
// account), which also gives FR-CUI-17 and FR-CUI-20 below a real working row to look at.
const tok = (await (await fetch(`${E.TP}/auth/dev-token`, { method: 'POST', headers: { 'content-type': 'application/json', 'X-Auth-Master-Secret': E.MASTER },
  body: JSON.stringify({ subject: 'yu18-desk', accounts: [Number(acctId)], admin: false, ttlSeconds: 1800 }) })).text()).trim();
const bearer = tok.startsWith('{') ? (j => j.token ?? j.accessToken ?? j.jwt)(JSON.parse(tok)) : tok.replace(/^"|"$/g, '');   // a bare JWT or a JSON wrapper
const ro = await fetch(`${E.GW}/orders`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${bearer}` },
  body: JSON.stringify({ accountId: Number(acctId), ticker: E.FRESH_TICKER, side: 'Buy', quantity: 1, limitPrice: Number(E.ACCEPT_PX) }) });
const rb = await ro.text();
b.check(ro.status === 200 && /"kind":\s*1/.test(rb), `FR-CUI-12: the Desk-created account ${acctId} is admitted at the gateway (Buy 1 ${E.FRESH_TICKER} @ ${E.ACCEPT_PX}: ${ro.status} ${rb.slice(0, 80)})`);
fact('rest-ref', JSON.parse(rb).orderRef);

// Submit (FR-CUI-16/17/19). The Desk preflights the run before sending (FR-CUI-17: an ACTIVE registered
// descriptor), so on an unmanaged run the server refuses with nothing sent; on a managed one it sends.
const vis = () => b.js(`const c=document.querySelector('main').cloneNode(true); c.querySelectorAll('select,option').forEach(e=>e.remove()); return c.innerText`);
const setPx = v => b.js(`const e=[...document.querySelectorAll('main input[type=number]')][1]; e.value=${JSON.stringify(v)}; e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); return e.value`);
const nums = () => b.js(`return [...document.querySelectorAll('main input[type=number]')].map(e=>e.value)`);
await set('Instrument', E.FRESH_TICKER); await sleep(1200); await set('Order type', 'Limit');
await set('Quantity', '2'); await setPx(E.ACCEPT_PX);
fact('probe-px', E.ACCEPT_PX);
const sent0 = b.events.length;
await click('Submit order');
t = await b.until(async () => { const v = await vis(); return [/accepted|refused|rejected|unknown|not sent|Nothing sent|could not be confirmed/i.test(v.split('Submit order')[1] ?? ''), v]; }, 'a submit outcome', 20000);
const post = b.events.slice(sent0).find(e => e.method === 'Network.requestWillBeSent' && e.params.request.method === 'POST' && /orders/.test(e.params.request.url));
const resp = post && b.events.find(e => e.method === 'Network.responseReceived' && e.params.requestId === post.params.requestId);
const rbody = post ? (await b.cdp('Network.getResponseBody', { requestId: post.params.requestId })).result?.body ?? '' : '';
const posts = b.events.slice(sent0).filter(e => e.method === 'Network.requestWillBeSent' && e.params.request.method === 'POST' && /orders/.test(e.params.request.url)).length;
b.note('submit', { url: post?.params.request.url.replace(/^https?:\/\/[^/]+/, ''), status: resp?.params.response.status, body: rbody.slice(0, 200) });
if (resp && resp.params.response.status !== 200 && /Nothing sent|could not be confirmed/i.test(rbody)) {
  fact('submit', 'refused-unmanaged');
  const q = await nums();
  b.check(q[0] === '2' && q[1] === E.ACCEPT_PX, `FR-CUI-16: a refused submit keeps the draft (${JSON.stringify(q)})`);
  await sleep(3000);
  b.check(posts === 1, `FR-CUI-16/19: the refused submit is not retried (${posts} POST)`);
  const outcome = (t.split('This session')[1] ?? t).trim().split('\n')[0];
  b.soft(!/Outcome unknown/i.test(outcome), `FR-CUI-19: a server refusal before sending ("${rbody.slice(0, 80)}") is shown as refused, not as an unknown outcome`, { shown: outcome });
  b.note('FR-CUI-16 acceptance path / FR-CUI-17-19 mutations', 'TIER GAP: the Desk sends orders only on a managed run (registered ACTIVE descriptor); this run is legacy-unknown');
  await b.shot('06-submit-refused-unmanaged');
} else {
  fact('submit', 'sent');
  t = await waitText(x => /accepted/i.test(x), 'an acceptance receipt', 20000);
  const qA = await nums();
  b.check((qA[0] === '' || qA[0] === null) && qA[1] === '', `FR-CUI-16: after acceptance quantity and price are cleared (${JSON.stringify(qA)})`);
  b.check(await disabled('Submit order') === true, 'FR-CUI-16: repeat submission is blocked after clearing');
  fact('accepted-px', E.ACCEPT_PX);
  await b.shot('07-accepted-cleared');
}

// FR-CUI-17 on an unmanaged run: the working order offers no cancel/replace.
await b.until(async () => [await b.js(`return [...document.querySelectorAll('main tr')].some(r=>r.innerText.includes(${JSON.stringify(E.FRESH_TICKER)}))`)], 'the REST order as a working row', 30000, { soft: true });
const rowActs = await b.js(`return [...document.querySelectorAll('main tr')].filter(r=>r.innerText.includes(${JSON.stringify(E.FRESH_TICKER)})).map(r=>[...r.querySelectorAll('button')].filter(x=>!x.disabled).map(x=>x.innerText.trim()))`);
b.note('working-row actions', rowActs);
if (rowActs.length) b.check(rowActs.flat().filter(x => /cancel|replace|amend/i.test(x)).length === 0, `FR-CUI-17: unmanaged run: no enabled cancel/replace on the working order (${JSON.stringify(rowActs)})`);
else b.check(/managed run is required to change existing orders/i.test(await vis()), 'FR-CUI-17: unmanaged run: the Orders page states that changing existing orders needs a managed run (no working rows to inspect)');

// 7. Phone width (acceptance): no horizontal page overflow at 390 px on the orders page.
await b.viewport(390, 844, true); await nav('/desk/orders'); await sleep(1500);
const sw = await b.js(`return document.documentElement.scrollWidth`);
b.check(sw <= 390, `390 px orders page: no horizontal page overflow (scrollWidth ${sw})`);
await b.shot('08-mobile-orders');
await b.viewport(1280, 1000);

// 8. FR-CUI-20 (DESTRUCTIVE): a hung account read clears rows and disables actions.
if (E.DESTRUCTIVE === '1' && E.HOLD_CMD) {
  await nav('/desk/overview');
  await b.until(async () => { const v = await vis(); return [v.includes(E.FRESH_TICKER), v]; }, 'the working order on the overview before the hang', 30000);
  const hold = spawn('bash', ['-c', E.HOLD_CMD], { stdio: 'ignore' }); b.onExit(() => hold.kill());
  try {
    t = await b.until(async () => { const v = await vis(); return [/timed out|Could not load|not shown/i.test(v), v]; }, 'a deadline refusal while reads hang', 45000);
    b.check(!t.includes(E.FRESH_TICKER), `FR-CUI-20: a hung read clears the account rows (the ${E.FRESH_TICKER} working order is gone)`);
    await b.shot('09-hung-read');
  } finally { hold.kill(); }
  await b.until(async () => { const v = await vis(); return [v.includes(E.FRESH_TICKER), v]; }, 'the working order back after the hang', 90000);
  b.check(true, 'FR-CUI-20: the rows come back once a read completes');
}
b.done();
