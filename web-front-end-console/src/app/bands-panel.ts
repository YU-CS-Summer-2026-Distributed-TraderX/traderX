import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Api } from './api';
import { HelpTip } from './help';

/**
 * Which securities are safe to type into a ticket, and which will refuse a plausible price.
 * Pre-demo screen rather than a diagnostic: the failure it prevents happens on stage, where the
 * true explanation — "that book's collar band is anchored where an unrelated order landed hours
 * ago" — is not one anybody wants to give.
 *
 * <p>The reading is three-part and the ORDER matters: no accepted orders means no verdict at all
 * (disjointness is trivially true against an empty set, so the naive rule condemns a security that
 * has simply never traded); too few samples means thin, because a split of one against one can be
 * disjoint by luck; and only then does disjoint mean the band and overlap mean another cause.
 */
@Component({
  selector: 'bands-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <button type="button" class="card-tog" (click)="open.set(!open())">
        <span class="arrow">{{ open() ? '▾' : '▸' }}</span><h2>Book bands &amp; refusals</h2>
      </button>
      <help-tip text="The price collar is a band anchored on the first limit order that entered a security's book — not a percentage around the current mark. A book anchored by a stray order refuses every realistic price for the rest of the epoch, and nothing repairs it in place: a price seed cannot move a mark that has already printed, and the band is not derived from the mark anyway. This screen reads the regulatory journal and compares, per security, the prices that were accepted against the prices that were refused. Disjoint ranges are the signature of a mis-anchored book; overlapping ranges mean the refusal came from something else and say nothing about the band." />
      <span class="spacer"></span>
      @if (bad().length) { <span class="pill bad">{{ bad().length }} mis-anchored</span> }
      @else if (api.bands().length) { <span class="pill good">no mis-anchored book</span> }
      <button (click)="refresh()" [disabled]="busy()">{{ busy() ? '…' : 'Rescan' }}</button>
    </div>

    @if (open()) {
      @if (!api.bands().length) {
        <div class="faint">{{ busy() ? 'reading the journal…' : 'no refusals on record for this epoch' }}</div>
      } @else {
        <table>
          <thead><tr><th>security</th><th class="num">accepted price range</th>
            <th class="num">refused price range</th><th>reading</th></tr></thead>
          <tbody>
            @for (b of api.bands(); track b.security) {
              <tr>
                <td>{{ b.security }}</td>
                <!-- "2573 orders", never a bare number: a count printed beside a price range reads
                     as a count of price levels just as easily as of orders, and those differ by
                     ~49x on IBM here. The ambiguity has already caused one wrong figure. -->
                <td class="num">{{ b.accepted ? range(b.acceptedLo, b.acceptedHi) : '—' }}
                  <span class="n">{{ b.accepted }} order{{ b.accepted === 1 ? '' : 's' }}</span></td>
                <td class="num">{{ range(b.rejectedLo, b.rejectedHi) }}
                  <span class="n">{{ b.rejected }} order{{ b.rejected === 1 ? '' : 's' }}</span></td>
                <td>
                  @switch (b.verdict) {
                    @case ('anchored-elsewhere') {
                      <span class="pill bad">band anchored elsewhere</span>
                      <div class="sub">only prices near {{ range(b.acceptedLo, b.acceptedHi) }} are accepted —
                        type one of those, or demo a different security</div>
                    }
                    @case ('never-accepted') {
                      <span class="pill warn">never accepted</span>
                      <div class="sub">no accepted order to compare against, so nothing can be concluded
                        about the band</div>
                    }
                    @default {
                      <span class="pill">refused for another reason</span>
                      <div class="sub">the ranges overlap, so the same price was both accepted and
                        refused — account, credit or quantity, not the band</div>
                    }
                  }
                  @if (b.thin) { <div class="sub thin">thin: {{ b.accepted }} accepted / {{ b.rejected }} refused —
                    a split this small can be disjoint by luck</div> }
                </td>
              </tr>
            }
          </tbody>
        </table>
        <div class="sub note">Inference from prices, not the engine's own answer: ORDER_REJECTED in
          the regulatory report carries no reason code, so a refusal's actual cause is not readable
          here. The order path returns it — a rejection typed into the ticket names its reason.</div>
      }
    }
  `,
  styles: `
    .spacer { flex: 1; }
    .n { display: block; color: var(--faint); font-size: 11px; }
    .sub.thin { color: var(--warn); }
    .note { margin-top: 10px; max-width: 720px; }
    td .sub { font-size: 11.5px; }
  `,
})
export class BandsPanel implements OnInit {
  readonly api = inject(Api);
  readonly open = signal(true);
  readonly busy = signal(false);
  readonly bad = computed(() => this.api.bands().filter(b => b.verdict === 'anchored-elsewhere'));

  ngOnInit(): void { this.refresh(); }

  async refresh(): Promise<void> {
    this.busy.set(true);
    try { await this.api.loadBands(true); } finally { this.busy.set(false); }
  }

  range(lo: number, hi: number): string {
    const f = (v: number) => (v < 2 ? v.toFixed(6) : v.toFixed(2));
    return lo === hi ? f(lo) : `${f(lo)}–${f(hi)}`;
  }
}
