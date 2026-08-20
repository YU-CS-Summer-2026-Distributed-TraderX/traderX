import { Component, inject } from '@angular/core';
import { Api } from './api';
import { HelpTip } from './help';

/** What the YU17 override layer added to the upstream app, and why each one was needed. */
const ADDED: { what: string; why: string }[] = [
  { what: 'Algo execution on the order ticket',
    why: 'TWAP and VWAP were reachable only by calling the algo engine directly; the ticket now offers them as an execution style, and the parent slices through the same consensus path as any order.' },
  { what: 'Order details and W3C traces in the blotter',
    why: 'A row showed a status and nothing else. It now expands to the order\'s refs and its distributed trace, fetched from Tempo — a refusal always has a trace, because rejects are head-sampled.' },
  { what: 'Free-form OCC symbol entry',
    why: 'The ticket could only pick from the equity dropdown, so listed options were untradeable from the UI even though the engine accepts them.' },
  { what: 'Cancel routed to the gateway\'s own /cancel',
    why: 'The upstream app cancels with /orders/{id}/cancel. On the cluster tier the gateway routes that prefix to its NEW-ORDER handler — a cancel that silently books an order. The override sends {orderRef} to the sibling route instead.' },
  { what: 'Open orders read from the trade-processor model',
    why: 'The gateway serves no order snapshot on this tier (405, POST only), so the upstream call returned nothing and the blotter looked empty.' },
  { what: 'Live order updates off the bare /orders subject',
    why: 'The upstream app subscribes per-account; the cluster publisher emits on a bare subject with the account in the payload, so every live update was being dropped.' },
];

@Component({
  selector: 'legacy-panel',
  imports: [HelpTip],
  template: `
    <div class="card-head">
      <h2>The original TraderX UI</h2>
      <help-tip text="The upstream FINOS TraderX web front end — the app this project started from — running against the same cluster. It is not a museum piece: it is what the system looked like before this work, and it is still the app most people mean by 'TraderX'. Keeping it running alongside is the honest comparison, and the enhancements listed here were made to it rather than only to this console." />
      <span class="spacer"></span>
      <span class="pill" [class.good]="rig()" [class.warn]="!rig()">{{ rig() ? 'rig connected' : 'rig unreachable' }}</span>
    </div>

    <div class="hero">
      <div>
        <div class="lead">Open the original UI</div>
        <div class="sub">Served at the edge proxy's root, alongside every other service — the same
          nginx that serves this console's API calls. If the pill above is green, it is there.</div>
        <div class="mono url">{{ url }}</div>
      </div>
      <a class="btn-primary open" [href]="url" target="_blank" rel="noopener">Open ↗</a>
    </div>

    <h3>What the new UI added</h3>
    <table>
      <thead><tr><th>added</th><th>why it was needed</th></tr></thead>
      <tbody>
        @for (a of added; track a.what) {
          <tr><td class="w">{{ a.what }}</td><td class="sub">{{ a.why }}</td></tr>
        }
      </tbody>
    </table>

    <h3>Where the two apps differ</h3>
    <div class="sub note">The original covers one instrument class, one ingress and one view of a
      trade. This console exists because the rest of the system had no surface at all: five
      instrument classes, an Aeron cluster you can watch agree, FIX ingress, the end-of-day session
      chain, the kdb capture tap, cut provenance. Neither app replaces the other. The original is
      the familiar shape of a trading UI, and this one is the argument that there is more
      underneath it.</div>
  `,
  styles: `
    .spacer { flex: 1; }
    .hero { display: flex; align-items: center; gap: 20px; padding: 14px 16px; border-radius: 10px;
            background: var(--accent-soft); margin-bottom: 6px; }
    .lead { font-size: 15px; font-weight: 600; color: var(--accent); }
    .hero .sub { margin-top: 3px; max-width: 560px; }
    .url { margin-top: 5px; color: var(--muted); }
    .open { margin-left: auto; text-decoration: none; padding: 8px 16px; border-radius: 8px;
            font-size: 13.5px; white-space: nowrap; }
    h3 { margin: 20px 0 6px; font-size: 12.5px; font-weight: 600; color: var(--muted); }
    h3 .sub { font-weight: 400; }
    .note { max-width: 780px; margin-bottom: 8px; }
    td.w { font-weight: 600; font-size: 12.5px; width: 260px; vertical-align: top; }
    .mono { font-family: var(--mono); font-size: 12px; }
  `,
})
export class LegacyPanel {
  private api = inject(Api);
  readonly added = ADDED;
  /** The edge proxy's root serves the original app; the dev proxy forwards that port already. */
  readonly url = 'http://localhost:30080/';
  rig(): boolean { return this.api.accounts().length > 0; }
}
