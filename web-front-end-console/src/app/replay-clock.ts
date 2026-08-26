import { Component, computed, inject, signal } from '@angular/core';
import { Api } from './api';
import { HelpTip } from './help';

/** The `taqReplay` block of price-publisher's /health, verbatim. */
interface TaqReplay {
  source?: string; symbols?: number; days?: number;
  windowSeconds?: number; compression?: number; error?: string | null;
  position?: { tapeDate?: string; dayIndex?: number; windowIndex?: number; asOf?: string; held?: boolean };
}

/**
 * Where the tape is, right now — the sentence that makes a replayed price legible.
 *
 * Without this the fact that a price is Apple's real mark from a real February morning lives only in
 * a terminal, and on screen it is just a number moving.
 *
 * <p><b>Position is rendered, never derived.</b> Everything here comes from the publisher's own
 * `/health`; nothing is advanced locally between polls and `asOf` is never differenced against this
 * machine's clock. The design property being protected is that replay position is derived in exactly
 * ONE place — a second clock in a browser is a second place, and two clocks disagree silently and
 * worst under demo load. So a stale reading here shows as a stale reading, which is the honest
 * failure: a smoothly ticking widget that has lost its source would look perfect and be fiction.
 *
 * <p>The ET rendering is formatting, not derivation: the instant comes from the tape, and only its
 * presentation is local. US equity tape times are meaningless in any other zone.
 */
@Component({
  selector: 'replay-clock',
  imports: [HelpTip],
  template: `
    @if (state() === 'tape') {
      <div class="clock" [class.held]="held()">
        <span class="pill" [class.warn]="held()" [class.good]="!held()">{{ held() ? 'HELD' : 'TAPE' }}</span>
        <span class="when">{{ when() }}</span>
        <span class="sep">·</span>
        <span class="day">day {{ dayNumber() }} of {{ replay()?.days }}</span>
        <span class="sep">·</span>
        <span class="src mono">{{ replay()?.source }}</span>
        <help-tip [text]="tip()" />
      </div>
    } @else if (state() === 'synthetic') {
      <!-- A state word, not a sentence. Mid-demo this is read at a glance or not at all, and an
           explanation of what synthetic MEANS is rationale — it belongs in this comment. The chips
           on each row already say where any individual price came from. -->
      <div class="clock">
        <span class="pill">SYNTHETIC</span>
        <span class="when" [title]="replay()?.error || 'no replay extract configured'">no tape loaded</span>
      </div>
    } @else if (state() === 'error') {
      <div class="clock bad">
        <span class="pill bad">TAPE ERROR</span>
        <span class="when">{{ replay()?.error }}</span>
      </div>
    }
  `,
  styles: `
    .clock { display: flex; align-items: center; gap: 8px; font-size: 12.5px; }
    .when { font-weight: 600; }
    .sep { color: var(--faint); }
    .src { color: var(--muted); font-size: 11.5px; }
    .mono { font-family: var(--mono); }
    .clock.bad .when { color: var(--bad); font-weight: 400; }
  `,
})
export class ReplayClock {
  private api = inject(Api);
  readonly replay = signal<TaqReplay | null>(null);
  /** Distinguishes "no tape" from "tape broken" from "reading the publisher failed". */
  readonly state = signal<'tape' | 'synthetic' | 'error' | 'unreadable'>('unreadable');

  constructor() {
    void this.poll();
    // Slower than a tick on purpose. This is a position, not an animation, and polling it hard
    // would suggest a precision the 195-second tape window does not have.
    setInterval(() => void this.poll(), 5000);
  }

  private async poll(): Promise<void> {
    const r = await this.api.load<{ taqReplay?: TaqReplay }>('/price-publisher/health');
    if (r.status !== 200 || !r.body) { this.state.set('unreadable'); return; }
    const t = r.body.taqReplay;
    this.replay.set(t ?? null);
    this.state.set(classify(t));
  }

  readonly held = computed(() => !!this.replay()?.position?.held);

  /** dayIndex is 0-based on the wire; humans count from one. */
  readonly dayNumber = computed(() => (this.replay()?.position?.dayIndex ?? 0) + 1);

  readonly when = computed(() => {
    const at = this.replay()?.position?.asOf;
    if (!at) { return this.replay()?.position?.tapeDate ?? ''; }
    const d = new Date(at);
    if (isNaN(d.getTime())) { return at; }
    const zone = 'America/New_York';
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: zone, weekday: 'long', month: 'long', day: 'numeric', year: 'numeric',
    }).formatToParts(d);
    const get = (k: string) => parts.find(p => p.type === k)?.value ?? '';
    const day = Number(get('day'));
    const time = new Intl.DateTimeFormat('en-US', {
      timeZone: zone, hour: '2-digit', minute: '2-digit', hour12: false,
    }).format(d);
    return `${get('weekday')}, ${get('month')} ${day}${ordinal(day)} ${get('year')}, ${time} ET`;
  });

  readonly tip = computed(() => {
    const t = this.replay();
    const held = this.held();
    return `Prices for ${t?.symbols ?? '?'} equity names are a recorded market session being replayed, `
      + `not a simulation — ${t?.days ?? '?'} trading days compressed ${t?.compression ?? '?'}x, `
      + `each ${t?.windowSeconds ?? '?'}s window standing for one tape interval. `
      + (held
        ? 'The tape has reached its last day and is HELD at that close: the price no longer moves and asOf stops advancing, which is the tape ending rather than the feed failing.'
        : 'Position is read from the publisher on every poll and never advanced here, so one clock decides where the tape is.')
      + ' Names not on the tape publish their own source, shown per row.';
  });
}

/**
 * Which of the three states the publisher is actually in.
 *
 * MEASURED, not assumed, and the assumption was wrong. Deleting the replay Secret — the documented,
 * rehearsed revert — does NOT remove `taqReplay`. It returns the block present, with no position and
 * `error: "no extract at /etc/taq-replay/extract.json.gz"`. Keyed on `error` alone, the sanctioned
 * fallback therefore rendered as a red TAPE ERROR: the demo's own honest mode reported as a fault,
 * which is the opposite of what the revert exists to demonstrate.
 *
 * A purely STRUCTURAL rule does not work either, and I only found that by breaking the tape on
 * purpose. Corrupting the extract produces `did not gunzip+parse: incorrect header check` — and,
 * like the absent case, no source, no days, no position. Structure cannot separate "no tape here"
 * from "the tape is broken"; the publisher distinguishes them only in words, so this matches on the
 * one phrase it emits for absence and treats everything else as a fault.
 *
 * It FAILS SAFE deliberately: an unrecognised error alarms rather than going quiet. Silence is the
 * worse mistake here, because a corrupt tape presenting as the ordinary synthetic fallback is a
 * fault that looks exactly like a normal demo.
 */
function classify(t: TaqReplay | undefined): 'tape' | 'synthetic' | 'error' {
  if (!t) { return 'synthetic'; }
  // A described tape: position, day count or source present. Then an error means a broken tape.
  if (t.source || t.days || t.position) { return t.error ? 'error' : 'tape'; }
  if (!t.error) { return 'synthetic'; }
  return /no extract at/i.test(t.error) ? 'synthetic' : 'error';
}

const ordinal = (n: number): string => {
  if (n % 100 >= 11 && n % 100 <= 13) { return 'th'; }
  return ['th', 'st', 'nd', 'rd'][n % 10] ?? 'th';
};
