import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { Api } from './api';

// Controlled Treasury bill trade-to-analysis run (contract traderx.treasury-trade-demo.v1, owned by
// the server). The browser never chooses the trade: the server defines one bounded scenario and one
// run. This component only starts it (signed in, explicit click), follows confirmed stages and
// shows the result of that run. Internal ids are never rendered.

export type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'NEEDS_REVIEW';
export interface TreasuryResult {
  instrument: string; currency: string; quantity: string; signedFaceUsd: string; bookedPrice: string;
  bookedPriceUnit: string; valuationDate: string; pricingUsd: string; referenceUsd: string; differenceUsd: string;
  toleranceUsd: string; withinTolerance: boolean; rateSensitivity: string; executionLocation: string;
  assumedCurve: string; usableForRisk: false;
}
export interface TreasuryRun { id: string; status: RunStatus; stage: string; message: string; result: TreasuryResult | null }
export interface TreasuryView {
  schema: string; workerAvailable: boolean;
  scenario: null | { instrument: string; label: string; quantity: string; quantityUnit: string; currency: string;
    valuationDate: string; assumedCurve: string; executionLocation: string; singleRun: boolean };
  run: TreasuryRun | null;
}

export const STAGES: [string, string][] = [['TRADE_SUBMITTED', 'Trade submitted'], ['TRADE_BOOKED', 'Trade booked'],
  ['POSITION_EXPORTED', 'Position exported'], ['PRICING', 'Pricing'], ['INDEPENDENTLY_CHECKED', 'Independently checked']];
const STATUSES = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'NEEDS_REVIEW'];
const ACTIVE = ['QUEUED', 'RUNNING'];
const DECIMAL = /^-?\d+(\.\d+)?$/;
export const KEY_STORAGE = 'traderx.treasuryDemo.idempotencyKey';
export const POLL = { firstMs: 1000, maxMs: 5000, factor: 1.5, giveUpMs: 10 * 60 * 1000 };

/** A body is used only if it has exactly the shape the page relies on; anything else is unavailable. */
export function validView(b: any): b is TreasuryView {
  if (!b || b.schema !== 'traderx.treasury-trade-demo.v1' || typeof b.workerAvailable !== 'boolean') return false;
  const s = b.scenario;
  if (s !== null && (typeof s !== 'object' || typeof s.label !== 'string' || !DECIMAL.test(s.quantity)
      || typeof s.valuationDate !== 'string' || typeof s.assumedCurve !== 'string')) return false;
  const r = b.run;
  if (r === null) return true;
  if (typeof r !== 'object' || !STATUSES.includes(r.status) || !(r.stage === 'QUEUED' || STAGES.some(([k]) => k === r.stage))) return false;
  if (r.status !== 'SUCCEEDED') return r.result === null;
  const x = r.result;
  return r.stage === 'INDEPENDENTLY_CHECKED' && !!x && x.usableForRisk === false && x.withinTolerance === true
    && ['quantity', 'signedFaceUsd', 'bookedPrice', 'pricingUsd', 'referenceUsd', 'differenceUsd', 'toleranceUsd'].every((k) => DECIMAL.test(x[k]));
}

@Component({
  selector: 'treasury-demo',
  template: `
    <div class="head"><h2>Run a Treasury demo</h2>
      <button type="button" (click)="check()" [disabled]="checking()">Check status</button></div>
    <p class="sub">Places one small demo Treasury bill trade on the demo venue, then prices the resulting position with an
      assumed flat 3% curve and checks it independently. Calculated locally; results displayed here. Demo only — not production risk.</p>

    @if (unavailable(); as u) { <p role="status" class="banner bad" data-state="unavailable">{{ u }}</p> }
    @if (view(); as v) {
      @if (v.scenario; as s) {
        <p class="scenario" data-scenario><b>{{ s.label }}</b> · {{ s.instrument }} · buy {{ usd(s.quantity, 0) }} {{ s.quantityUnit }} ·
          valuation {{ s.valuationDate }} · {{ s.assumedCurve }} curve</p>
      }
      @if (!v.run) {
        @if (!v.scenario) {
          <p class="banner bad" data-state="not-configured">The Treasury demo is not available on this deployment.</p>
        } @else if (!v.workerAvailable) {
          <p class="banner bad" data-state="worker-unavailable">The pricing worker is not available right now, so a run cannot start. No trade has been placed.</p>
        } @else if (!api.authUser()) {
          <p class="sub">Sign in as an operator to run the demo.</p>
          <button type="button" (click)="api.authPrompt.set(true)">Sign in</button>
        } @else {
          <button type="button" class="btn-primary" data-run (click)="start()" [disabled]="starting()">
            {{ starting() ? 'Starting…' : 'Run Treasury demo' }}</button>
          <span class="sub"> This demo runs once; its result stays here.</span>
        }
      }
    }
    @if (startError(); as e) {
      <p role="alert" class="banner bad" data-state="start-error">{{ e }}</p>
      @if (canRetry()) { <button type="button" (click)="start()" [disabled]="starting()">Try again</button> }
    }

    @if (view()?.run; as run) {
      <article class="run" [attr.data-run-status]="run.status">
        <div class="head"><h3>This run</h3>
          <span class="pill" [class.good]="run.status === 'SUCCEEDED'" [class.bad]="run.status === 'FAILED'"
                [class.warn]="run.status === 'NEEDS_REVIEW' || active(run)" data-run-label>{{ runLabel(run) }}</span></div>
        <ol class="flow">
          @for (s of steps(run); track s.label) { <li [class]="s.tone" [attr.data-step]="s.state"><b>{{ s.label }}</b><span class="sub">{{ s.state }}</span></li> }
        </ol>
        @if (active(run) && !view()!.workerAvailable) {
          <p class="banner bad" data-state="worker-lost">The pricing worker is currently unavailable. The run is not complete and may stay waiting until it returns.</p>
        }
        @if (stalled()) {
          <p class="banner warn" data-state="stalled">Still running after 10 minutes. Automatic checking stopped — use Check status.</p>
        }
        @if (run.status === 'FAILED' || run.status === 'NEEDS_REVIEW') {
          <p class="banner bad" data-state="run-failed">{{ run.status === 'FAILED' ? 'The run did not complete.' : 'The run stopped and needs operator review.' }}
            @if (message(run); as m) { {{ m }} } No result is shown for this run.</p>
        }
        @if (run.status === 'SUCCEEDED' && run.result; as r) {
          <div class="labels"><span class="pill warn">demo only</span><span class="pill warn">assumed {{ r.assumedCurve }} curve</span>
            <span class="pill warn">calculated {{ r.executionLocation }}</span><span class="pill bad">not production risk</span></div>
          <h4>Booked trade (this run)</h4>
          <dl data-booked>
            <dt>Instrument</dt><dd>Treasury bill · {{ r.instrument }}</dd>
            <dt>Quantity</dt><dd>{{ usd(r.quantity, 0) }}</dd>
            <dt>Signed face</dt><dd>{{ r.currency }} {{ usd(r.signedFaceUsd, 2) }}</dd>
            <dt>Booked price</dt><dd>{{ price(r) }}</dd>
          </dl>
          <h4>Valuation of the resulting position</h4>
          <table data-valuation>
            <thead><tr><th>Valuation date</th><th class="num">Pricing result</th><th class="num">Independent check</th>
              <th class="num">Difference</th><th class="num">Tolerance</th><th>Result</th></tr></thead>
            <tbody><tr><td>{{ r.valuationDate }}</td><td class="num">{{ r.currency }} {{ usd(r.pricingUsd) }}</td><td class="num">{{ r.currency }} {{ usd(r.referenceUsd) }}</td>
              <td class="num" [title]="r.differenceUsd">{{ tiny(r.differenceUsd) }}</td><td class="num">{{ r.toleranceUsd }}</td>
              <td><span class="pill good">pass</span></td></tr></tbody>
          </table>
          <p class="sub" data-coverage>Present value: calculated. Rate sensitivity: not calculated for a bill (unsupported) — this is not zero.
            Booked price is what the trade paid; the valuation above uses the assumed curve.</p>
        }
      </article>
    }
  `,
  styles: `
    .head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
    h3 { margin: 0; font-size: 14px; } h4 { margin: 14px 0 6px; font-size: 13px; font-weight: 600; }
    .scenario { margin: 8px 0; }
    .banner.warn { background: var(--warn-soft); color: var(--warn); }
    .run { border-top: 1px solid var(--border); margin-top: 14px; padding-top: 12px; }
    .flow { display: grid; grid-template-columns: repeat(5, 1fr); list-style: none; padding: 0; margin: 6px 0 10px; gap: 14px; }
    .flow li { position: relative; padding: 8px 10px; border: 1px solid var(--border); background: #fafbfc; display: flex; flex-direction: column; border-radius: 7px; }
    .flow li:not(:last-child)::after { content: '→'; position: absolute; right: -12px; top: 50%; transform: translateY(-50%); color: var(--faint); }
    .flow li.good { background: var(--good-soft); border-color: #b7e4c7; } .flow li.warn { background: var(--warn-soft); border-color: #f5d9a8; }
    .flow li.bad { background: var(--bad-soft); border-color: #f4c7c3; }
    .labels { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0; }
    dl { display: grid; grid-template-columns: 140px 1fr; gap: 4px 10px; margin: 8px 0; font-size: 13px; }
    dt { color: var(--muted); } dd { margin: 0; }
    @media (max-width: 760px) { .flow { grid-template-columns: 1fr; gap: 12px; } .flow li:not(:last-child)::after { content: '↓'; right: 50%; top: auto; bottom: -14px; transform: none; }
      table { display: block; overflow-x: auto; } dl { grid-template-columns: 1fr; } }
  `,
})
export class TreasuryDemo implements OnInit, OnDestroy {
  readonly api = inject(Api);
  readonly view = signal<TreasuryView | null>(null);
  readonly unavailable = signal('');
  readonly checking = signal(false);
  readonly starting = signal(false);
  readonly startError = signal('');
  readonly canRetry = signal(false);
  readonly stalled = signal(false);
  private timer: ReturnType<typeof setTimeout> | undefined;
  private delay = POLL.firstMs;
  private pollingSince = 0;
  private destroyed = false;

  ngOnInit(): void { void this.check(); }
  ngOnDestroy(): void { this.destroyed = true; clearTimeout(this.timer); }

  active(run: TreasuryRun): boolean { return ACTIVE.includes(run.status); }

  /** Explicit read. Never starts anything. A failed or invalid read clears what was shown. */
  async check(): Promise<void> {
    clearTimeout(this.timer); this.stalled.set(false); this.delay = POLL.firstMs; this.pollingSince = Date.now();
    await this.read();
  }

  private async read(): Promise<void> {
    this.checking.set(true);
    const r = await this.api.load<any>('/risk/treasury-demo');
    this.checking.set(false);
    if (this.destroyed) return;
    this.apply(r.status === 200 ? r.body : null, r.status, r.body?.code);
  }

  private apply(body: any, status: number, code?: string): void {
    if (!validView(body)) {
      this.view.set(null);
      this.unavailable.set(code === 'NOT_CONFIGURED' ? 'The Treasury demo is not available on this deployment.'
        : status === 0 ? 'No reply from the server. Use Check status to try again.'
        : 'Treasury demo status is unavailable right now. Use Check status to try again.');
      return;
    }
    this.unavailable.set('');
    this.view.set(body);
    const run = body.run;
    if (run && !this.active(run)) this.forgetKey();
    if (run && this.active(run)) this.schedule();
  }

  private schedule(): void {
    clearTimeout(this.timer);
    if (Date.now() - this.pollingSince > POLL.giveUpMs) { this.stalled.set(true); return; }
    this.timer = setTimeout(() => void this.read(), this.delay);
    this.delay = Math.min(POLL.maxMs, this.delay * POLL.factor);
  }

  /** The only mutation. Single-flight; the same idempotency key is reused until the run is terminal. */
  async start(): Promise<void> {
    if (this.starting() || this.view()?.run) return;
    this.starting.set(true); this.startError.set(''); this.canRetry.set(false);
    const idempotencyKey = this.key();
    const r = await this.api.load<any>('/risk/treasury-demo/start', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ idempotencyKey }),
    });
    this.starting.set(false);
    if (this.destroyed) return;
    if ((r.status === 200 || r.status === 202) && validView(r.body)) {
      this.pollingSince = Date.now(); this.delay = POLL.firstMs;
      this.apply(r.body, 200);
      return;
    }
    const code = r.body?.code;
    const known: Record<string, string> = {
      admin_auth_required: 'Sign in as an operator to run the demo. No trade was placed.',
      WORKER_UNAVAILABLE: 'The pricing worker is not available right now. No trade was placed.',
      NOT_CONFIGURED: 'The Treasury demo is not available on this deployment. No trade was placed.',
    };
    if (r.status === 401) { this.forgetKey(); this.startError.set(known['admin_auth_required']); return; }
    if (r.status === 403) { this.forgetKey(); this.startError.set('The server refused this request. No trade was placed.'); return; }
    if (code && known[code]) { this.forgetKey(); this.startError.set(known[code]); await this.check(); return; }
    // Unknown outcome: the request may have been received. Keep the key so a retry resumes the same run.
    this.startError.set('The server did not confirm the start. Trying again is safe: it resumes the same run and cannot place a second trade.');
    this.canRetry.set(true);
    await this.check();
    if (this.view()?.run) { this.startError.set(''); this.canRetry.set(false); }
  }

  runLabel(run: TreasuryRun): string {
    return { QUEUED: 'Waiting to start', RUNNING: 'In progress', SUCCEEDED: 'Completed and checked', FAILED: 'Did not complete', NEEDS_REVIEW: 'Needs review' }[run.status];
  }

  /** Only confirmed stages are done; PRICING confirms that pricing started, not that it finished. */
  steps(run: TreasuryRun): { label: string; state: string; tone: string }[] {
    const at = STAGES.findIndex(([k]) => k === run.stage);
    const pricing = 3;
    const ok = run.status === 'SUCCEEDED';
    const stopped = run.status === 'FAILED' || run.status === 'NEEDS_REVIEW';
    const failedAt = at === pricing ? pricing : at + 1;
    return STAGES.map(([, label], i) => {
      if (ok || i < at || (i === at && i !== pricing)) return { label, state: 'done', tone: 'good' };
      if (stopped) {
        if (i === failedAt) return run.status === 'FAILED' ? { label, state: 'failed', tone: 'bad' } : { label, state: 'needs review', tone: 'warn' };
        return { label, state: 'not reached', tone: '' };
      }
      if (i === at) return { label, state: 'in progress', tone: 'warn' };
      return { label, state: i === at + 1 ? 'waiting' : 'not started', tone: '' };
    });
  }

  message(run: TreasuryRun): string {
    const m = typeof run.message === 'string' ? run.message.trim() : '';
    return m.length > 240 ? m.slice(0, 239) + '…' : m;
  }

  price(r: TreasuryResult): string {
    const n = Number(r.bookedPrice);
    return r.bookedPriceUnit === 'fraction of par'
      ? `${(n * 100).toLocaleString('en-US', { maximumFractionDigits: 6 })}% of par`
      : `${r.bookedPrice} ${r.bookedPriceUnit}`;
  }

  usd(s: string, digits = 2): string {
    return Number(s).toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits });
  }

  tiny(s: string): string {
    const v = Number(s);
    return v === 0 ? 'exact' : v.toExponential(1);
  }

  private key(): string {
    try {
      const existing = localStorage.getItem(KEY_STORAGE);
      if (existing) return existing;
      const fresh = crypto.randomUUID();
      localStorage.setItem(KEY_STORAGE, fresh);
      return fresh;
    } catch {
      return crypto.randomUUID(); // storage blocked: still safe, the server keeps a single run
    }
  }

  private forgetKey(): void { try { localStorage.removeItem(KEY_STORAGE); } catch { /* storage blocked */ } }
}
