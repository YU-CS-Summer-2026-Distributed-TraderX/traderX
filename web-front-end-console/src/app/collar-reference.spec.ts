import { signal } from '@angular/core';
import { Book, Row, capturedPrints, printedSummary, rankBooks } from './collar-reference';
import { Section } from './section';

/**
 * The collar reference at the size it is about to be.
 *
 * The quoted universe goes from 69 books to ~145 when `PRICE_TICKERS` widens to the 100-symbol
 * replay universe, which turns the panel's paging from a nicety into the thing that decides what a
 * reader sees. **Showing fifteen of a hundred and forty-five is only honest if the fifteen are the
 * right fifteen**, and at 69 books that was true by luck as much as by design: there were few
 * enough never-printed rows that a printed one was unlikely to be pushed off. At 145, with only a
 * handful of books ever having printed, it is the sort that has to guarantee it.
 *
 * So the invariant under test is not "it sorts" — it is that **no never-printed row can displace a
 * printed one from page one, however large its gap**, and never-printed gaps are the large ones (a
 * seeded mark against a tape that has moved on runs to thousands of percent; FNMA on the live rig
 * is +17,186%). A comparator that merely sorted by |gap| would bury every real signal.
 */
describe('collar reference at ~145 books', () => {
  /** 145 books: 6 that have printed here, 139 that never have — the live shape, scaled up. */
  const PRINTED = ['AAPL', 'IBM', 'NVDA', 'MSFT', 'TSLA', 'GS'];
  const universe = (): { books: Book[]; printed: Set<string> } => {
    const books: Book[] = [];
    // Printed books sit CLOSE to the reference — small gaps, because they are real prints.
    PRINTED.forEach((ticker, i) => books.push({ ticker, mark: 100 + i, ref: 100 }));
    // Never-printed books carry a seeded mark against a tape that has moved on: enormous gaps.
    for (let i = 0; i < 139; i++) {
      books.push({ ticker: `SYM${i}`, mark: 200, ref: 200 / (i + 2) });
    }
    return { books, printed: new Set(PRINTED) };
  };

  it('puts every printed book above every never-printed one, whatever the gaps say', () => {
    const { books, printed } = universe();
    const rows = rankBooks(books, printed);
    expect(rows.length).toBe(145);
    // findLastIndex is not in this project's TS lib target.
    const lastPrinted = rows.reduce((acc, r, i) => (r.hasPrinted ? i : acc), -1);
    const firstUnprinted = rows.findIndex(r => !r.hasPrinted);
    expect(lastPrinted).toBe(PRINTED.length - 1);
    expect(firstUnprinted).toBe(PRINTED.length);
    // And the never-printed rows really do carry the bigger numbers — otherwise this passes
    // vacuously against a comparator that only ever sorted by gap.
    expect(Math.abs(rows[firstUnprinted].gapPct!)).toBeGreaterThan(Math.abs(rows[0].gapPct!));
  });

  it('keeps the whole signal on page one at 15 a page', () => {
    const { books, printed } = universe();
    const rows = signal(rankBooks(books, printed));
    const sec = new Section<Row>(rows, r => r.ticker);
    sec.setPerPage(15);

    expect(sec.pages()).toBe(10);
    expect(sec.view().length).toBe(15);
    // Every book that has printed is visible without paging.
    expect(sec.view().filter(r => r.hasPrinted).map(r => r.ticker).sort()).toEqual([...PRINTED].sort());
  });

  it('sorts by gap magnitude, not sign, inside each group', () => {
    const rows = rankBooks(
      [{ ticker: 'UP', mark: 120, ref: 100 }, { ticker: 'DOWN', mark: 50, ref: 100 },
       { ticker: 'FLAT', mark: 101, ref: 100 }],
      new Set(['UP', 'DOWN', 'FLAT']));
    // DOWN is -50%, UP is +20%: magnitude wins, so the fall outranks the rise.
    expect(rows.map(r => r.ticker)).toEqual(['DOWN', 'UP', 'FLAT']);
  });

  /**
   * The failure a growing universe actually produces: books disappear under a reader who has paged
   * deep. `page` is never rewound, so if `view()` read it directly the panel would go blank rather
   * than show the last page — a wedged panel that a reload fixes, which is how it survives review.
   */
  it('shows the last page rather than nothing when the book count shrinks underneath', () => {
    const { books, printed } = universe();
    const rows = signal(rankBooks(books, printed));
    const sec = new Section<Row>(rows, r => r.ticker);
    sec.setPerPage(15);
    sec.page.set(9);
    expect(sec.view().length).toBe(10);          // 145 = 9 x 15 + 10

    rows.set(rankBooks(books.slice(0, 20), printed));
    expect(sec.pages()).toBe(2);
    expect(sec.cur()).toBe(1);                   // clamped, not left at 9
    expect(sec.view().length).toBe(5);
  });

  /** A book with no reference cannot divide by it. `—` is a rendered gap, not a NaN. */
  it('reports no gap rather than a NaN when the reference is zero', () => {
    const rows = rankBooks([{ ticker: 'NEW', mark: 200, ref: 0 }], new Set());
    expect(rows[0].gapPct).toBeNull();
    expect(rows[0].offReference).toBe(false);
  });

  /**
   * The reconciliation used to fail by OMISSION — a clause appended when the capture accounted for
   * every trade and dropped when it did not. A reader learned the count was untrustworthy by
   * noticing an absent phrase, which nobody does.
   *
   * It stopped being hypothetical when ADR-072's replayed order flow went live: the capture is
   * `tail -n 300` per member and the leader's file measured 1,625 lines against 1,502 booked
   * trades, so the panel could see 18% of them. Every book whose prints fell outside that window
   * reads as never-printed — the WRONG one of the two states this panel exists to separate, and
   * the one that sorts to the bottom precisely because it looks uninteresting.
   */
  describe('the printed count says when it is only a floor', () => {
    it('claims the capture accounts for everything only when the counts match', () => {
      const line = printedSummary(69, 6, 6, 6);
      expect(line).toContain('6 of 69 books have printed here');
      expect(line).toContain('which the capture accounts for');
      expect(line).not.toContain('floor');
    });

    it('states the shortfall in numbers rather than dropping a clause', () => {
      const line = printedSummary(68, 22, 300, 1502);
      expect(line).toContain('At least 22 of 68');
      expect(line).toContain('300 of 1502');
      expect(line).toContain('floor');
      // The specific consequence, because "this is a floor" alone does not tell a reader that a
      // row in the table below may be lying to them.
      expect(line).toContain('never-printed');
    });

    it('never claims reconciliation when a counter could not be read', () => {
      for (const line of [printedSummary(68, 22, null, 1502), printedSummary(68, 22, 300, null)]) {
        expect(line).not.toContain('accounts for');
        expect(line).toContain('floor');
      }
    });
  });

  /**
   * The capture parse, and the case that blanked the panel.
   *
   * The bridge returns `tail -n 300` per file. Below 300 lines the header rides along and a
   * header-lookup works; above it the header has scrolled out, line 0 is a data row,
   * `indexOf('sym')` is -1, and every row is dropped in silence. Measured live once ADR-072's
   * replayed flow filled the file: 0 of 68 books printed against 2,476 booked trades, all 68
   * rendered never-printed.
   *
   * The header case is tested too, because a positional parser that ate the header as data would
   * add a symbol called "sym" and be just as wrong in the other direction.
   */
  describe('capturedPrints', () => {
    const trade = (seq: number, sym: string) => `${seq},1,${seq},900001,${sym},B,10,201.19,17877817`;
    const file = (name: string, rows: string[]) => `==FILE /data/kdb-capture/${name} ${rows.length}\n${rows.join('\n')}`;

    it('reads rows whose header has scrolled out of the tail window', () => {
      const cap = file('txtrade-1-order-matcher-cluster-0.csv',
        [trade(1, 'COF'), trade(2, 'AAPL'), trade(3, 'COF')]);
      const got = capturedPrints([{ capture: cap }]);
      expect([...got.symbols].sort()).toEqual(['AAPL', 'COF']);
      expect(got.rows).toBe(3);
    });

    it('skips the header when it is still in the window, rather than counting it as a print', () => {
      const cap = file('txtrade-1-order-matcher-cluster-0.csv',
        ['seq,epoch,tradeSeq,account,sym,side,qty,px,tsMs', trade(1, 'IBM')]);
      const got = capturedPrints([{ capture: cap }]);
      expect([...got.symbols]).toEqual(['IBM']);
      expect(got.rows).toBe(1);
    });

    it('counts trades only — an order capture is not a print', () => {
      const cap = file('txorder-1-order-matcher-cluster-0.csv', [trade(1, 'TSLA')])
        + '\n' + file('txtrade-1-order-matcher-cluster-0.csv', [trade(2, 'NVDA')]);
      const got = capturedPrints([{ capture: cap }]);
      expect([...got.symbols]).toEqual(['NVDA']);
    });

    it('is empty, not wrong, for a follower holding only a header', () => {
      const got = capturedPrints([{ capture: file('txtrade-1-order-matcher-cluster-1.csv',
        ['seq,epoch,tradeSeq,account,sym,side,qty,px,tsMs']) }]);
      expect(got.symbols.size).toBe(0);
      expect(got.rows).toBe(0);
    });
  });
});
