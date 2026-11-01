// FR-DA10: the Kdb tab's live q against the demo history (scripts/demo-kdb/demo-hdb.q, KDB_Q_URL). Presets run for
// anyone; free-form q only for a signed-in administrator. demo-hdb.q itself evaluates under reval (read-only).
export const KDB_PRESETS = [
  { id: 'count', title: 'How many trades', q: 'select trades:count i, days:count distinct date from trades' },
  { id: 'by-sym', title: 'Volume and VWAP by symbol, whole year',
    q: 'select trades:count i, volume:sum qty, vwap:qty wavg px by sym from trades' },
  { id: 'daily', title: 'Daily volume and notional, last two weeks',
    q: 'select trades:count i, volume:sum qty, notional:sum qty*px by date from trades where date>=last[date]-14' },
  { id: 'bars', title: '5-minute AAPL bars, last day',
    q: 'select open:first px, high:max px, low:min px, close:last px, volume:sum qty by 5 xbar time.minute from trades where date=last date, sym=`AAPL' },
  { id: 'net', title: 'Net position by symbol (buys minus sells)',
    q: 'select net:sum ?[side=`B;qty;neg qty], trades:count i by sym from trades' },
  { id: 'largest', title: 'Ten largest trades, last day',
    q: '10 sublist `notional xdesc select time, sym, side, qty, px, notional:qty*px from trades where date=last date' },
];

/** The q text a request may run, or an error the server answers with. */
export function kdbQueryText(body, admin) {
  if (body && typeof body.preset === 'string') {
    const p = KDB_PRESETS.find(x => x.id === body.preset);
    return p ? { q: p.q } : { status: 404, error: `no preset ${body.preset}` };
  }
  if (!body || typeof body.q !== 'string' || !body.q.trim()) return { status: 400, error: 'want {"preset":"id"} or {"q":"..."}' };
  if (body.q.length > 2000) return { status: 400, error: 'q is limited to 2000 characters' };
  if (!admin) return { status: 401, code: 'admin_auth_required', error: 'sign in as an administrator to run your own q' };
  return { q: body.q };
}
