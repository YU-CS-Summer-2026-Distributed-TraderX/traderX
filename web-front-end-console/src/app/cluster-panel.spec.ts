import { BookRead, bookVerdict } from './cluster-panel';

/**
 * The four things three member reads can be saying.
 *
 * Three of these have been seen on a live rig. The fourth — members DISAGREEING on the book at the
 * same applied sequence — deliberately has not, and must not be: divergence in this system is
 * permanent and costs a fresh epoch to clear, so manufacturing it to look at a banner would be
 * causing the accident to photograph it.
 *
 * But an arm whose behaviour is only assumed is the arm that fails when it finally matters, and this
 * is the one that matters most. So it is exercised HERE, where the payload can be divergent and
 * nothing is at stake. What is under test is the state selection and the wording — the parts that
 * could actually be wrong — not the cluster.
 */
describe('bookVerdict', () => {
  const read = (applied: number, digest: string, books = 69): BookRead => ({ applied, digest, books });

  it('is a consensus reading only when the sequence is BOTH matched and advancing', () => {
    const v = bookVerdict([read(100, 'x'), read(100, 'x'), read(100, 'x')], true, 0);
    expect(v.tone).toContain('good');
    expect(v.text).toContain('agree on the book');
    expect(v.text).toContain('advancing');
  });

  /** A stopped cluster agrees with itself perfectly. Going green for that is the vacuous pass. */
  it('refuses to call identical-but-static agreement a consensus reading', () => {
    const v = bookVerdict([read(100, 'x'), read(100, 'x')], false, 42000);
    expect(v.tone).not.toContain('good');
    expect(v.tone).not.toContain('bad');
    expect(v.text).toContain('quiet 42s');
  });

  /** THE ARM THAT MUST NEVER FIRE FOR REAL. Only ever exercised here. */
  it('alarms when members hold DIFFERENT books at the SAME sequence', () => {
    const v = bookVerdict([read(100, 'x'), read(100, 'y'), read(100, 'x')], true, 0);
    expect(v.tone).toContain('bad');
    expect(v.text).toContain('DISAGREE');
    expect(v.text).toContain('100');
  });

  /**
   * The distinction that stops the alarm crying wolf. Members legitimately sit at different points
   * mid-flush; a difference read across two sequences says nothing about agreement, so this must NOT
   * reach the alarm even though the digests differ.
   */
  it('calls differing books at differing sequences skew, not disagreement', () => {
    const v = bookVerdict([read(100, 'x'), read(101, 'y')], true, 0);
    expect(v.tone).not.toContain('bad');
    expect(v.text).toContain('skew, not disagreement');
  });

  it('concludes nothing from a single readable member', () => {
    const v = bookVerdict([read(100, 'x'), null, null], true, 0);
    expect(v.tone).not.toContain('good');
    expect(v.text).toContain('at least two');
  });
});
