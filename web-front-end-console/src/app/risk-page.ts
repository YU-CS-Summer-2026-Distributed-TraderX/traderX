import { Component, OnInit, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { Api } from './api';

// Read-only Risk tab. Two independent reads, each cleared before it is retried so a failed refresh
// never leaves the previous answer on screen: GET /eod/jobs (coordinator status, unchanged route)
// and GET /risk/demo (one operator-configured, server-validated synthetic pricing artifact).
// Nothing here submits, reruns or changes anything.

export interface EodJob {
  job_id: string; bundle_id: string; status: string; clusterEpoch: string; valuationTime: string;
  cut: { sessionDate: string; consensusSequence: string; priceSnapshotVersion: string; cutSha256: string };
  error: string | null; integrityError?: string; resultIntegrity: string; currentCut: boolean;
  selectionAmbiguous: boolean; selectedSyntheticPricingResult?: boolean;
  profile: { adapter: string; engineCommit?: string; assumedProfileId?: string };
  attempts: { attempt_id: string; status: string; error: string | null }[];
}
interface EodJobsStatus { schema: string; availability: string; observedAt: string; usableForRisk: false; jobs: EodJob[] }

export interface Cmp { alexUsd: string; referenceUsd: string; differenceUsd: string; toleranceUsd: string; withinTolerance: boolean }
interface Position {
  side: 'long' | 'short'; signedFaceUsd: string; npv: Cmp;
  noteDetail: null | { cleanNpv: Cmp; accruedInterest: Cmp; dirtyMinusCleanMinusAccruedUsd: string; parallelBump1bpUsd: Cmp;
    accrualReconciliation: { exportedFraction: string; recomputedFraction: string; differenceUsd: string; boundUsd: string; withinBound: boolean } };
}
export interface DemoJob {
  instrument: 'bill' | 'note'; jobId: string; bundleId: string; status: string; resultIntegrity: string;
  resultSha256: string; validatedAt: string; attemptCount: number; clusterEpoch: string;
  cut: EodJob['cut']; positions: Position[];
  coverage: { itemCount: number; byCalculation: Record<string, Record<string, number>> };
}
export interface RiskDemo {
  schema: string; usableForRisk: false; businessDate: string; valuationTime: string; assumedMarketProfile: string;
  generatedAt: string; traderxCommit: string; compatibilityProfile: { adapter: string; engineCommit: string };
  producerExecution: { location: string; completedAt: string }; jobs: DemoJob[];
}

const NOT_CONFIGURED = 'NOT_CONFIGURED';
export const CALCULATIONS: [string, string][] = [['npv', 'NPV'], ['accruedInterest', 'Accrued interest'],
  ['rateSensitivity', 'Rate sensitivity'], ['rateGamma', 'Rate gamma'], ['theta', 'Theta'], ['vega', 'Vega'], ['varEs', 'VaR / ES (per item)']];
/** Snapshot of a dated review, NOT a live capability probe. Source: review-evidence/alex-bb9cf0e/review.md. */
export const REVIEWED = { commit: 'bb9cf0e', at: '2026-09-16 16:53 UTC', pinned: 'e7246e1' };

@Component({
  selector: 'risk-page',
  template: `
<div class="stack">
  <p class="banner warn scope"><strong>Synthetic fixture validation</strong> · business date 2025-06-02 · assumed curve
    flat-3pct-v1 · <strong>not usable for production risk</strong>. Nothing on this page is live financial readiness.</p>

  <!-- 1. Integration overview ---------------------------------------------------------------- -->
  <section class="card">
    <div class="head"><h2>Integration overview</h2>
      <button type="button" (click)="loadJobs()" [disabled]="jobsLoading()">Refresh</button></div>
    <ol class="flow">
      <li><b>EOD input bundle</b><span class="sub">TraderX cut → bundle</span></li>
      <li><b>Alex result</b><span class="sub">risk engine output</span></li>
      <li><b>TraderX validation</b><span class="sub">shape, identity, integrity</span></li>
    </ol>
    <p class="sub">Worker connectivity <b>not probed</b>: this read never contacts Alex's engine, so it says nothing
      about whether it is connected or live.
      @if (jobsLastOk()) { Last successful read {{ jobsLastOk() }}. }</p>
    @if (jobsLoading()) { <p role="status" class="sub">Reading coordinator status…</p> }
    @if (jobsError(); as e) { <p role="status" class="banner bad" data-state="jobs-unavailable">{{ e }}</p> }
    @if (jobs(); as view) {
      @if (!view.jobs.length) { <p>The coordinator is configured and has no discovered EOD jobs.</p> }
      @for (job of view.jobs; track job.job_id) {
        <article class="job">
          <div class="head"><h3>{{ instrumentOf(job.bundle_id) }} job</h3>
            <span class="pill" [class.good]="tone(job) === 'good'" [class.warn]="tone(job) === 'warn'" [class.bad]="tone(job) === 'bad'"
                  data-status>{{ statusLabel(job.status) }}</span></div>
          <ol class="flow steps">
            @for (s of stages(job); track $index) { <li [class]="s.tone"><b>{{ s.state }}</b><span class="sub">{{ s.detail }}</span></li> }
          </ol>
          <dl>
            <dt>Job</dt><dd class="mono">{{ job.job_id }}</dd>
            <dt>Input bundle</dt><dd class="mono">{{ job.bundle_id }}</dd>
            <dt>Cut</dt><dd>{{ job.cut.sessionDate }} · sequence {{ job.cut.consensusSequence }} · price version {{ job.cut.priceSnapshotVersion }}</dd>
            <dt>Cut hash</dt><dd class="mono">{{ job.cut.cutSha256 }}</dd>
            <dt>Epoch / valuation</dt><dd>{{ job.clusterEpoch }} · {{ job.valuationTime }}</dd>
            <dt>Run profile</dt><dd>{{ job.profile.adapter }}@if (job.profile.engineCommit) { · engine {{ job.profile.engineCommit.slice(0, 7) }} }@if (job.profile.assumedProfileId) { · {{ job.profile.assumedProfileId }} }</dd>
            <dt>Attempts</dt><dd>{{ job.attempts.length }}@if (job.attempts.length) { · last {{ job.attempts[job.attempts.length - 1].status }} }</dd>
            <dt>Result integrity</dt><dd>{{ job.resultIntegrity }}</dd>
            <dt>Selection</dt><dd data-selection>{{ selection(job) }}</dd>
          </dl>
          @if (job.integrityError || job.error) { <p class="banner bad">{{ job.integrityError || job.error }}</p> }
        </article>
      }
    }
  </section>

  <!-- 2. Synthetic pricing comparison ---------------------------------------------------------- -->
  <section class="card">
    <div class="head"><h2>Synthetic pricing comparison</h2>
      <button type="button" (click)="loadDemo()" [disabled]="demoLoading()">Refresh</button></div>
    <p class="sub">Alex's NPV against an independent Decimal reference calculated by TraderX (no Alex code). Signed USD.</p>
    @if (demoLoading()) { <p role="status" class="sub">Reading validated demo artifact…</p> }
    @if (demoError(); as e) { <p role="status" class="banner bad" data-state="demo-unavailable">{{ e }}</p> }
    @if (demo(); as d) {
      <p class="labels"><span class="pill warn">synthetic fixture validation</span><span class="pill warn">date {{ d.businessDate }}</span>
        <span class="pill warn">assumed {{ d.assumedMarketProfile }}</span><span class="pill bad">not usable for production risk</span></p>
      <p class="sub">Producer executed locally (operator machine) at {{ d.producerExecution.completedAt }} · static evidence served by this console ·
        report generated {{ d.generatedAt }} · valuation {{ d.valuationTime }}</p>
      @for (job of d.jobs; track job.jobId) {
        <article class="job" [attr.data-instrument]="job.instrument">
          <div class="head"><h3>{{ job.instrument === 'bill' ? 'Treasury bill' : 'Treasury note' }}</h3>
            <span class="pill good">{{ statusLabel(job.status) }}</span></div>
          <table>
            <thead><tr><th>Position</th><th>Measure</th><th class="num">Alex</th><th class="num">Reference</th>
              <th class="num">Difference</th><th class="num">Tolerance</th><th>Result</th></tr></thead>
            <tbody>
            @for (p of job.positions; track p.side) {
              <tr><td>{{ p.side }} USD {{ usd(p.signedFaceUsd, 0) }} face</td><td>NPV (dirty)</td><ng-container *ngTemplateOutlet="cmp; context: { $implicit: p.npv }" /></tr>
              @if (p.noteDetail; as n) {
                <tr><td></td><td>Clean NPV</td><ng-container *ngTemplateOutlet="cmp; context: { $implicit: n.cleanNpv }" /></tr>
                <tr><td></td><td>Exported accrued</td><ng-container *ngTemplateOutlet="cmp; context: { $implicit: n.accruedInterest }" /></tr>
                <tr><td></td><td>Price change, +1bp parallel</td><ng-container *ngTemplateOutlet="cmp; context: { $implicit: n.parallelBump1bpUsd }" /></tr>
              }
            }
            </tbody>
          </table>
          @if (job.instrument === 'note') {
            <h4>Clean / dirty / accrued reconciliation</h4>
            <table>
              <thead><tr><th>Position</th><th class="num">Dirty − clean − accrued</th><th class="num">Exported fraction</th>
                <th class="num">Recomputed ICMA</th><th class="num">Accrual difference</th><th class="num">Bound</th><th>Result</th></tr></thead>
              <tbody>
              @for (p of job.positions; track p.side) { @if (p.noteDetail; as n) {
                <tr><td>{{ p.side }}</td><td class="num" [title]="n.dirtyMinusCleanMinusAccruedUsd">{{ tiny(n.dirtyMinusCleanMinusAccruedUsd) }}</td>
                  <td class="num">{{ n.accrualReconciliation.exportedFraction }}</td><td class="num" [title]="n.accrualReconciliation.recomputedFraction">{{ n.accrualReconciliation.recomputedFraction.slice(0, 14) }}…</td>
                  <td class="num">{{ usd(n.accrualReconciliation.differenceUsd, 6) }}</td><td class="num">{{ usd(n.accrualReconciliation.boundUsd, 6) }}</td>
                  <td><span class="pill" [class.good]="n.accrualReconciliation.withinBound" [class.bad]="!n.accrualReconciliation.withinBound">{{ n.accrualReconciliation.withinBound ? 'within bound' : 'outside bound' }}</span></td></tr>
              } }
              </tbody>
            </table>
            <p class="sub">+1bp is the signed USD price change P(3.01%) − P(3.00%) for the whole position — not a per-unit derivative and not per-pillar DV01.</p>
          }
          <details><summary class="sub">Job identity and custody</summary>
            <dl>
              <dt>Job</dt><dd class="mono">{{ job.jobId }}</dd><dt>Input bundle</dt><dd class="mono">{{ job.bundleId }}</dd>
              <dt>Cut</dt><dd>{{ job.cut.sessionDate }} · sequence {{ job.cut.consensusSequence }} · price version {{ job.cut.priceSnapshotVersion }} · {{ job.clusterEpoch }}</dd>
              <dt>Result integrity</dt><dd>{{ job.resultIntegrity }} · validated {{ job.validatedAt }} · {{ job.attemptCount }} attempt(s)</dd>
              <dt>Result sha256</dt><dd class="mono">{{ job.resultSha256 }}</dd>
              <dt>Profile</dt><dd>{{ d.compatibilityProfile.adapter }} · engine {{ d.compatibilityProfile.engineCommit.slice(0, 7) }} · TraderX {{ d.traderxCommit.slice(0, 7) }}</dd>
            </dl>
          </details>
        </article>
      }
    }
    <ng-template #cmp let-c>
      <td class="num">{{ usd(c.alexUsd) }}</td><td class="num">{{ usd(c.referenceUsd) }}</td>
      <td class="num" [title]="c.differenceUsd">{{ tiny(c.differenceUsd) }}</td><td class="num">{{ c.toleranceUsd }}</td>
      <td><span class="pill" [class.good]="c.withinTolerance" [class.bad]="!c.withinTolerance">{{ c.withinTolerance ? 'pass' : 'fail' }}</span></td>
    </ng-template>
  </section>

  <!-- 3. Coverage and remaining work ----------------------------------------------------------- -->
  <section class="card">
    <h2>Coverage and remaining work</h2>
    <p class="sub">Per-calculation outcomes from the validated result coverage of each job. Unsupported is not zero.</p>
    @if (demo(); as d) {
      <table data-coverage>
        <thead><tr><th>Calculation</th><th>Bill</th><th>Note</th></tr></thead>
        <tbody>
          @for (c of calculations; track c[0]) {
            <tr><td>{{ c[1] }}</td>
              @for (job of d.jobs; track job.jobId) { <td><span class="pill" [class.good]="outcome(job, c[0]).tone === 'good'" [class.warn]="outcome(job, c[0]).tone === 'warn'" [class.bad]="outcome(job, c[0]).tone === 'bad'">{{ outcome(job, c[0]).label }}</span></td> }
            </tr>
          }
        </tbody>
      </table>
    } @else {
      <p class="banner bad" data-state="coverage-unavailable">Calculation coverage unavailable: no validated synthetic result is loaded.</p>
    }
    <table class="remaining">
      <thead><tr><th>Capability</th><th>State</th><th>Basis</th></tr></thead>
      <tbody>
        <tr><td>Bill rate sensitivity</td><td><span class="pill warn">unsupported</span></td><td>No pricer at this stage; reported unsupported, never 0</td></tr>
        <tr><td>Note rate gamma, theta</td><td><span class="pill warn">unsupported</span></td><td>Note has NPV and the +1bp parallel bump only</td></tr>
        <tr><td>SOFR swaps</td><td><span class="pill warn">unsupported</span></td><td>Outside the accepted profile; fails closed</td></tr>
        <tr><td>Equity</td><td><span class="pill warn">unsupported</span></td><td>Outside the accepted profile; fails closed</td></tr>
        <tr><td>Portfolio VaR / ES</td><td><span class="pill bad">unavailable</span></td><td>Not produced; per-item "not applicable" does not imply it</td></tr>
        <tr><td>Instrument terms v2</td><td><span class="pill">pending</span></td><td>Reviewed: terms-v2 join still refused; bundles carry terms v1</td></tr>
        <tr><td>Versioned result schemas</td><td><span class="pill">pending</span></td><td>Reviewed: no producer result schema version (W1.6 not delivered)</td></tr>
        <tr><td>Durable EOD HTTP service</td><td><span class="pill">pending</span></td><td>Reviewed: planned, not delivered; results arrive as local files</td></tr>
      </tbody>
    </table>
    <p class="sub">"Pending" rows come from the review of Alex's engine at commit {{ reviewed.commit }} on {{ reviewed.at }}, not a live
      capability probe. The accepted pricing profile stays pinned to {{ reviewed.pinned }}.</p>
  </section>
</div>`,
  styles: `
    .stack { display: grid; gap: 14px; max-width: 1100px; }
    .head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
    h3 { margin: 0; font-size: 14px; } h4 { margin: 14px 0 6px; font-size: 13px; font-weight: 600; }
    .scope { margin: 0; }
    .banner.warn { background: var(--warn-soft); color: var(--warn); }
    .flow { display: grid; grid-template-columns: repeat(3, 1fr); gap: 0; list-style: none; padding: 0; margin: 6px 0 10px; counter-reset: s; }
    .flow li { position: relative; padding: 8px 12px 8px 14px; border: 1px solid var(--border); background: #fafbfc;
               display: flex; flex-direction: column; margin-right: 14px; border-radius: 7px; }
    .flow li:not(:last-child)::after { content: '→'; position: absolute; right: -13px; top: 50%; transform: translateY(-50%); color: var(--faint); }
    .flow li.good { background: var(--good-soft); border-color: #b7e4c7; } .flow li.warn { background: var(--warn-soft); border-color: #f5d9a8; }
    .flow li.bad { background: var(--bad-soft); border-color: #f4c7c3; }
    .job { border-top: 1px solid var(--border); margin-top: 14px; padding-top: 12px; }
    dl { display: grid; grid-template-columns: 140px 1fr; gap: 4px 10px; margin: 8px 0; font-size: 13px; }
    dt { color: var(--muted); } dd { margin: 0; overflow-wrap: anywhere; }
    .mono { font-family: var(--mono); font-size: 12px; }
    .labels { display: flex; flex-wrap: wrap; gap: 6px; margin: 4px 0; }
    .pill:not(.good):not(.bad):not(.warn) { background: #eef0f3; color: var(--muted); }
    .remaining { margin-top: 14px; } table { margin-top: 6px; }
    details { margin-top: 8px; }
    @media (max-width: 760px) { .flow { grid-template-columns: 1fr; } .flow li { margin: 0 0 14px; } .flow li:not(:last-child)::after { content: '↓'; right: 50%; top: auto; bottom: -15px; transform: none; }
      table { display: block; overflow-x: auto; } dl { grid-template-columns: 1fr; } }
  `,
  imports: [NgTemplateOutlet],
})
export class RiskPage implements OnInit {
  private api = inject(Api);
  readonly calculations = CALCULATIONS;
  readonly reviewed = REVIEWED;
  readonly jobs = signal<EodJobsStatus | null>(null);
  readonly jobsError = signal('');
  readonly jobsLoading = signal(false);
  readonly jobsLastOk = signal('');
  readonly demo = signal<RiskDemo | null>(null);
  readonly demoError = signal('');
  readonly demoLoading = signal(false);

  ngOnInit(): void { void this.loadJobs(); void this.loadDemo(); }

  async loadJobs(): Promise<void> {
    this.jobsLoading.set(true); this.jobs.set(null); this.jobsError.set('');
    const r = await this.api.load<EodJobsStatus & { code?: string }>('/eod/jobs');
    const b = r.body;
    if (r.status === 200 && b?.schema === 'traderx.eod-job-status.v1' && b.availability === 'AVAILABLE'
        && b.usableForRisk === false && Array.isArray(b.jobs)) {
      this.jobs.set(b); this.jobsLastOk.set(b.observedAt);
    } else {
      this.jobsError.set(b?.code === NOT_CONFIGURED
        ? 'Coordinator status unavailable: no EOD coordinator is configured on this deployment. This is not an empty result.'
        : 'Coordinator status unavailable: the status read failed or returned an unrecognised response. Refresh to retry.');
    }
    this.jobsLoading.set(false);
  }

  async loadDemo(): Promise<void> {
    this.demoLoading.set(true); this.demo.set(null); this.demoError.set('');
    const r = await this.api.load<{ schema: string; availability: string; code?: string; artifact?: RiskDemo }>('/risk/demo');
    const a = r.body?.artifact;
    // The server validates the closed contract; this guards against a wrong or stale upstream answer.
    if (r.status === 200 && r.body?.availability === 'AVAILABLE' && a?.schema === 'traderx.risk-demo.v1'
        && a.usableForRisk === false && Array.isArray(a.jobs) && a.jobs.length === 2
        && a.jobs[0].instrument === 'bill' && a.jobs[1].instrument === 'note') {
      this.demo.set(a);
    } else {
      this.demoError.set(r.body?.code === NOT_CONFIGURED
        ? 'Synthetic pricing comparison unavailable: no validated demo artifact is configured on this deployment.'
        : r.body?.code === 'ARTIFACT_INVALID'
          ? 'Synthetic pricing comparison unavailable: the configured artifact is missing or failed validation.'
          : 'Synthetic pricing comparison unavailable: the read failed. Refresh to retry.');
    }
    this.demoLoading.set(false);
  }

  statusLabel(status: string): string {
    return ({ QUEUED: 'Queued', RUNNING: 'Awaiting result', FAILED: 'Failed', MOCK_COMPLETE: 'Mock transport only',
      W0_VALIDATED: 'W0 outcomes validated — no pricing', SYNTHETIC_PRICING_VALIDATED: 'Synthetic pricing validated' } as Record<string, string>)[status]
      ?? `Unrecognised status ${status}`;
  }

  tone(job: EodJob): 'good' | 'warn' | 'bad' | '' {
    if (job.status === 'FAILED' || job.resultIntegrity === 'INVALID') return 'bad';
    if (job.status === 'SYNTHETIC_PRICING_VALIDATED' && job.resultIntegrity === 'VERIFIED') return 'good';
    return job.status === 'QUEUED' ? '' : 'warn';
  }

  selection(job: EodJob): string {
    if (job.selectedSyntheticPricingResult) return 'Current validated synthetic pricing result';
    if (job.selectionAmbiguous) return 'Shared synthetic cut: bill and note are separate jobs on the same cut, so no single current result is selected';
    return job.currentCut ? 'Current cut; no selected result' : 'Historical cut';
  }

  instrumentOf(bundleId: string): string {
    return ({ c3211337d0c3e61b5276613972fd6af9b6e7e8ec18070019963c61fc8c1b525d: 'Synthetic bill',
      '1b64bdcb2497423a72ddd7ca70b664a9a1cf6b8b6b595a562b87e6b2ac211dc9': 'Synthetic note' } as Record<string, string>)[bundleId] ?? 'EOD';
  }

  /** The three flow stages for one job. Each job is its own run; bill and note are never combined. */
  stages(job: EodJob): { state: string; detail: string; tone: string }[] {
    const bundle = { state: 'Bundle discovered', detail: `cut ${job.cut.sessionDate} seq ${job.cut.consensusSequence}`, tone: 'good' };
    const invalid = job.resultIntegrity === 'INVALID';
    switch (job.status) {
      case 'QUEUED': return [bundle, { state: 'Not yet requested', detail: 'queued', tone: '' }, { state: 'Not started', detail: '—', tone: '' }];
      case 'RUNNING': return [bundle, { state: 'Awaiting result', detail: 'no result file yet', tone: 'warn' }, { state: 'Pending', detail: 'nothing to validate', tone: '' }];
      case 'FAILED': return [bundle, { state: 'Attempt failed', detail: 'see public error code', tone: 'bad' }, { state: 'Failed', detail: 'no accepted result', tone: 'bad' }];
      case 'MOCK_COMPLETE': return [bundle, { state: 'Mock transport', detail: 'not Alex, not pricing', tone: 'warn' },
        { state: invalid ? 'Integrity invalid' : 'Mock result', detail: 'unusable for risk', tone: invalid ? 'bad' : 'warn' }];
      case 'W0_VALIDATED': return [bundle, { state: 'W0 outcomes', detail: 'no pricing', tone: 'warn' },
        { state: invalid ? 'Integrity invalid' : 'W0 validated', detail: 'outcomes only, no priced risk', tone: invalid ? 'bad' : 'warn' }];
      case 'SYNTHETIC_PRICING_VALIDATED': return [bundle, { state: 'Result received', detail: 'synthetic fixture pricing', tone: 'good' },
        { state: invalid ? 'Integrity invalid' : 'Synthetic pricing validated', detail: invalid ? 'stored result failed custody check' : 'fixture only, usableForRisk=false', tone: invalid ? 'bad' : 'good' }];
      default: return [bundle, { state: 'Unknown', detail: job.status, tone: 'bad' }, { state: 'Unknown', detail: '—', tone: 'bad' }];
    }
  }

  outcome(job: DemoJob, calc: string): { label: string; tone: string } {
    const counts = job.coverage.byCalculation[calc];
    const n = job.coverage.itemCount;
    const all = (s: string) => counts?.[s] === n;
    if (!counts) return { label: 'unavailable', tone: 'bad' };
    if (all('ok')) return { label: calc === 'rateSensitivity' ? '+1bp parallel bump' : 'computed', tone: 'good' };
    if (all('unsupported')) return { label: 'unsupported', tone: 'warn' };
    if (all('notApplicable')) return { label: 'not applicable', tone: '' };
    if (all('failed')) return { label: 'failed', tone: 'bad' };
    if (all('unavailable')) return { label: 'unavailable', tone: 'bad' };
    return { label: Object.entries(counts).filter(([, v]) => v).map(([k, v]) => `${v} ${k}`).join(', '), tone: 'warn' };
  }

  usd(s: string, digits = 2): string {
    return Number(s).toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits });
  }

  /** Differences sit far below a cent; show magnitude honestly instead of rounding to "0.00". */
  tiny(s: string): string {
    const v = Number(s);
    return v === 0 ? 'exact' : v.toExponential(1);
  }
}
