import { Component, computed, inject, signal, OnDestroy } from '@angular/core';
import { Api } from './api';

/** The sandbox publisher's `/replay/status`, verbatim. */
interface SandboxStatus {
  controls?: boolean;
  tape?: {
    source?: string; symbols?: number; days?: number; compression?: number;
    paused?: boolean; error?: string | null;
    position?: { tapeDate?: string; dayIndex?: number; windowIndex?: number; asOf?: string; held?: boolean } | null;
  };
  flow?: { enabled?: boolean; submitted?: number; rejected?: number; symbols?: number };
  days?: string[];
  windowsPerDay?: number | null;
}

interface EngineHealth {
  role?: string; applied?: number; trades?: number; phase?: string; started?: boolean;
}

/**
 * ADR-073 — the sandbox's transport.
 *
 * <p><b>Every reading here comes from the sandbox, and nothing here can reach the live venue.</b>
 * The panel talks only to `/sandbox/*`, which the console proxies to the sandbox gateway, engine and
 * replay driver. That is the point of the feature, so it is also the thing the panel must not quietly
 * undermine: there is no live endpoint in this file.
 *
 * <p><b>Position is rendered, never advanced locally.</b> Same rule replay-clock states and for the
 * same reason — the tape's position is derived in exactly one place, server-side, and a browser that
 * ticked its own copy between polls would be a second clock that disagrees silently. A stale reading
 * shows as stale; a smooth one that had lost its source would look perfect and be fiction.
 */
@Component({
  selector: 'sandbox-panel',
  template: `
    <header class="head">
      <h2>Replay session</h2>
      <span class="pill" [class.good]="running()" [class.warn]="paused()">
        {{ paused() ? 'PAUSED' : running() ? 'RUNNING' : 'NO TAPE' }}
      </span>
    </header>

    @if (error()) {
      <p class="err">{{ error() }}</p>
    }

    @if (tape(); as t) {
      <dl class="grid">
        <dt>Tape date</dt><dd class="mono">{{ t.position?.tapeDate ?? '—' }}</dd>
        <dt>Window</dt><dd class="mono">{{ t.position?.windowIndex ?? '—' }} of {{ windowsPerDay() }}</dd>
        <dt>As of</dt><dd class="mono">{{ t.position?.asOf ?? '—' }}</dd>
        <dt>Corpus</dt><dd>{{ t.symbols ?? 0 }} symbols · {{ t.days ?? 0 }} days · {{ t.compression ?? 0 }}x</dd>
      </dl>
    }

    @if (engine(); as e) {
      <dl class="grid">
        <dt>Sandbox engine</dt>
        <dd class="mono">{{ e.role ?? '—' }} · applied {{ e.applied ?? 0 }} · trades {{ e.trades ?? 0 }}</dd>
      </dl>
    }

    <div class="controls">
      <button type="button" (click)="toggle()" [disabled]="busy() || !hasTape()">
        {{ paused() ? 'Resume' : 'Pause' }}
      </button>
      <label>Jump to
        <select [value]="selectedDay()" (change)="pick($any($event.target).value)" [disabled]="busy() || !days().length">
          @for (d of days(); track d) { <option [value]="d">{{ d }}</option> }
        </select>
      </label>
      <button type="button" (click)="seek()" [disabled]="busy() || !selectedDay()">Go</button>
    </div>

    <!-- Resetting SANDBOX STATE is not the same action as rewinding the tape, so this is a
         separate control from the transport above and says which one it is. The tape is the
         driver's clock; the book, positions and contracts live in the engine, and this clears
         those by issuing ADR-073's sequenced command -- not by rewinding anything. -->
    <div class="reset">
      <button type="button" class="danger" (click)="confirmReset()" [disabled]="busy()">
        {{ confirming() ? 'Confirm reset' : 'Reset sandbox state' }}
      </button>
      @if (confirming()) {
        <button type="button" (click)="confirming.set(false)">Cancel</button>
      }
      <span class="muted">
        @if (resetNote()) { {{ resetNote() }} }
        @else { Clears orders, positions and contracts. Keeps accounts, instruments and the tape. }
      </span>
    </div>
  `,
  styles: `
    .head { display: flex; align-items: baseline; gap: 10px; }
    .pill { font-size: 11px; letter-spacing: .06em; padding: 2px 8px; border-radius: 999px;
            border: 1px solid var(--line); }
    .pill.good { color: var(--good); border-color: var(--good); }
    .pill.warn { color: var(--warn); border-color: var(--warn); }
    .grid { display: grid; grid-template-columns: max-content 1fr; gap: 4px 14px; margin: 10px 0; }
    dt { color: var(--muted); }
    dd { margin: 0; }
    .mono { font-variant-numeric: tabular-nums; font-family: var(--mono, ui-monospace, monospace); }
    .controls { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }
    .err { color: var(--bad); }
    .reset { display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
             margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--line); }
    .danger { color: var(--bad); }
    .muted { color: var(--muted); font-size: 12px; }
  `,
})
export class SandboxPanel implements OnDestroy {
  private readonly api = inject(Api);

  readonly status = signal<SandboxStatus | null>(null);
  readonly engine = signal<EngineHealth | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly selectedDay = signal('');
  readonly confirming = signal(false);
  readonly resetNote = signal('');

  readonly tape = computed(() => this.status()?.tape ?? null);
  readonly hasTape = computed(() => !!this.tape()?.position);
  readonly paused = computed(() => this.tape()?.paused === true);
  readonly running = computed(() => this.hasTape() && !this.paused());
  // Both come from the server's own account of the corpus. Deriving either here would be this
  // file asserting the tape's shape from memory, and it would keep asserting it after the corpus
  // changed underneath.
  readonly days = computed(() => this.status()?.days ?? []);
  readonly windowsPerDay = computed(() => this.status()?.windowsPerDay ?? '—');

  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    void this.refresh();
    this.timer = setInterval(() => void this.refresh(), 4000);
  }

  ngOnDestroy(): void {
    if (this.timer) { clearInterval(this.timer); }
  }

  async refresh(): Promise<void> {
    const s = await this.api.load<SandboxStatus>('/sandbox/pub/replay/status');
    if (s.status === 401) { this.error.set('Sign in to use the sandbox.'); return; }
    if (s.status !== 200 || !s.body) { this.error.set('The sandbox replay driver is not answering.'); return; }
    this.error.set(s.body.tape?.error ?? null);
    this.status.set(s.body);
    if (!this.selectedDay() && s.body.days?.length) { this.selectedDay.set(s.body.days[0]); }
    const e = await this.api.load<EngineHealth>('/sandbox/engine/health');
    this.engine.set(e.status === 200 ? e.body : null);
  }

  pick(v: string): void { this.selectedDay.set(v); }

  /**
   * Two clicks, because this one is not undoable and the tape controls beside it are. The second
   * click is the action; the first only arms it. No modal -- the button's own label carries the
   * state, so there is nothing to dismiss and nothing that can be confirmed by reflex.
   */
  async confirmReset(): Promise<void> {
    if (!this.confirming()) { this.confirming.set(true); return; }
    this.confirming.set(false);
    this.busy.set(true);
    const r = await this.api.load<{ sequence: number; clearedOrders: number;
      clearedContracts: number; clearedQueued: number; error?: string }>(
      '/sandbox/gw/sandbox/reset', { method: 'POST' });
    this.busy.set(false);
    if (r.status === 404) {
      // The venue does not offer the capability at all, which is what a live tier answers.
      this.error.set('This venue does not offer a reset.');
      return;
    }
    if (r.status !== 200 || !r.body) {
      this.error.set(r.body?.error ? `reset refused: ${r.body.error}` : `reset refused (${r.status})`);
      return;
    }
    this.error.set(null);
    // Report what was actually dropped. "Reset" alone cannot tell a cleared venue from one that
    // was already empty, and those are different answers when a session looked wrong.
    const b = r.body;
    this.resetNote.set(`Cleared at sequence ${b.sequence}: ${b.clearedOrders} order(s), `
      + `${b.clearedContracts} contract(s), ${b.clearedQueued} queued.`);
    await this.refresh();
  }

  async toggle(): Promise<void> {
    const to = this.paused() ? 'resume' : 'pause';
    await this.post(`/sandbox/pub/replay/${to}`, {});
  }

  async seek(): Promise<void> {
    const day = this.selectedDay();
    if (!day) { return; }
    await this.post('/sandbox/pub/replay/seek', { day });
  }

  private async post(url: string, body: unknown): Promise<void> {
    this.busy.set(true);
    const r = await this.api.load<SandboxStatus>(url, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    this.busy.set(false);
    if (r.status === 401) { this.error.set('Sign in to use the sandbox.'); return; }
    if (r.status !== 200) {
      // Report what the server said, not a generic failure — a refused seek names the days it has.
      const b = r.body as unknown as { error?: string; cause?: string } | null;
      this.error.set(b?.error ? `${b.error}${b.cause ? ` (${b.cause})` : ''}` : `sandbox refused (${r.status})`);
      return;
    }
    this.error.set(null);
    await this.refresh();
  }
}
