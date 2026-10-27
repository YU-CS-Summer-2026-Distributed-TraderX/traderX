import { CapOrder, mergeOrders } from './kdb-panel';

/** An aggressor that fills on arrival emits NEW and FILLED at ONE consensus seq. */
describe('mergeOrders', () => {
  const ev = (seq: number, ref: number, status: string, remaining: number): CapOrder => ({
    seq, epoch: 1, ref, account: 22214, sym: 'IBM', side: 'Buy', qty: 5, remaining,
    limitPx: 10, status, updatedMs: 1000,
  });

  it('keeps a fill that shares its seq with the NEW, and orders it last', () => {
    const member = { orders: [ev(9, 9, 'FILLED', 0), ev(9, 9, 'NEW', 5)] };
    const out = mergeOrders([member, member]);           // two members carry the same rows
    expect(out.map(o => o.status)).toEqual(['NEW', 'FILLED']);
  });
});
