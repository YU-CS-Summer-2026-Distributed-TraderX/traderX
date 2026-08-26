import { signal } from '@angular/core';
import { Book, Row, rankBooks } from './collar-reference';
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
});
