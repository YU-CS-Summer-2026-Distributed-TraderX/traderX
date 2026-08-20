import { bridgeError } from './api';

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
