import { Component, computed, input } from '@angular/core';
import { PROVENANCE_LABEL, Provenance, provenanceOf } from './api';

/**
 * Where one price came from, beside the price.
 *
 * The rig publishes several provenances at once and they are not interchangeable: a replayed tape
 * mark, a real reference curve, a model output, a simulation, and a carried-forward close all look
 * identical as numbers. Two names are deliberately NOT on the tape — the tick store merged
 * Alphabet's share classes, so replaying that partition would publish a price for a security that
 * does not exist, and one name is OTC and simply absent from the tape. That is a decision worth
 * seeing rather than a footnote nobody reads, and a chip is where a reader is already looking.
 *
 * Deliberately NOT a tape/synthetic binary: that mislabels the FRED curve as synthetic and a
 * carried-forward close as live, both in the direction that overclaims.
 */
@Component({
  selector: 'price-chip',
  template: `
    @if (kind() !== 'unknown' || source()) {
      <span class="chip" [class]="kind()" [title]="hint()">{{ label() }}</span>
    }
  `,
  styles: `
    .chip { font-size: 9.5px; letter-spacing: .02em; padding: 0 5px; line-height: 14px;
            border-radius: 7px; vertical-align: middle; white-space: nowrap;
            background: var(--chip-bg, #eef1f5); color: var(--chip-fg, #5a6672); }
    .tape      { background: #e7f3ea; color: #1d6b35; }
    .reference { background: #e8eefb; color: #27479b; }
    .model     { background: #f1ecfa; color: #5b3fa6; }
    .simulated { background: #fdf1e3; color: #8a5a17; }
    .carried   { background: #f3f4f6; color: #6b7280; }
    .unknown   { background: #fdecec; color: #9b2c2c; }
  `,
})
export class PriceChip {
  /** The publisher's own source string, verbatim. */
  readonly source = input<string | undefined>(undefined);
  /** Tape time on replayed names, wall-clock elsewhere — shown in the hint, never differenced. */
  readonly asOf = input<string | undefined>(undefined);

  readonly kind = computed<Provenance>(() => provenanceOf(this.source()));
  readonly label = computed(() => PROVENANCE_LABEL[this.kind()]);

  readonly hint = computed(() => {
    const s = this.source() ?? 'unreported';
    const at = this.asOf();
    const what: Record<Provenance, string> = {
      tape: 'A real recorded market mark being replayed.',
      reference: 'A real published reference curve.',
      model: 'Derived from the underlying, not quoted.',
      simulated: 'Generated, not observed.',
      carried: 'Last close carried forward — this name is not on the tape, so it does not move.',
      unknown: 'The tick did not say where this price came from.',
    };
    return `${what[this.kind()]} source=${s}${at ? ` · asOf=${at}` : ''}`;
  });
}
