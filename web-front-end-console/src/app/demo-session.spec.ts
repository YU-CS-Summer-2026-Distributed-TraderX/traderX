import { TestBed } from '@angular/core/testing';
import { Api } from './api';
import { SessionDriver } from './demo-session';

/**
 * The pause arithmetic, on a mocked clock.
 *
 * Worth a committed test because the failure mode is invisible until it matters: a session whose
 * budget is quietly consumed by a pause looks fine on screen and then stops early in front of an
 * audience. The live check that proved this on the rig is not repeatable; this is.
 *
 * Nothing here touches HTTP — prices() returns nothing, so every tick counts a skip instead of
 * sending an order. The subject is the clock, not the order path.
 */
describe('SessionDriver pause/resume', () => {
  let d: SessionDriver;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: Api, useValue: {
        watchPrices: () => {}, log: () => {}, prices: () => ({}),
        post: () => Promise.resolve({ status: 200, body: {} }),
      } }],
    });
    d = TestBed.inject(SessionDriver);
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(0));
    d.actors.set([{ accountId: 1, side: 'Buy', perMin: 60, quantity: 25, durationSec: 10,
      sent: 0, accepted: 0, rejected: 0, noPrice: 0, running: false, lastReason: '', remainingMs: 0 }]);
  });

  afterEach(() => { d.stop('test over'); jasmine.clock().uninstall(); });

  const rem = () => d.actors()[0].remainingMs;

  it('holds the clock and the budget across a pause, then finishes on trading time', () => {
    d.start();
    expect(rem()).toBe(10_000);

    jasmine.clock().tick(4_000);
    expect(d.elapsed()).toBe(4);

    d.pause();
    expect(d.paused()).toBeTrue();
    expect(d.running()).withContext('paused is a state OF running, not a stop').toBeTrue();
    expect(rem()).toBe(6_000);

    // The whole point: wall time passes, trading time does not.
    jasmine.clock().tick(30_000);
    expect(d.elapsed()).withContext('clock stopped').toBe(4);
    expect(rem()).withContext('a pause must not spend the budget').toBe(6_000);

    d.resume();
    expect(d.paused()).toBeFalse();

    // 6s of budget left, so it retires then — 40s after start in wall time.
    jasmine.clock().tick(5_000);
    expect(d.running()).withContext('still owed 1s').toBeTrue();
    jasmine.clock().tick(1_500);
    expect(d.running()).withContext('budget spent').toBeFalse();
    expect(d.elapsed()).withContext('elapsed is trading time, not wall time').toBe(10);
  });

  it('reports the full duration even when the clock tick never fires', () => {
    // An unfocused tab throttles setInterval, so the last sample can be badly stale. stop() must
    // re-derive elapsed from the wall clock rather than freeze whatever the tick last wrote —
    // this reported 19s for a 25s session before it did.
    d.start();
    clearInterval((d as unknown as { clock: ReturnType<typeof setInterval> }).clock);
    jasmine.clock().tick(7_000);
    expect(d.elapsed()).withContext('no tick fired, so nothing updated it').toBe(0);
    d.stop('operator');
    expect(d.elapsed()).toBe(7);
  });

  it('does not resurrect a session whose budget ran out while paused', () => {
    d.start();
    jasmine.clock().tick(9_500);
    d.pause();
    expect(rem()).toBe(500);
    d.resume();
    jasmine.clock().tick(600);
    expect(d.running()).toBeFalse();
  });
});

/**
 * The tally is the only place a reader can check the session's arithmetic, so it has to be right
 * about two things that are easy to get wrong — and were, in its first version: batch mode counts
 * batches on one side and orders on the other, and an in-flight order is a legitimate shortfall
 * rather than a loss.
 */
describe('SessionDriver tally', () => {
  let d: SessionDriver;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: Api, useValue: {
        watchPrices: () => {}, log: () => {}, prices: () => ({}),
        post: () => Promise.resolve({ status: 200, body: {} }),
      } }],
    });
    d = TestBed.inject(SessionDriver);
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(0));
  });
  afterEach(() => { d.stop('test over'); jasmine.clock().uninstall(); });

  /** start() zeroes the counters, so the numbers under test are set after it. */
  const withCounts = (c: { sent: number; accepted: number; rejected: number; noPrice: number }) => {
    d.actors.set([{ accountId: 1, side: 'Buy', perMin: 1, quantity: 25, durationSec: 600,
      sent: 0, accepted: 0, rejected: 0, noPrice: 0, running: false, lastReason: '', remainingMs: 0 }]);
    d.start();
    d.actors.update(l => l.map(a => ({ ...a, ...c })));
  };

  it('balances when every order has been answered', () => {
    withCounts({ sent: 20, accepted: 9, rejected: 0, noPrice: 11 });
    d.stop('done');
    expect(d.tally().bad).toBeFalse();
    expect(d.tally().text).toBe('20 orders sent = 9 accepted + 0 rejected + 11 skipped (no price yet)');
  });

  it('calls a shortfall "in flight" while running, and does not flag it', () => {
    withCounts({ sent: 20, accepted: 15, rejected: 0, noPrice: 2 });
    expect(d.running()).toBeTrue();
    expect(d.tally().bad).withContext('in flight is not loss').toBeFalse();
    expect(d.tally().text).toContain('3 in flight');
  });

  it('calls the same shortfall UNACCOUNTED once stopped, and flags it', () => {
    withCounts({ sent: 20, accepted: 15, rejected: 0, noPrice: 2 });
    d.stop('operator');
    expect(d.tally().bad).withContext('at rest nothing more can arrive').toBeTrue();
    expect(d.tally().text).toContain('3 UNACCOUNTED');
  });

  it('counts ORDERS on both sides in batch mode, not batches against orders', () => {
    d.batch.set(true);
    d.batchSize.set(10);
    withCounts({ sent: 4, accepted: 30, rejected: 4, noPrice: 6 });
    d.stop('done');
    // 4 batches x 10 = 40 orders offered, and 30 + 4 + 6 = 40. The naive version compared 4 to 40.
    expect(d.tally().bad).toBeFalse();
    expect(d.tally().text).toBe('40 orders in 4 batches sent = 30 accepted + 4 rejected + 6 skipped (no price yet)');
  });

  it('refuses to print a balanced-looking line when more was answered than offered', () => {
    withCounts({ sent: 5, accepted: 99, rejected: 0, noPrice: 0 });
    expect(d.tally().bad).toBeTrue();
    expect(d.tally().text).toContain('do not add up');
  });
});
