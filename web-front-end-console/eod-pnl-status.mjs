export function pnlStatus(rows, summary) {
  const halted = Number(/halted=(\d+)/.exec(summary)?.[1] ?? NaN);
  if (Number.isFinite(halted) && halted > 0) {
    return {state: rows > 0 ? 'partial' : 'halted', rows, halted,
      detail: `${rows} position PnL row(s); ${halted} account(s) halted on missing closing prices. Account coverage is incomplete.`};
  }
  if (rows > 0) return {state: Number.isFinite(halted) ? 'ok' : 'unverified', rows,
    halted: Number.isFinite(halted) ? halted : null,
    detail: `${rows} position PnL row(s)` + (Number.isFinite(halted) ? '' : '; account coverage could not be verified.')};
  if (summary) return {state: 'ok', rows: 0, halted: 0, detail: 'ran with nothing to mark'};
  return {state: 'pending', rows: 0, detail: 'position-service has not marked this session yet'};
}
