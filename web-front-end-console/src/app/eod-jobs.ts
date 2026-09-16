import { Component, inject, signal, OnInit } from '@angular/core';
import { JsonPipe } from '@angular/common';
import { Api } from './api';

export interface EodJob {
  job_id: string; bundle_id: string; status: string; clusterEpoch: string; valuationTime: string;
  cut: { sessionDate: string; consensusSequence: string; priceSnapshotVersion: string; cutSha256: string };
  error: string | null; integrityError?: string; resultIntegrity: string;
  currentCut: boolean; selectionAmbiguous: boolean; selectedMockResult: boolean; selectedW0Result: boolean;
  coverage: unknown; profile: { adapter: string };
  attempts: { attempt_id: string; status: string; error: string | null }[];
}
export interface EodJobsStatus {
  schema: string; availability: string; observedAt: string; usableForRisk: false; jobs: EodJob[];
}
@Component({
  selector: 'eod-jobs',
  imports: [JsonPipe],
  template: `
    <header><h2>Overnight jobs</h2><button type="button" (click)="refresh()" [disabled]="loading()">Refresh</button></header>
    <p>A completed extract supplies inputs. Pricing and portfolio risk remain unavailable for mock and W0 jobs.</p>
    @if (loading()) { <p role="status">Reading job status…</p> }
    @if (error()) { <p role="status">{{ error() }}</p> }
    @if (data(); as view) {
      <p class="sub">Read at {{ view.observedAt }} · Worker connectivity not checked · Producer identity not authenticated</p>
      @if (!view.jobs.length) { <p>No EOD jobs have been discovered in this coordinator.</p> }
      @for (job of view.jobs; track job.job_id) {
        <article>
          <h3>{{ label(job.status) }}</h3>
          <p>{{ job.cut.sessionDate }} · sequence {{ job.cut.consensusSequence }} · price version {{ job.cut.priceSnapshotVersion }}</p>
          <dl>
            <dt>Job</dt><dd>{{ job.job_id }}</dd>
            <dt>Input bundle</dt><dd>{{ job.bundle_id }}</dd>
            <dt>Cut hash</dt><dd>{{ job.cut.cutSha256 }}</dd>
            <dt>Epoch / valuation</dt><dd>{{ job.clusterEpoch }} / {{ job.valuationTime }}</dd>
            <dt>Adapter</dt><dd>{{ job.profile.adapter }}</dd>
            <dt>Result integrity</dt><dd>{{ job.resultIntegrity }}</dd>
            <dt>Selection</dt><dd>{{ job.selectionAmbiguous ? 'Ambiguous cut' : job.selectedW0Result ? 'Current validated W0 result' : job.selectedMockResult ? 'Current mock result' : job.currentCut ? 'Current cut; no selected result' : 'Historical cut' }}</dd>
          </dl>
          @if (job.error || job.integrityError) { <p class="error">{{ job.integrityError || job.error }}</p> }
          <p>Pricing unavailable · Portfolio risk unavailable · usableForRisk=false</p>
          @if (job.coverage) { <details><summary>Validated result coverage</summary><pre>{{ job.coverage | json }}</pre></details> }
          @else { <p>Validated coverage unavailable.</p> }
          <details><summary>Attempts ({{ job.attempts.length }})</summary>
            @for (attempt of job.attempts; track attempt.attempt_id) {
              <p>{{ attempt.attempt_id }} · {{ attempt.status }} @if (attempt.error) { · {{ attempt.error }} }</p>
            }
          </details>
        </article>
      }
    }
  `,
  styles: `header { display:flex; align-items:center; justify-content:space-between; }
    article { border-top:1px solid var(--border, #555); margin-top:16px; padding-top:12px; }
    dl { display:grid; grid-template-columns:130px 1fr; gap:6px; }
    dd { margin:0; overflow-wrap:anywhere; } pre { white-space:pre-wrap; }`,
})
export class EodJobs implements OnInit {
  private api = inject(Api);
  readonly data = signal<EodJobsStatus | null>(null);
  readonly error = signal('');
  readonly loading = signal(false);
  ngOnInit() { void this.refresh(); }
  label(status: string) {
    return ({ QUEUED: 'Pending', RUNNING: 'Running / awaiting result', FAILED: 'Failed',
      MOCK_COMPLETE: 'Mock transport complete', W0_VALIDATED: 'W0 outcomes validated — no pricing' } as Record<string,string>)[status] ?? status;
  }
  async refresh() {
    this.loading.set(true); this.data.set(null); this.error.set('');
    try {
      const r = await this.api.load<EodJobsStatus>('/eod/jobs');
      if (r.status !== 200 || r.body?.schema !== 'traderx.eod-job-status.v1'
          || r.body.availability !== 'AVAILABLE' || r.body.usableForRisk !== false || !Array.isArray(r.body.jobs)) {
        this.error.set('EOD job status unavailable. The coordinator read service is not configured or could not be read.');
      } else { this.data.set(r.body); }
    } catch { this.error.set('EOD job status unavailable. Refresh to try again.'); }
    finally { this.loading.set(false); }
  }
}
