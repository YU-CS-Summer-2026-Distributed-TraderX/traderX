import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';
import { HelpTip } from './help';

interface Step { label: string; ok: boolean; text: string; }

@Component({
  selector: 'accounts-panel',
  imports: [FormsModule, HelpTip],
  template: `
    <div class="card-head">
      <h2>Trading accounts</h2>
      <help-tip text="An account exists in two places, and it needs both to trade. The account service is the directory every UI reads its name from; the matching engine keeps its own risk state, and an order from an account the engine has never been told about is refused UNKNOWN_ACCOUNT. Admission is an operator control command sequenced through consensus exactly like an order, so all three members admit the account at the same log position and a rebuilt member replays to the same state." />
    </div>

    <table>
      <thead><tr><th class="num">id</th><th>name</th><th>engine risk state</th><th></th></tr></thead>
      <tbody>
        @for (a of api.accounts(); track a.id) {
          <tr>
            <td class="num">{{ a.id }}</td>
            <td>{{ a.displayName }}</td>
            <td class="sub">{{ note()[a.id] || '—' }}</td>
            <td class="acts">
              <button (click)="admit(a.id, true)">Enable</button>
              <button (click)="admit(a.id, false)">Disable</button>
            </td>
          </tr>
        } @empty { <tr><td colspan="4" class="faint">account service unreachable</td></tr> }
      </tbody>
    </table>
    <div class="sub note">The engine exposes no read surface for account risk state — the control
      snapshot carries instruments only. This column reports what this console last applied, not
      what the engine holds; a suspended account proves itself by rejecting ACCOUNT_DISABLED.</div>

    <div class="card-head sect">
      <h2>New account</h2>
      <help-tip text="Two calls, in order: create the directory record, then admit the account to the engine's risk state. Both are reported below — if the second fails the account will list here and still reject every order, which is a confusing failure worth naming rather than hiding." />
    </div>
    <form (ngSubmit)="create()">
      <label class="field">Account id <input type="number" [(ngModel)]="newId" name="id" min="1"></label>
      <label class="field">Display name <input [(ngModel)]="newName" name="name" placeholder="Desk C"></label>
      <button class="btn-primary" type="submit" [disabled]="busy() || !newName.trim()">
        {{ busy() ? '…' : 'Create and admit' }}</button>
    </form>
    @for (s of steps(); track s.label) {
      <div class="banner" [class.good]="s.ok" [class.bad]="!s.ok"><b>{{ s.label }}</b> — {{ s.text }}</div>
    }
  `,
  styles: `
    .sect { margin-top: 20px; }
    .acts { display: flex; gap: 6px; }
    .acts button { font-size: 11.5px; padding: 1px 9px; }
    .note { margin-top: 6px; max-width: 720px; }
    form { display: flex; gap: 10px; align-items: flex-end; flex-wrap: wrap; }
    form input { width: 180px; }
    .banner { margin-top: 8px; font-size: 12.5px; }
  `,
})
export class AccountsPanel implements OnInit {
  readonly api = inject(Api);
  newId = 0;
  newName = '';
  readonly busy = signal(false);
  readonly steps = signal<Step[]>([]);
  /** accountId -> what this console last applied. Not the engine's own view; see the note. */
  readonly note = signal<Record<number, string>>({});

  readonly nextId = computed(() =>
    Math.max(0, ...this.api.accounts().map(a => Number(a.id))) + 1);

  ngOnInit(): void {
    this.api.loadAccounts().then(() => { if (!this.newId) this.newId = this.nextId(); });
  }

  async create(): Promise<void> {
    this.busy.set(true);
    this.steps.set([]);
    try {
      const id = Number(this.newId);
      const r = await this.api.post<{ id: number; displayName: string }>(
        '/account-service/account/', { id, displayName: this.newName.trim() });
      const created = r.status === 200 && !!r.body;
      this.push({ label: 'account service', ok: created,
        text: created ? `${r.body!.displayName} (${r.body!.id}) in the directory` : `HTTP ${r.status}` });
      if (!created) return;
      await this.api.loadAccounts();
      await this.admit(id, true);
      this.newId = this.nextId();
      this.newName = '';
    } finally { this.busy.set(false); }
  }

  async admit(accountId: number, enabled: boolean): Promise<void> {
    const r = await this.api.riskControl<{ applied?: boolean; version?: number; error?: string }>(
      'account', { accountId, enabled });
    const ok = r.status === 200 && !!r.body?.applied;
    const text = ok ? `${enabled ? 'admitted' : 'suspended'} at control version ${r.body!.version}`
      : r.status === 401 ? 'HTTP 401 — this rig sets its own RISK_CONTROL_TOKEN'
      : `HTTP ${r.status}`;
    this.push({ label: `engine risk state · account ${accountId}`, ok, text });
    if (ok) this.note.update(m => ({ ...m, [accountId]: enabled ? 'enabled' : 'disabled' }));
    this.api.log({ kind: 'order', ok, summary: `risk control: account ${accountId} ${enabled ? 'enabled' : 'disabled'} → ${text}` });
  }

  private push(s: Step): void { this.steps.update(l => [...l, s]); }
}
