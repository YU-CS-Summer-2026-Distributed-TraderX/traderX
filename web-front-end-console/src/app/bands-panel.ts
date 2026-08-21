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
 * (the containment test passes vacuously against an empty accepted range, so a naive rule condemns
 * a security that has simply never traded); too few samples means thin, because a split of one
 * against one can fall the right way by luck; and only then does the test decide.
 *
 * <p>The test is CONTAINMENT — no refused price inside the accepted range — not disjointness of the
 * two ranges. A collar refuses on both sides of its band, so the ranges always overlap and a
 * disjointness rule reports the band's own signature as "some other cause", inverting the answer
 * for the exact case this panel exists to catch. See the BandCheck doc in api.ts.
 */
@Component({
  selector: 'bands-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <button type="button" class="card-tog" (click)="open.set(!open())">
        <span class="arrow">{{ open() ? '▾' : '▸' }}</span><h2>Book bands &amp; refusals</h2>
      </button>
      <help-tip text="The price collar is a band anchored on the first limit order that entered a security's book — not a percentage around the current mark. A book anchored by a stray order refuses every realistic price for the rest of the epoch, and nothing repairs it in place: a price seed cannot move a mark that has already printed, and the band is not derived from the mark anyway. This screen reads the regulatory journal and compares, per security, the prices that were accepted against the prices that were refused. A collar refuses on BOTH sides of its band, so the test is not whether the two ranges sit apart — they rarely do — but whether any refused price falls INSIDE the accepted range. None inside is the band's own signature; one inside means the band cannot be what refused it, and the cause is elsewhere." />
      <span class="spacer"></span>
      @if (api.bandsState() !== 'ok') { <span class="pill warn">cannot see</span> }
      @else if (bad().length) { <span class="pill bad">{{ bad().length }} mis-anchored</span> }
      @else if (api.bands().length) { <span class="pill good">no mis-anchored book</span> }
      <button (click)="refresh()" [disabled]="busy()">{{ busy() ? '…' : 'Rescan' }}</button>
    </div>

    @if (open()) {
      @if (api.bandsState() === 'disabled') {
        <div class="banner warn-note">The regulatory projection is off on this rig, so this screen
          cannot see. It is an in-memory blotter sized by <span class="mono">RECON_BLOTTER_CAPACITY</span>,
          which the cloud manifest deliberately sets to 0 for throughput — a one-value edit turns it
          on. <b>Absence of refusals here is not evidence there are none</b>: with no journal to read,
          an empty screen and a clean rig look identical.</div>
      } @else if (api.bandsState() === 'absent') {
        <div class="banner warn-note">This gateway serves no regulatory route at all — the build
          running here never registered it, which is a different thing from the projection being
          switched off. Nothing to judge from, and nothing wrong.</div>
      } @else if (api.bandsState() === 'stale-token') {
        <div class="banner warn-note">The regulatory journal refused this console's admin token, and
          a freshly minted one was refused too. An admin JWT lives 8 hours, so a console left open
          outlives it — but a re-mint has already been tried, so this is credentials rather than
          age. Nothing here is a statement about the rig.</div>
      } @else if (api.bandsState() === 'unreachable' || api.bandsState() === 'no-token') {
        <div class="banner warn-note">Could not read the regulatory journal
          ({{ api.bandsState() === 'no-token' ? 'no admin token could be minted' : 'route unreachable' }}),
          so this screen has nothing to judge from — which is not the same as nothing to report.</div>
      } @else if (!api.bands().length) {
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
                      <div class="sub">a refused price sits inside the range this book has already
                        accepted, so the band cannot be what refused it — account, credit or
                        quantity</div>
                    }
                  }
                  @if (b.thin) { <div class="sub thin">thin: {{ b.accepted }} accepted / {{ b.rejected }} refused —
                    a split this small can fall either way by luck</div> }
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
    .warn-note { background: var(--warn-soft); color: var(--warn); max-width: 760px; }
    .mono { font-family: var(--mono); font-size: 12px; }
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
