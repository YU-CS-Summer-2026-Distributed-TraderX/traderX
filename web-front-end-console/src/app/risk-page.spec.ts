import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Api } from './api';
import { RiskPage } from './risk-page';
import { routes } from './app.config';
// The same artifact the Node reader test accepts (real e7246e1 fixture outputs vs independent reference).
import fixture from '../../test-fixtures/risk-demo-synthetic.json';

type Reply = { status: number; body: any };
describe('Risk tab (read-only)', () => {
  let replies: Record<string, Reply>;
  let pending: Record<string, (r: Reply) => void>;
  const job = (status: string, extra: object = {}) => ({ job_id: 'job-' + status, bundle_id: 'c3211337d0c3e61b5276613972fd6af9b6e7e8ec18070019963c61fc8c1b525d',
    status, clusterEpoch: 'synthetic-shared-examples-v1', valuationTime: '2025-06-02T16:00:00-04:00',
    cut: { sessionDate: '2025-06-02', consensusSequence: '8', priceSnapshotVersion: '1', cutSha256: 'c'.repeat(64) },
    error: null, resultIntegrity: 'NOT_AVAILABLE', currentCut: true, selectionAmbiguous: false,
    profile: { adapter: 'alex-pricing-local-provisional-v1' }, attempts: [], ...extra });
  const jobs = (...list: object[]): Reply => ({ status: 200, body: { schema: 'traderx.eod-job-status.v1', availability: 'AVAILABLE',
    observedAt: '2026-09-16T18:00:00Z', usableForRisk: false, workerConnectivity: 'NOT_PROBED', jobs: list } });
  const demo = (): Reply => ({ status: 200, body: { schema: 'traderx.risk-demo.v1', availability: 'AVAILABLE', artifact: structuredClone(fixture) } });

  beforeEach(() => {
    replies = {}; pending = {};
    const load = (url: string) => url in replies ? Promise.resolve(replies[url]) : new Promise<Reply>((r) => (pending[url] = r));
    TestBed.configureTestingModule({ imports: [RiskPage], providers: [{ provide: Api, useValue: { load } }] });
  });
  async function render() {
    const f = TestBed.createComponent(RiskPage); f.detectChanges(); await f.whenStable(); f.detectChanges();
    return { f, text: () => (f.detectChanges(), f.nativeElement.textContent as string), el: f.nativeElement as HTMLElement };
  }

  it('shows loading while both reads are in flight', async () => {
    const f = TestBed.createComponent(RiskPage); f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Checking calculation status');
    expect(f.nativeElement.textContent).toContain('Loading pricing results');
    expect(f.nativeElement.textContent).not.toContain('pass');
  });

  it('reports missing configuration as unavailable, never an empty success or zero values', async () => {
    replies['/eod/jobs'] = { status: 503, body: { availability: 'UNAVAILABLE', code: 'NOT_CONFIGURED' } };
    replies['/risk/demo'] = { status: 503, body: { availability: 'UNAVAILABLE', code: 'NOT_CONFIGURED' } };
    const { text, el } = await render();
    expect(text()).toContain('results have not been configured');
    expect(text()).toContain('no validated examples are configured');
    expect(text()).not.toContain('No calculations are available yet');
    expect(el.querySelector('[data-state=coverage-unavailable]')).not.toBeNull();
    expect(el.querySelector('[data-coverage]')).toBeNull();
    expect(text()).not.toMatch(/\b0\.00\b|\$0|exact|pass\b/);
  });

  it('treats a corrupt or wrong-shaped answer as unavailable', async () => {
    replies['/eod/jobs'] = { status: 200, body: '<html>' };
    replies['/risk/demo'] = { status: 200, body: { availability: 'AVAILABLE', artifact: { schema: 'traderx.risk-demo.v1', usableForRisk: true, jobs: [] } } };
    const { text } = await render();
    expect(text()).toContain('Calculation status unavailable');
    expect(text()).toContain('Pricing results unavailable');
    replies['/risk/demo'] = { status: 503, body: { code: 'ARTIFACT_INVALID' } };
    const again = await render();
    expect(again.text()).toContain('failed validation');
  });

  it('shows a network error and clears stale data when a refresh fails', async () => {
    replies['/eod/jobs'] = jobs(job('SYNTHETIC_PRICING_VALIDATED', { resultIntegrity: 'VERIFIED' }));
    replies['/risk/demo'] = demo();
    const { f, text } = await render();
    expect(text()).toContain('98,507.15');
    expect(text()).toContain('Independently checked');
    replies['/eod/jobs'] = { status: 0, body: null };
    replies['/risk/demo'] = { status: 0, body: null };
    await f.componentInstance.loadJobs(); await f.componentInstance.loadDemo();
    expect(text()).not.toContain('98,507.15');
    expect(text()).not.toContain('job-SYNTHETIC_PRICING_VALIDATED');
    expect(text()).toContain('Calculation status unavailable');
    expect(text()).not.toContain('2026-09-16T18:00:00Z');
  });

  it('distinguishes queued, awaiting, failed, mock, W0 and synthetic pricing and never claims a live worker', async () => {
    replies['/eod/jobs'] = jobs(job('QUEUED'), job('RUNNING'), job('FAILED', { error: 'The worker attempt failed.' }), job('MOCK_COMPLETE', { resultIntegrity: 'VERIFIED' }),
      job('W0_VALIDATED', { resultIntegrity: 'VERIFIED' }), job('SYNTHETIC_PRICING_VALIDATED', { resultIntegrity: 'VERIFIED', selectionAmbiguous: true, currentCut: false }));
    replies['/risk/demo'] = demo();
    const { text, el } = await render();
    const labels = [...el.querySelectorAll('[data-status]')].map((e) => e.textContent!.trim());
    expect(labels).toEqual(['Queued', 'Awaiting result', 'Failed', 'Mock transport only', 'Outcomes checked — no pricing', 'Synthetic pricing validated']);
    expect(text()).toContain('Treasury bill');
    expect(text()).toContain('Live pricing connectivity has not been checked');
    expect(text()).not.toMatch(/\b(worker|Alex|engine)\s+(is\s+)?(connected|live|online)\b/i);
  });

  it('renders container custody prices and clears them for uncertainty or integrity failure', async () => {
    const containerPricing = { marketProvenance: 'assumed', assumedProfile: 'flat-3pct-v1',
      positions: [{ signedFaceUsd: '100000', npvUsd: '98507.14563826029' }],
      unsupported: ['rateSensitivity', 'rateGamma', 'theta'], portfolioRiskAvailable: false };
    replies['/eod/jobs'] = jobs(job('CONTAINER_PRICING_VALIDATED', { resultIntegrity: 'VERIFIED', containerPricing }));
    replies['/risk/demo'] = { status: 503, body: { code: 'NOT_CONFIGURED' } };
    const { f, text, el } = await render();
    expect(text()).toContain('Container pricing validated');
    expect(text()).toContain('98,507.15');
    expect(text()).toContain('Portfolio VaR/ES unavailable');
    expect(el.querySelector('[data-container-pricing]')).not.toBeNull();
    replies['/eod/jobs'] = jobs(job('CONTAINER_PRICING_VALIDATED', { resultIntegrity: 'INVALID', containerPricing }));
    await f.componentInstance.loadJobs();
    expect(text()).not.toContain('98,507.15');
    expect(el.querySelector('[data-container-pricing]')).toBeNull();
    replies['/eod/jobs'] = jobs(job('UNCERTAIN'));
    await f.componentInstance.loadJobs();
    expect(text()).toContain('Automatic retry disabled');
    expect(el.querySelector('[data-uncertain]')).not.toBeNull();
  });

  it('shows separate bill and note comparisons with the note reconciliation and signed +1bp change', async () => {
    replies['/eod/jobs'] = jobs(); replies['/risk/demo'] = demo();
    const { text, el } = await render();
    expect(el.querySelectorAll('article[data-instrument]').length).toBe(2);
    for (const label of ['Synthetic pricing demo', 'business date 2025-06-02', 'assumed flat 3% curve', 'not usable for production risk',
      'Calculated locally; results displayed here', 'Price change, +1bp parallel', 'Recomputed ICMA', 'within bound', 'not a per-unit derivative']) {
      expect(text()).toContain(label);
    }
    expect(text()).toContain('-15.28');
    expect(text()).not.toContain('fixture-bill-job');
    expect(text()).not.toContain('fixture-note-job');
    const results = [...el.querySelectorAll('article[data-instrument] td .pill')].map((e) => e.textContent!.trim());
    expect(results.length).toBe(2 + 8 + 2); // bill NPV x2; note NPV/clean/accrued/bump x2; reconciliation x2
    expect(results.every((r) => r === 'pass' || r === 'within bound')).toBeTrue();
  });

  it('derives coverage from the validated result: bill sensitivity unsupported, note bump only, VaR/ES unavailable', async () => {
    replies['/eod/jobs'] = jobs(); replies['/risk/demo'] = demo();
    const { el, text } = await render();
    const rows = [...el.querySelectorAll('[data-coverage] tbody tr')].map((r) => [...r.querySelectorAll('td')].map((c) => c.textContent!.trim()));
    expect(rows).toContain(['Rate sensitivity', 'unsupported', '+1bp parallel bump']);
    expect(rows).toContain(['Rate gamma', 'unsupported', 'unsupported']);
    expect(rows).toContain(['Theta', 'unsupported', 'unsupported']);
    expect(text()).toContain('Portfolio VaR / ES');
    expect(text()).not.toContain('bb9cf0e');
    expect(el.querySelectorAll('input, select, textarea, form').length).toBe(0);
    expect([...el.querySelectorAll('button')].map((b) => b.textContent!.trim())).toEqual(['Check status', 'Refresh', 'Refresh']);
  });

  it('keeps internal provenance and raw errors out of rendered content', async () => {
    replies['/eod/jobs'] = jobs(job('FAILED', { error: 'PRIVATE_ERROR_SENTINEL', integrityError: 'PRIVATE_INTEGRITY_SENTINEL', profile: { adapter: 'PRIVATE_ADAPTER_SENTINEL', engineCommit: 'PRIVATE_COMMIT_SENTINEL' } }));
    replies['/risk/demo'] = demo();
    const { text, el } = await render();
    for (const internal of ['PRIVATE_', 'job-FAILED', 'fixture-bill-job', 'fixture-note-job', 'c'.repeat(64), 'synthetic-shared-examples-v1', 'bb9cf0e', 'e7246e1', '2026-09-16T18:00:00Z', 'Alex', 'sha256', 'flat-3pct-v1', 'W1.6']) {
      expect(el.innerHTML).not.toContain(internal);
    }
    expect(text()).toContain('This result could not be verified');
    expect(text()).toContain('Pricing result');
    expect(text()).toContain('Independent check');
    expect(text()).toContain('98,507.15');
  });

  it('is routed at /risk', async () => {
    expect(routes.find((r) => r.path === 'risk')?.component).toBe(RiskPage);
    replies['/eod/jobs'] = jobs(); replies['/risk/demo'] = demo();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: Api, useValue: { load: (u: string) => Promise.resolve(replies[u]) } }] });
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/risk', RiskPage);
    expect(page).toBeInstanceOf(RiskPage);
  });
});
