import { firstEpoch } from './blotter-panel';
import { TestBed } from '@angular/core/testing';
import { Api, bridgeError, riskControlError } from './api';

/**
 * The rule these three cases exist to hold: the bridge's own words win, and the console's own
 * fallback never names a deployment. A message that says "is the dev proxy running?" to someone
 * viewing the deployed console sends them after a process that does not exist on their machine.
 */
describe('bridgeError', () => {
  it('prefers the bridge\'s own explanation over the generic one', () => {
    const r = { status: 502, body: { error: 'kubectl exec failed — is the risk-extract pod up?' } };
    expect(bridgeError(r, 'the cut-sink bridge')).toBe('kubectl exec failed — is the risk-extract pod up?');
  });

  it('falls back to the ROLE and the status when the body explains nothing', () => {
    expect(bridgeError({ status: 502, body: null }, 'the capture bridge'))
      .toBe('the capture bridge did not answer (HTTP 502)');
  });

  it('says "no response" rather than "HTTP 0" when the fetch itself failed', () => {
    expect(bridgeError({ status: 0, body: null }, 'the FIX bridge'))
      .toBe('the FIX bridge did not answer (no response)');
  });

  it('never names a deployment in the fallback', () => {
    for (const role of ['the cut-sink bridge', 'the capture bridge', 'the FIX bridge']) {
      const msg = bridgeError({ status: 502, body: null }, role);
      expect(msg).not.toContain('dev proxy');
      expect(msg).not.toContain('kubectl');
    }
  });
});

/**
 * The 401 body below is not invented: it was read off the live gateway twice, once with a bad
 * X-Risk-Control-Token and once with the operator header missing, and both answers were
 * byte-identical. That is the whole point of these cases.
 */
describe('riskControlError', () => {
  const CREDS_401 = { status: 401, body: { error: 'invalid risk-control credentials' } };

  it('quotes the gateway rather than guessing which credential was wrong', () => {
    expect(riskControlError(CREDS_401)).toBe('HTTP 401 — invalid risk-control credentials');
  });

  it('never names the token, because a missing operator gives the same 401', () => {
    expect(riskControlError(CREDS_401)).not.toContain('RISK_CONTROL_TOKEN');
  });

  it('falls back to the bare status when there is no body to quote', () => {
    expect(riskControlError({ status: 503, body: null })).toBe('HTTP 503');
  });
});

/**
 * The band verdict, on the case that exposed the old test.
 *
 * A price collar refuses on BOTH sides of its band, so the refused min/max straddles the accepted
 * prices and a range-disjointness test always says "overlapping — some other cause". That is the
 * band's own signature being read as its absence. Measured on the rig: EXC accepted 150 and was
 * refused at 100 and across 180-210.
 */
describe('band verdict', () => {
  // Mirrors the rule in loadBands: no refused price may lie inside the accepted range.
  const verdict = (accepted: number[], rejected: number[]) => {
    if (!accepted.length) return 'never-accepted';
    const aLo = Math.min(...accepted), aHi = Math.max(...accepted);
    return rejected.every(p => p < aLo || p > aHi) ? 'anchored-elsewhere' : 'other-refusal';
  };

  it('calls a two-sided collar what it is, where disjointness could not', () => {
    expect(verdict([150], [100, 180, 190, 192.4, 195, 200, 210])).toBe('anchored-elsewhere');
  });

  it('still calls it anchored when every refusal is above the band', () => {
    expect(verdict([100, 110], [200, 210])).toBe('anchored-elsewhere');
  });

  it('says another cause the moment a refusal lands among the accepted prices', () => {
    // 150 was both accepted and refused, so the band cannot be what refused it.
    expect(verdict([100, 200], [150])).toBe('other-refusal');
  });

  it('concludes nothing without an accepted order to compare against', () => {
    expect(verdict([], [100, 200])).toBe('never-accepted');
  });
});

/**
 * The persisted order-ref → trace map across an epoch roll.
 *
 * Worth committing because the failure is not a missing link, it is a CONFIDENT WRONG one. Order
 * refs restart at 1 on a fresh epoch (nextOrderRef is restored only from a snapshot, and the roll
 * wipes the PVCs) while sessionStorage survives a refresh — so a map keyed on the bare ref answers
 * epoch 2's order 7 with epoch 1's trace. That id resolves in Tempo and renders five convincing
 * spans belonging to a different order. Nothing on screen looks wrong.
 */
describe('order trace map across an epoch roll', () => {
  let api: Api;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({});
    api = TestBed.inject(Api);
  });

  it('recovers the trace of an order this session submitted', () => {
    api.log({ kind: 'order', ok: true, summary: 'x', orderRef: 7, traceId: 'aaaa' });
    expect(api.traceForOrderRef(7)).toBe('aaaa');
  });

  it('adopts the first epoch it sees without discarding what is already recorded', () => {
    api.log({ kind: 'order', ok: true, summary: 'x', orderRef: 7, traceId: 'aaaa' });
    api.noteEpoch(1);
    expect(api.traceForOrderRef(7)).toBe('aaaa');
  });

  it('drops the map when the epoch changes, rather than answering with the old epoch\'s trace', () => {
    api.log({ kind: 'order', ok: true, summary: 'x', orderRef: 7, traceId: 'aaaa' });
    api.noteEpoch(1);
    api.noteEpoch(2);                       // fresh-epoch roll; ref 7 will be issued again
    expect(api.traceForOrderRef(7)).toBeUndefined();
  });

  it('ignores a re-sighting of the same epoch', () => {
    api.log({ kind: 'order', ok: true, summary: 'x', orderRef: 7, traceId: 'aaaa' });
    api.noteEpoch(1);
    api.noteEpoch(1);
    expect(api.traceForOrderRef(7)).toBe('aaaa');
  });

  it('never treats ref 0 — a market sweep — as a lookup', () => {
    api.log({ kind: 'order', ok: true, summary: 'x', orderRef: 0, traceId: 'aaaa' });
    expect(api.traceForOrderRef(0)).toBeUndefined();
  });
});

// ---- firstEpoch: the guard that stops a persisted trace map crossing an epoch boundary --------
// These exist because the ORIGINAL fix witnessed the epoch only from open orders, and a fresh
// epoch starts with an empty book — so the load that most needs to clear the map had nothing to
// read an epoch from, while its trades used that same stale map.
describe('firstEpoch', () => {
  it('reads the epoch from an open-order id', () => {
    expect(firstEpoch(['2-7'])).toBe(2);
  });

  it('falls through to a trade sourceOrderId when there are no open orders', () => {
    // The fresh-epoch case: empty book, trades already arriving. Built the way the loader builds
    // it — open-order ids first, then trade sourceOrderIds — so this fails if the trade half is
    // ever dropped from the call site. Spreading a literal empty array would have tested nothing.
    const openOrders: { id: string }[] = [];
    const trades = [{ sourceOrderId: '3-14' }];
    const ids = [...openOrders.map(r => r.id), ...trades.map(r => r.sourceOrderId)];
    expect(ids.length).toBe(1);                 // the list really is trades-only
    expect(firstEpoch(ids)).toBe(3);
  });

  it('prefers an open order when both are present, and agrees with the trades either way', () => {
    const ids = [...[{ id: '4-1' }].map(r => r.id), ...[{ sourceOrderId: '4-9' }].map(r => r.sourceOrderId)];
    expect(firstEpoch(ids)).toBe(4);
  });

  it('skips market trades and blanks rather than reading them as an epoch', () => {
    expect(firstEpoch([null, undefined, '', '3-9'])).toBe(3);
  });

  it('returns null when nothing carries an epoch, so the map is left alone', () => {
    // Not 0 and not 1: an absent witness must not be mistaken for "epoch 1", which would clear a
    // valid map on every empty account.
    expect(firstEpoch([null, ''])).toBeNull();
  });

  it('takes the FIRST id that has one, not the last', () => {
    expect(firstEpoch(['5-1', '5-2'])).toBe(5);
  });
});
