import { buildControlSnapshot } from './control-snapshot';
import { Stock } from './stock.model';

describe('buildControlSnapshot', () => {
  const stocks: Stock[] = [
    { ticker: 'MSFT', companyName: 'Microsoft Corporation' },
    { ticker: 'IBM', companyName: 'International Business Machines' },
  ];

  it('sorts records by ticker and reports a matching count', () => {
    const snapshot = buildControlSnapshot(1, 5, stocks);
    expect(snapshot.records.map((s) => s.ticker)).toEqual(['IBM', 'MSFT']);
    expect(snapshot.count).toBe(2);
    expect(snapshot.sourceEpoch).toBe(1);
    expect(snapshot.watermark).toBe(5);
  });

  it('produces a stable checksum for the same record set regardless of input order', () => {
    const first = buildControlSnapshot(1, 5, stocks);
    const second = buildControlSnapshot(1, 5, [...stocks].reverse());
    expect(first.checksum).toEqual(second.checksum);
    expect(first.checksum).toMatch(/^sha256:[0-9a-f]{64}$/);
  });

  it('produces a different checksum when the record set changes', () => {
    const first = buildControlSnapshot(1, 5, stocks);
    const second = buildControlSnapshot(1, 5, [...stocks, { ticker: 'GS', companyName: 'Goldman Sachs' }]);
    expect(first.checksum).not.toEqual(second.checksum);
  });
});
