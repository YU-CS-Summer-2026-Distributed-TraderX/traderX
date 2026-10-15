import { Component, inject, OnInit, signal } from '@angular/core';
import { Api } from './api';
interface BatchResult {
  schema: string; availability: string; status: string; usableForRisk: false;
  synthetic: true; financialValidation: false; scenarioRiskAvailable: false;
  accountId: string; runId: string; bundleId: string; batchUid: string; image: string;
  valuationTime: string; clusterEpoch: string; currentPortfolioComparison: string;
  cut: { sessionDate: string; consensusSequence: string; priceSnapshotVersion: string; cutSha256: string };
  tradeCount: number; baseNpvUsd: number;
  market: { rate: string; compounding: string; day_count: string; asof: string };
}
@Component({
  selector: 'batch-result',
  template: `
    <div class="head"><h2>Cloud Batch portfolio</h2>
      <button type="button" (click)="refresh()" [disabled]="busy()">Refresh</button></div>
    @if (busy()) { <p role="status">Verifying imported Batch result…</p> }
    @if (error()) { <p role="status" class="banner warn" data-batch-unavailable>{{ error() }}</p> }
    @if (result(); as r) {
      <p><span class="pill good" data-batch-status>Verified result</span> · Google Cloud CPU Batch</p>
      <p class="banner warn">Synthetic portfolio · assumed market inputs. Not usable for production risk.</p>
      <dl>
        <dt>Account</dt><dd data-batch-account>{{ r.accountId }}</dd>
        <dt>Portfolio NPV (USD)</dt><dd data-batch-npv>{{ money(r.baseNpvUsd) }}</dd>
        <dt>Trades</dt><dd>{{ r.tradeCount }}</dd>
        <dt>Curve assumption</dt><dd>{{ percent(r.market.rate) }} · {{ r.market.compounding }} · {{ r.market.day_count }} · as of {{ r.market.asof }}</dd>
        <dt>Valuation time</dt><dd>{{ r.valuationTime }}</dd>
        <dt>Portfolio cut</dt><dd>{{ r.cut.sessionDate }} · sequence {{ r.cut.consensusSequence }} · price version {{ r.cut.priceSnapshotVersion }}</dd>
        <dt>Run</dt><dd>{{ r.runId }}</dd>
        <dt>Scenario risk / VaR / ES</dt><dd>Unavailable</dd>
        <dt>General financial validation</dt><dd>Not performed</dd>
      </dl>
      <p class="sub">Retained result for this portfolio cut. Comparison with current positions has not been performed. Refresh rechecks local evidence; it does not contact Google Cloud.</p>
      <details><summary>Result provenance</summary><dl>
        <dt>Batch UID</dt><dd>{{ r.batchUid }}</dd><dt>Image</dt><dd>{{ r.image }}</dd>
        <dt>Bundle</dt><dd>{{ r.bundleId }}</dd><dt>Cut hash</dt><dd>{{ r.cut.cutSha256 }}</dd>
        <dt>Cluster epoch</dt><dd>{{ r.clusterEpoch }}</dd>
      </dl></details>
    }
  `,
  styles: `.head { display:flex; justify-content:space-between; align-items:center; gap:12px; }
    dl { display:grid; grid-template-columns:minmax(130px, 210px) minmax(0, 1fr); gap:8px 16px; }
    dt { color:var(--muted); } dd { margin:0; overflow-wrap:anywhere; }
    @media(max-width:600px) { dl { grid-template-columns:1fr; } }`,
})
export class BatchResultPanel implements OnInit {
  private readonly api = inject(Api);
  readonly result = signal<BatchResult | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  ngOnInit(): void { void this.refresh(); }
  money(n: number): string { return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }); }
  percent(rate: string): string { return Number(rate).toLocaleString('en-US', { style: 'percent', maximumFractionDigits: 6 }); }
  async refresh(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true); this.result.set(null); this.error.set('');
    try {
      const response = await this.api.load<BatchResult & { code?: string }>('/risk/batch-result');
      const r = response.body;
      if (response.status === 200 && r?.schema === 'traderx.batch-result.v1'
          && r.availability === 'AVAILABLE' && r.status === 'VERIFIED' && r.usableForRisk === false
          && r.synthetic === true && r.financialValidation === false && r.scenarioRiskAvailable === false
          && Number.isFinite(r.baseNpvUsd) && Number.isInteger(r.tradeCount) && r.tradeCount > 0
          && typeof r.accountId === 'string' && !!r.accountId && typeof r.runId === 'string'
          && !!r.cut?.cutSha256 && typeof r.market?.rate === 'string' && Number.isFinite(Number(r.market.rate)) && r.currentPortfolioComparison === 'NOT_CHECKED') this.result.set(r);
      else this.error.set(r?.code === 'NOT_CONFIGURED' ? 'No Batch result has been selected.'
        : 'Batch result unavailable: evidence could not be verified.');
    } catch { this.error.set('Batch result unavailable. Refresh to retry.'); }
    finally { this.busy.set(false); }
  }
}
