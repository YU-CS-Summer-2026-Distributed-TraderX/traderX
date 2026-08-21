import { Component, computed, inject, input, signal } from '@angular/core';
import { Api } from './api';

interface SpanRow { service: string; name: string; startNs: bigint; durUs: number; ref?: string; }

/**
 * One trace, fetched from Tempo and rendered as a span table.
 *
 * Extracted from the activity panel so the blotter can show the same thing under a trade without a
 * second copy of the parsing — the OTLP shape is nested three deep (batch → scopeSpans → spans) and
 * the service name hides in a resource attribute, which is not worth getting right twice.
 *
 * **A missing trace has three causes and they are not the same fact**, so this refuses to render one
 * message for all of them:
 *
 *   - Tempo holds NOTHING at all — tracing is not exporting on this rig, and no id would ever hit.
 *     Measured on the cloud rig 2026-08-21: `/api/search` returns `{"traces":[]}` and
 *     `/api/search/tags` returns `{"tagNames":[]}` while Tempo itself answers 200. A per-trace 404
 *     is indistinguishable from a sampling miss, so the panel asks the question a single lookup
 *     cannot answer.
 *   - Tempo has traces but not this one — accepted orders are head-sampled 1-in-N.
 *   - Tempo is unreachable — a fact about the route, not about the order.
 */
@Component({
  selector: 'trace-view',
  template: `
    <div class="head">
      <button (click)="load()" [disabled]="busy()">
        {{ busy() ? 'fetching…' : spans() ? 'refresh trace' : 'View trace' }}</button>
      @if (traceId()) { <span class="tid">{{ traceId()!.slice(0, 16) }}…</span> }
    </div>

    @if (msg()) { <div class="faint note">{{ msg() }}</div> }
    @if (spans(); as ss) {
      @if (ss.length) {
        @if (refs().length > 1) {
          <div class="faint note">This trace covers {{ refs().length }} orders — refs {{ refs().join(', ') }}.
            A trace id is a pure function of the CLIENT order id, so orders that reused one share a
            trace. Every span below is real; they are not all this row's.</div>
        }
        <table class="spans">
          <thead><tr>
            @if (refs().length > 1) { <th>order</th> }
            <th>service</th><th>span</th><th class="num">start +µs</th><th class="num">duration µs</th></tr></thead>
          <tbody>
            @for (s of ss; track $index) {
              <tr>
                @if (refs().length > 1) { <td>{{ s.ref ?? '—' }}</td> }
                <td>{{ s.service }}</td><td>{{ s.name }}</td>
                  <td class="num">{{ rel(s, ss) }}</td><td class="num">{{ s.durUs.toFixed(0) }}</td></tr>
            }
          </tbody>
        </table>
      }
    }
  `,
  styles: `
    .head { display: flex; align-items: center; gap: 7px; }
    .tid { font-family: var(--mono); font-size: 10.5px; color: var(--faint); }
    .note { margin-top: 5px; max-width: 620px; }
    .spans { margin-top: 6px; }
    .spans td, .spans th { font-size: 11.5px; }
  `,
})
export class TraceView {
  private api = inject(Api);
  /** Undefined when the caller has nothing to derive an id from — the button then explains that. */
  readonly traceId = input<string | undefined>(undefined);
  /** What the id was derived from, so the message can say why a lookup is even possible. */
  readonly derivedFrom = input<string>('');

  readonly spans = signal<SpanRow[] | null>(null);

  /**
   * The distinct order refs in this trace, and the reason the table has an "order" column at all.
   *
   * A trace id is derived from the CLIENT order id, so two orders that reuse one get the SAME trace
   * — measured: a reused ClOrdID produced a single id holding 10 spans, 5 per order. A panel that
   * says "this order's trace" and then lists somebody else's spans without saying so is the same
   * failure as showing a wrong id: everything on screen is real, and the whole is misleading.
   * Every span carries `traderx.order_ref`, so this is detectable rather than merely possible.
   *
   * Stays hidden in the ordinary one-order case, where a constant column teaches nothing.
   */
  readonly refs = computed(() => [...new Set((this.spans() ?? []).map(s => s.ref).filter(Boolean))] as string[]);
  readonly msg = signal('');
  readonly busy = signal(false);

  async load(): Promise<void> {
    const id = this.traceId();
    if (!id) {
      // Naming the missing LINK, not the missing trace. Saying "not found" would blame Tempo for
      // a gap that is on this side: there was no id to look up.
      this.msg.set(this.derivedFrom() === 'trade'
        ? 'No trace id to look up for this trade. Trace ids come from the CLIENT ORDER ID, which the '
          + 'engine never sees and no trade row carries — so a trade reaches its trace only through '
          + 'sourceOrderId, joined to an order THIS browser session submitted and still holds the generated id '
          + 'for. A market sweep (no originating order), a trade from another client, or one from '
          + 'before this browser session began has nothing on this side to join to.'
        : this.derivedFrom() === 'order'
        ? 'No trace id for this order. The row carries one when the engine stamped it on the order\'s '
          + 'own egress, and this session falls back to what it recorded for orders it submitted '
          + 'itself — neither is available here. An order that was not sampled, or that carried no '
          + 'client order id to derive an id from, has none to show — and it does not borrow one from '
          + 'whoever filled it. (A resting order that HAS a trace keeps it through a fill: measured, '
          + '1-70 held its own id across a partial fill by another account.) '
          + 'The order may well be traced; this page cannot name the id, and will not guess one.'
        : 'nothing to trace: this row carries no client order id to derive a trace id from');
      return;
    }
    this.busy.set(true);
    this.spans.set(null);
    try {
      const r = await this.api.load<{ batches?: unknown[] }>(`/tempo/api/traces/${id}`);
      if (r.status === 200 && r.body && typeof r.body === 'object' && 'batches' in r.body) {
        this.spans.set(parseSpans(r.body as OtlpTrace));
        this.msg.set(this.spans()!.length ? '' : 'the trace exists but carries no spans yet');
        return;
      }
      if (r.status !== 404) {
        this.msg.set(`Tempo did not answer (HTTP ${r.status || 'no response'}) — a fact about the route, not about this ${this.derivedFrom() || 'row'}`);
        return;
      }
      // 404 alone cannot tell "not sampled" from "nothing is being traced at all". Ask Tempo.
      const any = await this.api.load<{ traces?: unknown[] }>('/tempo/api/search?limit=1');
      const empty = any.status === 200 && any.body && typeof any.body === 'object'
        && Array.isArray(any.body.traces) && any.body.traces.length === 0;
      this.msg.set(empty
        ? 'Tempo is up and holds NO traces at all, so nothing here would be found whatever its id — '
          + 'tracing is not exporting on this rig rather than this order going unsampled.'
        : 'not in Tempo: accepted orders are head-sampled 1-in-N, so most leave no trace. '
          + 'Rejected orders are always traced.');
    } finally { this.busy.set(false); }
  }

  rel(s: SpanRow, all: SpanRow[]): string {
    const t0 = all[0].startNs;
    return (Number(s.startNs - t0) / 1000).toFixed(0);
  }
}

interface OtlpTrace {
  batches?: {
    resource?: { attributes?: { key: string; value?: { stringValue?: string } }[] };
    scopeSpans?: { spans?: {
      name: string; startTimeUnixNano?: string; endTimeUnixNano?: string;
      attributes?: { key: string; value?: { stringValue?: string; intValue?: string } }[];
    }[] }[];
  }[];
}

/**
 * OTLP → flat rows in start order. Service name lives in a resource attribute, not on the span.
 *
 * Exported for the spec: the order-ref attribute it carries is what lets the panel notice a trace
 * holding more than one order, and that is worth a test that fails if the attribute stops arriving.
 */
export function parseSpans(body: OtlpTrace): SpanRow[] {
  const rows: SpanRow[] = [];
  for (const b of body.batches ?? []) {
    const service = b.resource?.attributes?.find(a => a.key === 'service.name')?.value?.stringValue ?? '?';
    for (const ss of b.scopeSpans ?? []) {
      for (const sp of ss.spans ?? []) {
        const refAttr = sp.attributes?.find(a => a.key === 'traderx.order_ref')?.value;
        rows.push({
          service, name: sp.name,
          startNs: BigInt(sp.startTimeUnixNano ?? 0),
          durUs: Number(BigInt(sp.endTimeUnixNano ?? 0) - BigInt(sp.startTimeUnixNano ?? 0)) / 1000,
          ref: refAttr?.stringValue ?? refAttr?.intValue,
        });
      }
    }
  }
  return rows.sort((a, b) => (a.startNs < b.startNs ? -1 : 1));
}
