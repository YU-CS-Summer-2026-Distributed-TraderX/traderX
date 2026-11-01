import { nextChanges } from './gateway-scale';

describe('FR-DA09 gateway scale card', () => {
  it('logs a change only when the ready count moves, newest first', () => {
    let log = nextChanges([], null, 3, 1);
    expect(log).toEqual([]);
    log = nextChanges(log, 3, 3, 2);
    expect(log).toEqual([]);
    log = nextChanges(log, 3, 4, 3);
    log = nextChanges(log, 4, 6, 4);
    expect(log.map(c => `${c.from}->${c.to}`)).toEqual(['4->6', '3->4']);
  });
});
