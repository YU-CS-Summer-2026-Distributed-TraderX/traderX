// Retry credential acquisition, never the caller's mutation. One request and one timer
// per process prevent a failed dependency from creating a retry storm.
export function createInternalToken({mint, ttlMs = 3600000, retryMs = 1000,
  maxRetryMs = 30000, now = Date.now, log = console.log}) {
  let token = '', expires = 0, timer, stopped = false, failures = 0;
  const schedule = ms => { if (!stopped) { timer = setTimeout(refresh, ms); timer.unref?.(); } };
  async function refresh() {
    try {
      const value = await mint();
      if (typeof value !== 'string' || value.split('.').length !== 3) throw Error('Invalid token response');
      token = value; expires = now() + ttlMs; failures = 0;
      schedule(ttlMs / 2);
    } catch (e) {
      log(`[console] internal token mint failed: ${e.message}`);
      schedule(Math.min(maxRetryMs, retryMs * 2 ** Math.min(failures++, 10)));
    }
  }
  void refresh();
  return { get: () => now() < expires ? token : '', stop: () => { stopped = true; clearTimeout(timer); } };
}
