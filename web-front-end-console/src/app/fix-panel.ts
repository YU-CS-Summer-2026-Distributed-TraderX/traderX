import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, bridgeError, nextClientOrderId } from './api';
import { HelpTip } from './help';

/** FIX 4.4 tags this panel renders by name — enough to read a NewOrderSingle and its report. */
const TAGS: Record<string, string> = {
  '8': 'BeginString', '9': 'BodyLength', '35': 'MsgType', '34': 'MsgSeqNum', '49': 'SenderCompID',
  '56': 'TargetCompID', '52': 'SendingTime', '10': 'CheckSum', '98': 'EncryptMethod',
  '108': 'HeartBtInt', '141': 'ResetSeqNumFlag', '11': 'ClOrdID', '1': 'Account', '55': 'Symbol',
  '54': 'Side', '38': 'OrderQty', '40': 'OrdType', '44': 'Price', '21': 'HandlInst',
  '60': 'TransactTime', '37': 'OrderID', '17': 'ExecID', '150': 'ExecType', '39': 'OrdStatus',
  '151': 'LeavesQty', '14': 'CumQty', '6': 'AvgPx', '58': 'Text', '31': 'LastPx', '32': 'LastQty',
};

const MSG_TYPES: Record<string, string> = {
  A: 'Logon', '0': 'Heartbeat', '1': 'TestRequest', '2': 'ResendRequest', '3': 'Reject',
  '4': 'SequenceReset', '5': 'Logout', '8': 'ExecutionReport', D: 'NewOrderSingle',
};

const EXEC_TYPE: Record<string, string> = {
  '0': 'New', '1': 'PartialFill', '2': 'Fill', '4': 'Canceled', '8': 'Rejected', F: 'Trade',
};

interface Field { tag: string; name: string; value: string; }
interface Msg { type: string; typeName: string; raw: string; fields: Field[]; }

@Component({
  selector: 'fix-panel',
  imports: [FormsModule, HelpTip],
  template: `
    <div class="card-head">
      <h2>FIX 4.4 ingress</h2>
      <help-tip text="The second way an order reaches this system. A counterparty holds a FIX session against the gateway's own acceptor port and sends NewOrderSingle messages; the gateway terminates that session itself and forwards each order through the same submitter seam, the same consensus log and the same risk gate as an order typed into the ticket. The session's TCP connection and sequence numbers live on the gateway, entirely apart from its cluster client — so when the leader dies and the client reconnects to the new one, the counterparty stays logged on and never notices. That is the property this ingress exists to prove." />
      <span class="spacer"></span>
      <span class="pill" [class.good]="last()?.received?.length" [class.warn]="!last()">
        FIX.4.4 · CLIENT1 → TRADERX</span>
    </div>

    <!-- "the dev proxy holds the session" was true where it was written and false where it runs:
         deployed, this is server.mjs dialling the acceptor Service. Named by role instead. -->
    <div class="sub note">A browser cannot open a TCP socket, so the console cannot speak FIX
      itself: its back end holds the session for the length of one order — logon, NewOrderSingle,
      ExecutionReport, disconnect — and hands back the raw wire text of every message. Sessions are
      ephemeral by design (the acceptor stores them in memory), so a one-order session is an
      ordinary one, not a shortcut.</div>

    <form (ngSubmit)="send()">
      <label class="field">Account
        <select [(ngModel)]="accountId" name="acct">
          @for (a of api.accounts(); track a.id) { <option [value]="a.id">{{ a.displayName }} ({{ a.id }})</option> }
        </select>
      </label>
      <label class="field">Symbol <input [(ngModel)]="symbol" name="sym" spellcheck="false"></label>
      <label class="field">Side
        <select [(ngModel)]="side" name="side"><option>Buy</option><option>Sell</option></select>
      </label>
      <label class="field">Quantity <input type="number" [(ngModel)]="quantity" name="qty" min="1"></label>
      <label class="field">Limit price <input type="number" [(ngModel)]="limitPrice" name="px" step="0.01"></label>
      <button class="btn-primary" type="submit" [disabled]="busy()">{{ busy() ? 'on the wire…' : 'Send over FIX' }}</button>
      <button type="button" (click)="atMarket()" [disabled]="busy()">Price at market</button>
    </form>

    @if (last(); as r) {
      @if (r.error) { <div class="banner bad">{{ r.error }}</div> }
      @for (m of r.messages; track $index) {
        <div class="msg" [class.in]="m.dir === 'in'">
          <div class="mhead">
            <span class="dir">{{ m.dir === 'out' ? '▶ sent' : '◀ received' }}</span>
            <b>{{ m.msg.typeName }}</b> <span class="sub">35={{ m.msg.type }}</span>
            @if (verdict(m.msg); as v) { <span class="pill" [class.good]="v.ok" [class.bad]="!v.ok">{{ v.text }}</span> }
          </div>
          <pre class="raw">{{ m.msg.raw }}</pre>
          <div class="fields">
            @for (f of m.msg.fields; track $index) {
              <span class="f"><span class="t">{{ f.tag }}</span> {{ f.name }} <b>{{ f.value }}</b></span>
            }
          </div>
        </div>
      }
    }
  `,
  styles: `
    .spacer { flex: 1; }
    .note { max-width: 760px; margin-bottom: 12px; }
    form { display: flex; gap: 10px; align-items: flex-end; flex-wrap: wrap; margin-bottom: 12px; }
    form input { width: 150px; }
    .msg { border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; margin-bottom: 8px; }
    .msg.in { background: #f8f9fb; }
    .mhead { display: flex; align-items: center; gap: 8px; font-size: 13px; margin-bottom: 5px; }
    .dir { font-size: 11.5px; color: var(--faint); font-family: var(--mono); }
    .raw { font-family: var(--mono); font-size: 11.5px; color: var(--muted); background: #fff;
           border: 1px solid var(--border); border-radius: 6px; padding: 6px 8px; margin: 0 0 6px;
           overflow-x: auto; white-space: pre-wrap; word-break: break-all; }
    .fields { display: flex; flex-wrap: wrap; gap: 4px 10px; font-size: 11.5px; color: var(--muted); }
    .f b { color: var(--text); font-weight: 600; }
    .t { font-family: var(--mono); color: var(--faint); }
  `,
})
export class FixPanel {
  readonly api = inject(Api);
  accountId = 22214;
  symbol = 'IBM';
  side = 'Buy';
  quantity = 100;
  limitPrice = 0;
  readonly busy = signal(false);
  readonly last = signal<{ messages: { dir: string; msg: Msg }[]; error?: string; received?: string[] } | null>(null);

  atMarket(): void {
    const p = this.api.prices()[this.symbol.trim().toUpperCase()];
    if (p) this.limitPrice = Math.round((p.price + (this.side === 'Buy' ? 0.05 : -0.05)) * 100) / 100;
  }

  async send(): Promise<void> {
    this.busy.set(true);
    this.last.set(null);
    try {
      const clOrdId = nextClientOrderId();
      const r = await this.api.post<{ sent: string[]; received: string[]; error?: string }>('/fixorder', {
        clOrdId, accountId: Number(this.accountId), symbol: this.symbol.trim().toUpperCase(),
        side: this.side, quantity: this.quantity, limitPrice: this.limitPrice,
      });
      if (r.status !== 200 || !r.body) {
        this.last.set({ messages: [], error: bridgeError(r, 'the FIX bridge') });
        return;
      }
      // Interleaved in wire order: logon out, logon in, order out, report in.
      const messages = [
        ...(r.body.sent ?? []).map(raw => ({ dir: 'out', msg: this.parse(raw) })),
        ...(r.body.received ?? []).map(raw => ({ dir: 'in', msg: this.parse(raw) })),
      ].sort((a, b) => Number(a.msg.fields.find(f => f.tag === '34')?.value ?? 0)
        - Number(b.msg.fields.find(f => f.tag === '34')?.value ?? 0));
      this.last.set({ messages, error: r.body.error, received: r.body.received });
      const report = messages.find(m => m.msg.type === '8');
      this.api.log({
        kind: 'order', ok: !!report && this.verdict(report.msg)?.ok !== false,
        summary: `FIX ${this.side} ${this.quantity} ${this.symbol} @ ${this.limitPrice} → `
          + (report ? `${this.verdict(report.msg)?.text} (OrderID ${report.msg.fields.find(f => f.tag === '37')?.value})`
            : r.body.error ?? 'no ExecutionReport'),
        clientOrderId: clOrdId,
      });
    } finally { this.busy.set(false); }
  }

  /** An ExecutionReport's outcome, read off ExecType — the field that says what happened. */
  verdict(m: Msg): { ok: boolean; text: string } | null {
    if (m.type !== '8') return null;
    const exec = m.fields.find(f => f.tag === '150')?.value ?? '';
    const text = EXEC_TYPE[exec] ?? `ExecType ${exec}`;
    const note = m.fields.find(f => f.tag === '58')?.value;
    return { ok: exec !== '8', text: note ? `${text} — ${note}` : text };
  }

  private parse(raw: string): Msg {
    const fields: Field[] = raw.split('\x01').filter(Boolean).map(part => {
      const eq = part.indexOf('=');
      const tag = part.slice(0, eq);
      return { tag, name: TAGS[tag] ?? `tag ${tag}`, value: part.slice(eq + 1) };
    });
    const type = fields.find(f => f.tag === '35')?.value ?? '?';
    return {
      type, typeName: MSG_TYPES[type] ?? `MsgType ${type}`,
      // The wire uses SOH as its separator; rendered as | so it is readable on screen.
      raw: raw.replace(/\x01/g, '|'), fields,
    };
  }
}
