import { Component, Signal, computed, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

/**
 * Collapse + paging state for one list section. Holds no data of its own — it wraps whatever
 * signal already produces the rows, so the panel keeps a single source of truth and this stays a
 * view concern.
 *
 * `items` MUST be a signal: every derived value here is a computed(), and a computed only
 * recomputes when a signal it reads changes. Handing one a plain array would freeze the section on
 * the first render — the same trap that made the ticket's bond terms and the blotter's contract
 * list go stale.
 */
export class Section<T> {
  readonly open = signal(true);
  readonly page = signal(0);
  readonly perPage = signal(10);

  constructor(readonly items: Signal<T[]>, readonly idOf: (t: T) => string) {}

  readonly pages = computed(() =>
    Math.max(1, Math.ceil(this.items().length / Math.max(1, this.perPage()))));

  /** The page actually displayed: `page` can outrun the data when rows disappear under it. */
  readonly cur = computed(() => Math.min(this.page(), this.pages() - 1));

  readonly view = computed(() => {
    const n = Math.max(1, this.perPage());
    const from = this.cur() * n;
    return this.items().slice(from, from + n);
  });

  /** A method rather than an inline arrow: Angular templates do not parse arrow functions. */
  toggle(): void { this.open.set(!this.open()); }

  step(delta: number): void {
    this.page.set(Math.min(this.pages() - 1, Math.max(0, this.cur() + delta)));
  }

  setPerPage(n: number): void {
    this.perPage.set(Math.min(500, Math.max(1, Math.floor(n) || 10)));
    this.page.set(0);
  }

  /** Jump to the page holding `id` (substring match, case-insensitive). True if it was found. */
  reveal(id: string): boolean {
    const q = id.trim().toLowerCase();
    if (!q) return false;
    const i = this.items().findIndex(x => this.idOf(x).toLowerCase().includes(q));
    if (i < 0) return false;
    this.open.set(true);
    this.page.set(Math.floor(i / Math.max(1, this.perPage())));
    return true;
  }
}

/** Section heading: the disclosure arrow, the row count, and a slot for the section's help tip. */
@Component({
  selector: 'sec-head',
  template: `
    <h3>
      <button type="button" class="tog" (click)="s().toggle()">
        <span class="arrow">{{ s().open() ? '▾' : '▸' }}</span>{{ label() }}
      </button>
      <ng-content />
    </h3>
  `,
  styles: `
    h3 { display: flex; align-items: center; gap: 6px; margin: 12px 0 3px; }
    .tog { display: flex; align-items: center; gap: 6px; background: none; border: none; padding: 0;
           font-size: 12.5px; font-weight: 600; color: var(--muted); cursor: pointer; }
    .tog:hover { color: var(--text); }
    .arrow { display: inline-block; width: 13px; font-size: 13px; line-height: 1; }
  `,
})
export class SecHead {
  // Section<any>: idOf is a property-typed function, so Section<Trade> is not assignable to
  // Section<unknown> under strictFunctionTypes — and these two components only ever read counts.
  readonly s = input.required<Section<any>>();
  readonly label = input.required<string>();
}

/** Section footer: ‹ / › page arrows around an editable page size, plus the shown-of-total range. */
@Component({
  selector: 'sec-pager',
  imports: [FormsModule],
  template: `
    @if (s().items().length > s().perPage() || s().perPage() !== 10) {
      <div class="pager">
        <button type="button" (click)="s().step(-1)" [disabled]="s().cur() === 0">‹</button>
        <input type="number" min="1" [ngModel]="s().perPage()" (ngModelChange)="s().setPerPage(+$event)">
        <span class="sub">per page</span>
        <button type="button" (click)="s().step(1)" [disabled]="s().cur() >= s().pages() - 1">›</button>
        <span class="sub">{{ from() }}–{{ to() }} of {{ s().items().length }}
          · page {{ s().cur() + 1 }}/{{ s().pages() }}</span>
      </div>
    }
  `,
  styles: `
    .pager { display: flex; align-items: center; gap: 5px; margin: 5px 0 2px; font-size: 12px; }
    .pager button { padding: 0 7px; font-size: 12px; line-height: 1.7; color: var(--muted); }
    .pager input { width: 38px; text-align: center; padding: 1px 3px; font-size: 12px; }
    /* Chrome's number spinners double the apparent width of a field this small. */
    .pager input::-webkit-inner-spin-button, .pager input::-webkit-outer-spin-button {
      -webkit-appearance: none; margin: 0; }
  `,
})
export class SecPager {
  readonly s = input.required<Section<any>>();
  readonly from = computed(() => (this.s().items().length ? this.s().cur() * this.s().perPage() + 1 : 0));
  readonly to = computed(() =>
    Math.min(this.s().items().length, (this.s().cur() + 1) * this.s().perPage()));
}
