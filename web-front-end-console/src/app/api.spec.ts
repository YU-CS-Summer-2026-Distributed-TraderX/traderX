import { bridgeError, riskControlError } from './api';

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
