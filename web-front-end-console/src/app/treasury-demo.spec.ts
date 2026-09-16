import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Api } from './api';
import { KEY_STORAGE, POLL, TreasuryDemo } from './treasury-demo';

type Reply = { status: number; body: any };
const scenario = { instrument: 'UST-BILL-20261112', label: 'Treasury bill', quantity: '1000', quantityUnit: 'USD face', currency: 'USD',
  valuationDate: '2026-09-16', assumedCurve: 'Flat 3%', executionLocation: 'local', singleRun: true };
const result = { instrument: 'UST-BILL-20261112', currency: 'USD', quantity: '1000', signedFaceUsd: '1000', bookedPrice: '0.99125',
  bookedPriceUnit: 'fraction of par', valuationDate: '2026-09-16', pricingUsd: '995.9876543', referenceUsd: '995.98765430001',
  differenceUsd: '-0.00000000001', toleranceUsd: '0.00000001', withinTolerance: true, rateSensitivity: 'unsupported',
  executionLocation: 'local', assumedCurve: 'Flat 3%', usableForRisk: false };
const view = (run: any = null, extra: object = {}): Reply => ({ status: 200, body: { schema: 'traderx.treasury-trade-demo.v1',
  workerAvailable: true, scenario, run, ...extra } });
const run = (status: string, stage: string, extra: object = {}) => ({ id: 'run-internal-7f3a', status, stage, message: '',
  result: status === 'SUCCEEDED' ? result : null, ...extra });
const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

describe('Treasury demo run', () => {
  let gets: Reply[];
  let posts: Reply[];
  let calls: { url: string; init?: RequestInit }[];
  const authUser = signal<string | null>('operator');
  const authPrompt = signal(false);

  beforeEach(() => {
    localStorage.removeItem(KEY_STORAGE);
    Object.assign(POLL, { firstMs: 60_000, maxMs: 60_000, factor: 1.5, giveUpMs: 10 * 60 * 1000 }); // polling off unless a test enables it
    gets = []; posts = []; calls = [];
    authUser.set('operator');
    const load = async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      const queue = init?.method === 'POST' ? posts : gets;
      await tick();
      return structuredClone(queue.length > 1 ? queue.shift()! : queue[0] ?? { status: 0, body: null });
    };
    TestBed.configureTestingModule({ imports: [TreasuryDemo], providers: [{ provide: Api, useValue: { load, authUser, authPrompt } }] });
  });

  async function render() {
    const f = TestBed.createComponent(TreasuryDemo); f.detectChanges(); await tick(5); f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    return { f, el, text: () => (f.detectChanges(), el.textContent as string), q: (s: string) => (f.detectChanges(), el.querySelector(s)) };
  }
  const postCalls = () => calls.filter((c) => c.init?.method === 'POST');
  const steps = (el: HTMLElement) => [...el.querySelectorAll('[data-step]')].map((e) => e.getAttribute('data-step'));

  it('never starts on load and needs sign-in before offering the run', async () => {
    authUser.set(null); gets = [view()];
    const { text, q } = await render();
    expect(q('[data-run]')).toBeNull();
    expect(text()).toContain('Sign in as an operator');
    expect(text()).toContain('Flat 3%');
    expect(postCalls().length).toBe(0);
  });

  it('starts once on double click, stores the key first and sends only the key', async () => {
    gets = [view(), view(run('RUNNING', 'TRADE_SUBMITTED'))];
    posts = [view(run('QUEUED', 'QUEUED'))];
    const { f, q } = await render();
    const button = q('[data-run]') as HTMLButtonElement;
    button.click(); button.click(); f.componentInstance.start();
    expect(localStorage.getItem(KEY_STORAGE)).toMatch(/^[0-9a-f-]{36}$/);
    await tick(20); f.detectChanges();
    expect(postCalls().length).toBe(1);
    expect(JSON.parse(postCalls()[0].init!.body as string)).toEqual({ idempotencyKey: localStorage.getItem(KEY_STORAGE)! });
    expect(q('[data-run]')).toBeNull();
    f.destroy();
  });

  it('retries a lost start reply with the same key and never offers a second run', async () => {
    gets = [view()];
    posts = [{ status: 0, body: null }, view(run('RUNNING', 'TRADE_SUBMITTED'))];
    const { f, q, text } = await render();
    (q('[data-run]') as HTMLButtonElement).click();
    await tick(20);
    const key = localStorage.getItem(KEY_STORAGE);
    expect(text()).toContain('cannot place a second trade');
    gets = [view(run('RUNNING', 'TRADE_SUBMITTED'))];
    await f.componentInstance.start();
    expect(postCalls().length).toBe(2);
    expect(JSON.parse(postCalls()[1].init!.body as string).idempotencyKey).toBe(key!);
    expect(q('[data-run-status=RUNNING]')).not.toBeNull();
    f.destroy();
  });

  it('resumes an active run after reload by reading only, then clears the key when checked', async () => {
    localStorage.setItem(KEY_STORAGE, 'kept-key');
    gets = [view(run('RUNNING', 'TRADE_BOOKED'))];
    const { el, f } = await render();
    expect(steps(el)).toEqual(['done', 'done', 'waiting', 'not started', 'not started']);
    expect(localStorage.getItem(KEY_STORAGE)).toBe('kept-key');
    gets = [view(run('RUNNING', 'PRICING'))];
    await f.componentInstance.check(); f.detectChanges();
    expect(steps(el)).toEqual(['done', 'done', 'done', 'in progress', 'waiting']);
    gets = [view(run('SUCCEEDED', 'INDEPENDENTLY_CHECKED'))];
    await f.componentInstance.check(); f.detectChanges();
    expect(steps(el)).toEqual(['done', 'done', 'done', 'done', 'done']);
    expect(postCalls().length).toBe(0);
    expect(localStorage.getItem(KEY_STORAGE)).toBeNull();
    f.destroy();
  });

  it('polls an active run with bounded backoff and stops at a terminal state', async () => {
    Object.assign(POLL, { firstMs: 5, maxMs: 10 });
    gets = [view(run('RUNNING', 'TRADE_BOOKED')), view(run('RUNNING', 'POSITION_EXPORTED')), view(run('SUCCEEDED', 'INDEPENDENTLY_CHECKED'))];
    const { f, q } = await render();
    for (let i = 0; i < 20 && !q('[data-run-status=SUCCEEDED]'); i++) await tick(10);
    expect(q('[data-run-status=SUCCEEDED]')).not.toBeNull();
    const reads = calls.length; await tick(60);
    expect(calls.length).toBe(reads);
    f.destroy();
  });

  it('shows an accepted-but-unbooked trade as submitted only, and a failure without any result', async () => {
    gets = [view(run('RUNNING', 'TRADE_SUBMITTED'))];
    const a = await render();
    expect(steps(a.el).slice(0, 2)).toEqual(['done', 'waiting']);
    a.f.destroy();
    gets = [view(run('FAILED', 'TRADE_SUBMITTED', { message: 'The trade was not filled.' }))];
    const { el, text, q } = await render();
    expect(steps(el)).toEqual(['done', 'failed', 'not reached', 'not reached', 'not reached']);
    expect(text()).toContain('The trade was not filled.');
    expect(q('[data-valuation]')).toBeNull();
    expect(text()).not.toContain('pass');
  });

  it('marks failed pricing as the failed step, not as done', async () => {
    gets = [view(run('FAILED', 'PRICING'))];
    const { el } = await render();
    expect(steps(el)).toEqual(['done', 'done', 'done', 'failed', 'not reached']);
  });

  it('renders the checked result with separate booked price and valuation, units and no internal ids', async () => {
    gets = [view(run('SUCCEEDED', 'INDEPENDENTLY_CHECKED'))];
    const { text, q } = await render();
    const t = text();
    for (const s of ['Treasury bill · UST-BILL-20261112', 'USD 1,000.00', '99.125% of par', 'USD 995.99', '2026-09-16', 'pass',
      'assumed Flat 3% curve', 'calculated local', 'not production risk', 'not calculated for a bill (unsupported) — this is not zero']) {
      expect(t).toContain(s);
    }
    expect(q('[data-run]')).toBeNull();
    expect(t).not.toContain('run-internal-7f3a');
    expect(t).not.toMatch(/[0-9a-f]{32}|Alex|commit/i);
  });

  it('clears a previous green result when the next read fails or is invalid', async () => {
    gets = [view(run('SUCCEEDED', 'INDEPENDENTLY_CHECKED'))];
    const { f, text, q } = await render();
    expect(text()).toContain('995.99');
    gets = [{ status: 503, body: { code: 'NOT_CONFIGURED' } }];
    await f.componentInstance.check();
    expect(text()).not.toContain('995.99');
    expect(q('[data-valuation]')).toBeNull();
    expect(text()).toContain('not available on this deployment');
    const bad = view(run('SUCCEEDED', 'INDEPENDENTLY_CHECKED')); bad.body.run.result = { ...result, usableForRisk: true };
    gets = [bad];
    await f.componentInstance.check();
    expect(text()).not.toContain('995.99');
    expect(text()).toContain('unavailable right now');
  });

  it('treats the unconfigured shape (200, no scenario, no worker) as unavailable with no run control', async () => {
    gets = [view(null, { scenario: null, workerAvailable: false })];
    const { text, q } = await render();
    expect(q('[data-state=not-configured]')).not.toBeNull();
    expect(q('[data-run]')).toBeNull();
    expect(q('[data-scenario]')).toBeNull();
    expect(text()).not.toContain('Sign in as an operator');
  });

  it('is honest about an unavailable worker before and during a run', async () => {
    gets = [view(null, { workerAvailable: false })];
    const a = await render();
    expect(a.q('[data-run]')).toBeNull();
    expect(a.text()).toContain('pricing worker is not available');
    a.f.destroy();
    gets = [view(run('RUNNING', 'POSITION_EXPORTED'), { workerAvailable: false })];
    const b = await render();
    expect(b.q('[data-state=worker-lost]')).not.toBeNull();
    expect(b.text()).not.toContain('Completed');
    b.f.destroy();
  });

  it('maps refused starts to fixed text and forgets the key', async () => {
    gets = [view()];
    for (const [reply, expected] of [[{ status: 503, body: { code: 'WORKER_UNAVAILABLE', message: '/private/secret stack' } }, 'No trade was placed'],
      [{ status: 401, body: { code: 'admin_auth_required' } }, 'Sign in as an operator'],
      [{ status: 403, body: { code: 'ORIGIN_REJECTED' } }, 'refused this request']] as [Reply, string][]) {
      posts = [reply];
      const { f, text } = await render();
      await f.componentInstance.start();
      expect(text()).toContain(expected);
      expect(text()).not.toContain('/private/secret');
      expect(localStorage.getItem(KEY_STORAGE)).toBeNull();
      f.destroy();
    }
  });

  it('stops polling when the page is left and gives up after the bound', async () => {
    Object.assign(POLL, { firstMs: 5, maxMs: 5 });
    gets = [view(run('RUNNING', 'TRADE_BOOKED'))];
    const a = await render();
    await tick(30);
    expect(calls.length).toBeGreaterThan(1); // it was polling
    a.f.destroy();
    const reads = calls.length; await tick(40);
    expect(calls.length).toBe(reads);
    POLL.giveUpMs = 0;
    const b = await render();
    await tick(20);
    expect(b.q('[data-state=stalled]')).not.toBeNull();
    const stalledReads = calls.length; await tick(30);
    expect(calls.length).toBe(stalledReads);
    b.f.destroy();
  });
});
