import { runQ } from './qeval';

/**
 * The evaluator's whole value is that it refuses what it does not understand, so the refusals are
 * tested as carefully as the answers. A wrong number from here would be laundered into every other
 * claim the kdb page makes.
 */
describe('runQ', () => {
  const txTrade = [
    { seq: 1, epoch: 1, sym: 'IBM', account: 22214, side: 'B', qty: 100, px: 10 },
    { seq: 2, epoch: 1, sym: 'IBM', account: 22214, side: 'S', qty: 300, px: 20 },
    { seq: 3, epoch: 1, sym: 'AAPL', account: 42422, side: 'B', qty: 50, px: 5 },
  ];
  const tables = { txTrade, txOrder: [] as Record<string, string | number>[] };

  it('runs the real .tx.fills definition, wrapper and all', () => {
    const r = runQ('.tx.fills:{[] 0!select execs:count i, volume:sum qty, '
      + 'vwap:(sum px*qty)%sum qty, first_px:first px, last_px:last px by sym from txTrade}', tables);
    expect(r.columns).toEqual(['sym', 'execs', 'volume', 'vwap', 'first_px', 'last_px']);
    const ibm = r.rows.find(row => row[0] === 'IBM')!;
    // (10*100 + 20*300) / 400 = 17.5 — weighted by qty, not a mean of the prices (15).
    expect(ibm).toEqual(['IBM', 2, 400, 17.5, 10, 20]);
  });

  it('groups on multi-character values without splitting them', () => {
    const r = runQ('select n:count i by sym from txTrade', tables);
    expect(r.rows.map(row => row[0]).sort()).toEqual(['AAPL', 'IBM']);
  });

  it('groups on more than one column', () => {
    const r = runQ('select n:count i by sym,side from txTrade', tables);
    expect(r.columns).toEqual(['sym', 'side', 'n']);
    expect(r.rows.length).toBe(3);
  });

  it('filters with =, comparison and like', () => {
    expect(runQ('select n:count i from txTrade where sym=`IBM', tables).rows[0][0]).toBe(2);
    expect(runQ('select n:count i from txTrade where qty>90', tables).rows[0][0]).toBe(2);
    expect(runQ('select n:count i from txTrade where sym like "A*"', tables).rows[0][0]).toBe(1);
  });

  it('aggregates without a by clause', () => {
    const r = runQ('select total:sum qty from txTrade', tables);
    expect(r.rows).toEqual([[450]]);
  });

  it('refuses a statement it does not understand rather than guessing', () => {
    expect(() => runQ('update foo:1 from txTrade', tables)).toThrowError(/only .select/);
    expect(() => runQ('select n:count i from txNope', tables)).toThrowError(/unknown table/);
    expect(() => runQ('select n:count i by nope from txTrade', tables)).toThrowError(/unknown column/);
    expect(() => runQ('select n:med qty from txTrade', tables)).toThrowError(/unsupported column expression/);
    expect(() => runQ('select n:count i from txTrade where qty ~ 1', tables)).toThrowError(/unsupported condition/);
  });
});
