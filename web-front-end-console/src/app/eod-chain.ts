import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
import { Gated } from './gated';
import { HelpTip } from './help';

/**
 * Deliberately `string`, not a closed union. The server owns this vocabulary and adds to it: this
 * panel was written against six states and the rig answered with a seventh (`remote`) within the
 * hour, because the extract's sink was repointed at the bucket. A closed union would have made that
 * a compile error at best and a silent mis-render at worst.
 *
 * Known at time of writing: ok · draft · pending · halted · not-configured · unreadable · remote.
 * Anything else is rendered as the server's own word, marked as unrecognised rather than shown in
 * the neutral grey that also means "pending" — an unknown state and a waiting one must not look
 * alike, or a new state silently reads as "nothing has happened yet".
 */
type StageState = string;

/** Only these are claims about health; everything else is reported, not interpreted. */
const GOOD = new Set(['ok']);
const BAD = new Set(['halted', 'unreadable']);
const WARN = new Set(['draft']);
const KNOWN = new Set(['ok', 'draft', 'pending', 'halted', 'not-configured', 'unreadable', 'remote']);

interface Stage {
  state: StageState;
  detail: string;
  rows?: number;
  pod?: string;
  bucket?: string;
  sink?: string;
  files?: string[];
}

interface Chain {
  date: string;
  /**
   * The date the TRADE-PROCESSOR thinks it is — read from that container, which is the machine whose
   * clock decides which session a close lands on. Not the console's clock and emphatically not the
   * browser's: those containers run UTC, so at 23:22 EDT the rig is already on tomorrow.
   */
  businessDate?: string;
  prices: Stage;
  pnl: Stage;
  extract: Stage;
  published: Stage;
}

/** The local calendar date, NOT `toISOString()` — see the comment on `date`. */
const localDate = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

/**
 * The end-of-day chain as one pipeline, because that is what it is.
 *
 * Prices and the risk extract have always been shown as two unrelated things, and they are one
 * sequence with a decision in the middle: closing a session mints a price version, publishing that
 * version is what STARTS the extract, and the cut that lands on the sink is the artifact of the
 * publish. An operator who does not know that reads a missing cut as a broken extract when the real
 * answer is that nobody published.
 *
 * Each stage reports its own state and the server distinguishes the ones that look alike — chiefly
 * "has not run yet" from "ran and refused", which are indistinguishable from a row count and mean
 * opposite things. This panel's whole job is to render that distinction rather than flatten it back
 * into a spinner.
 *
 * NOT POLLED. One call shells out to kubectl three times and lists a bucket; it takes seconds. The
 * refresh is manual, and the timestamp says how old the reading is so nobody mistakes a stale
 * screen for a live one.
 */
@Component({
  selector: 'eod-chain',
  imports: [FormsModule, Gated, HelpTip],
  template: `
    <div class="card-head">
      <button type="button" class="card-tog" (click)="open.set(!open())">
        <span class="arrow">{{ open() ? '▾' : '▸' }}</span><h2>Today's end of day</h2>
      </button>
      <help-tip text="The four stages are one pipeline, not four features. Closing a session mints a price version; publishing that version is what starts the risk extract; the extract takes a cut at an exact consensus sequence, byte-identical on every member; and the cut is then either published to a bucket or left on the pod, depending on where this rig points its sink. Each stage is read from a different place — prices over HTTP, PnL only as database rows, the cut from the risk-extract pod's volume, the published copy from the bucket — which is why this is one call to the console's own server rather than something the browser can assemble." />
      <span class="spacer"></span>
      <label class="field">Business date
        <input type="date" [(ngModel)]="date" name="d" [disabled]="busy()">
      </label>
      <button (click)="refresh()" [disabled]="busy()">{{ busy() ? 'reading…' : 'Refresh' }}</button>
    </div>

    @if (open()) {
      @if (error()) { <div class="banner bad">{{ error() }}</div> }

      @if (chain(); as c) {
        <!-- The date the SERVER answered about, not the one we asked for. They are the same today,
             and saying which is which costs nothing against the day they are not. -->
        @if (c.date !== date) {
          <div class="banner warn-note">Asked about {{ date }}; the server answered about
            {{ c.date }}. Everything below describes <b>{{ c.date }}</b>.</div>
        }
        @if (c.businessDate && c.businessDate !== c.date) {
          <div class="banner warn-note">This is <b>{{ c.date }}</b>, but the rig's current business
            date is <b>{{ c.businessDate }}</b> — the trade-processor's clock decides which session a
            close lands on, and it runs UTC. Closing now would land on
            <b>{{ c.businessDate }}</b> unless a date is sent with it.</div>
        }

        <div class="chain">
          @for (s of stages(); track s.key) {
            <div class="stage" [class.on]="good(s.stage.state)">
              <div class="top">
                <span class="dot" [class.d-good]="good(s.stage.state)" [class.d-bad]="bad(s.stage.state)"
                  [class.d-warn]="warn(s.stage.state)" [class.d-unknown]="!known(s.stage.state)"></span>
                <b>{{ s.label }}</b>
                <span class="pill" [class.good]="good(s.stage.state)" [class.bad]="bad(s.stage.state)"
                  [class.warn]="warn(s.stage.state)">{{ s.stage.state }}</span>
                @if (!known(s.stage.state)) {
                  <span class="sub unk">state not recognised by this console — shown as the server
                    sent it</span>
                }
              </div>
              <div class="sub">{{ s.stage.detail }}</div>
              @if (s.stage.files?.length) {
                <details>
                  <summary class="sub">{{ s.stage.files!.length }} artifact{{ s.stage.files!.length === 1 ? '' : 's' }}</summary>
                  <ul class="files">@for (f of s.stage.files; track f) { <li>{{ f }}</li> }</ul>
                </details>
              }
            </div>
            @if (!$last) { <div class="arrow-down">↓</div> }
          }
        </div>

        <div class="acts">
          <button (click)="closeSession()" [disabled]="busy()">Close session</button>
          <gated />
          <span class="sub">Closing mints a price version from the day's marks. Publishing it — on
            the panel below, after any flagged instrument is overridden — is what starts the
            extract.</span>
        </div>
        @if (actMsg(); as m) { <div class="banner" [class.good]="m.ok" [class.bad]="!m.ok">{{ m.text }}</div> }

        <div class="sub asof">Read at {{ readAt() }} · not polled, because one refresh shells out to
          the cluster three times and lists a bucket. Press Refresh after an action rather than
          waiting for this to change on its own.</div>
      } @else if (!busy()) {
        <div class="faint">press Refresh to read the chain</div>
      }
    }
  `,
  styles: `
    .spacer { flex: 1; }
    .field { display: flex; align-items: center; gap: 5px; font-size: 12px; color: var(--muted); }
    .field input { width: 140px; }
    .chain { display: grid; gap: 0; margin: 10px 0 4px; max-width: 720px; }
    .stage { border: 1px solid var(--border); border-radius: 8px; padding: 9px 11px; background: #fff; }
    .stage.on { background: var(--good-soft); border-color: transparent; }
    .top { display: flex; align-items: center; gap: 7px; font-size: 13px; }
    .dot { width: 9px; height: 9px; border-radius: 50%; flex: 0 0 auto; background: #c3c9d2; }
    .d-good { background: var(--good); } .d-bad { background: var(--bad); }
    .d-warn { background: var(--warn); }
    /* An unknown state must not wear the same grey as "pending" — see StageState. */
    .d-unknown { background: repeating-linear-gradient(45deg, #c3c9d2 0 2px, #8b93a0 2px 4px); }
    .unk { color: var(--warn); }
    .stage .sub { margin-top: 3px; }
    .arrow-down { text-align: center; color: var(--faint); font-size: 13px; line-height: 16px; }
    .files { margin: 4px 0 0; padding-left: 18px; font-family: var(--mono); font-size: 11px;
             color: var(--muted); max-height: 150px; overflow-y: auto; }
    .acts { display: flex; align-items: center; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
    .acts .sub { flex: 1 1 300px; min-width: 240px; }
    .asof { margin-top: 10px; max-width: 720px; }
    .warn-note { background: var(--warn-soft); color: var(--warn); font-size: 12.5px; }
  `,
})
export class EodChain {
  readonly api = inject(Api);
  readonly open = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly chain = signal<Chain | null>(null);
  readonly readAt = signal('');
  readonly actMsg = signal<{ ok: boolean; text: string } | null>(null);

  /**
   * Seeded from the browser only as a BOOTSTRAP, then replaced by the rig's own business date.
   *
   * The browser's date is the wrong authority twice over. `toISOString()` gives UTC, which after
   * ~19:00 local is tomorrow; and the local calendar date is not it either, because the session a
   * close lands on is decided by the trade-processor's clock, and those containers run UTC. Measured:
   * rig 2026-08-21 03:22 UTC, operator 2026-08-20 23:22 EDT, and every session row in the database
   * on 2026-08-21. An operator who closes a session at 11pm and is told "closed for 2026-08-20"
   * reasonably concludes it did not save.
   *
   * So the chain is asked once with the browser's guess, the answer carries `businessDate`, and the
   * picker adopts it. The guess is only ever wrong by a day, and being wrong costs one extra read.
   */
  date = localDate();
  /** True once the picker holds the rig's date rather than this browser's guess. */
  readonly adopted = signal(false);

  good(s: StageState): boolean { return GOOD.has(s); }
  bad(s: StageState): boolean { return BAD.has(s); }
  warn(s: StageState): boolean { return WARN.has(s); }
  known(s: StageState): boolean { return KNOWN.has(s); }

  readonly stages = computed(() => {
    const c = this.chain();
    if (!c) return [];
    return [
      { key: 'prices', label: '1 · Close session → price version', stage: c.prices },
      { key: 'pnl', label: '2 · Position service marks PnL', stage: c.pnl },
      { key: 'extract', label: '3 · Sequenced risk-extract cut', stage: c.extract },
      { key: 'published', label: '4 · Cut published to the sink', stage: c.published },
    ];
  });

  async refresh(): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      const r = await this.api.load<Chain>(`/eod/chain?date=${encodeURIComponent(this.date)}`);
      // A body check, not a status check: with no /eod proxy route the dev server answers its SPA
      // fallback — 200, with the index page — and "200 means we have a chain" would render four
      // undefined stages. Same fallthrough as /mN, /grafana and /auth before it.
      if (r.status !== 200 || !r.body || typeof r.body !== 'object' || !('prices' in r.body)) {
        this.chain.set(null);
        this.error.set(typeof r.body === 'string' || !r.body
          ? 'the EOD chain endpoint did not answer with a chain — the console server serves it, so a dev session needs the /eod proxy route'
          : `could not read the chain (HTTP ${r.status})`);
        return;
      }
      this.chain.set(r.body);
      this.readAt.set(new Date().toLocaleTimeString());
      // Adopt the rig's business date once. Only re-reads when the browser's guess was a day out,
      // which is exactly the case that used to render an entire green chain as "pending".
      const bd = r.body.businessDate;
      if (bd && !this.adopted()) {
        this.adopted.set(true);
        if (bd !== this.date) { this.date = bd; await this.refresh(); }
      }
    } finally { this.busy.set(false); }
  }

  /**
   * Gated: the server refuses this without an admin session. The 401 is left to say so through the
   * masthead prompt rather than being restated here as a failure — it is not one, it is a sign-in.
   */
  async closeSession(): Promise<void> {
    this.busy.set(true);
    this.actMsg.set(null);
    try {
      // sessionDate as a QUERY PARAM, which is what the endpoint reads. Without it the close
      // defaults to LocalDate.now() in the trade-processor's zone and the picker has no effect —
      // every close landed on the rig's UTC date whatever was selected.
      const r = await this.api.load<{ version?: number; sessionDate?: string; error?: string }>(
        `/trade-processor/eod/session/close?sessionDate=${encodeURIComponent(this.date)}`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
        });
      if (r.status === 401) {
        this.actMsg.set({ ok: false, text: 'closing a session needs an administrator — sign in from the header, then try again' });
      } else if (r.status === 200 || r.status === 201) {
        const b = r.body && typeof r.body === 'object' ? r.body : {};
        // The date the SERVER recorded, never the one this page asked for. Announcing the requested
        // date is what made a correct close read as a failed one: the banner said 2026-08-20 while
        // the row went in at 2026-08-21, and the panel below then loaded the row the banner denied.
        const landed = b.sessionDate ?? this.chain()?.businessDate ?? '(the rig\'s business date)';
        const v = b.version;
        this.actMsg.set({ ok: true, text: `session closed for ${landed}${v ? ` — price version v${v} minted` : ''}. Publish it below to start the extract.` });
        this.api.log({ kind: 'eod', ok: true, summary: `EOD session closed for ${landed}` });
      } else {
        const msg = r.body && typeof r.body === 'object' && r.body.error ? r.body.error : `HTTP ${r.status}`;
        this.actMsg.set({ ok: false, text: `close refused: ${msg}` });
        this.api.log({ kind: 'eod', ok: false, summary: `EOD session close for ${this.date} refused: ${msg}` });
      }
    } finally { this.busy.set(false); }
    await this.refresh();
  }
}
