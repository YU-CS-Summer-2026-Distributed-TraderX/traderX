/** Additive producer status contract. Optional codes support older publishers. */
export interface TaqReplay {
  state?: string; reason?: string;
  source?: string; symbols?: number; days?: number;
  windowSeconds?: number; compression?: number; error?: string | null;
  position?: { tapeDate?: string; dayIndex?: number; windowIndex?: number; asOf?: string; held?: boolean } | null;
}

/** Classify availability, never financial validity. Unknown structured codes alarm.
 * Older producers distinguish absence only in prose; retain that narrow fallback. */
export function classifyTapeStatus(t: TaqReplay | undefined): 'tape' | 'synthetic' | 'error' {
  if (!t) { return 'synthetic'; }
  if (t.state !== undefined || t.reason !== undefined) {
    if ((t.state === 'not_attempted' && t.reason === 'NOT_ATTEMPTED')
        || (t.state === 'unavailable' && t.reason === 'EXTRACT_MISSING')) {
      return t.source || t.days || t.position ? 'error' : 'synthetic';
    }
    const loaded = (t.state === 'replaying' && t.reason === 'REPLAY_ACTIVE')
      || (t.state === 'paused' && t.reason === 'REPLAY_PAUSED')
      || (t.state === 'finished' && t.reason === 'REPLAY_FINISHED');
    return loaded && t.position && !t.error ? 'tape' : 'error';
  }
  if (t.source || t.days || t.position) { return t.error ? 'error' : 'tape'; }
  if (!t.error) { return 'synthetic'; }
  return /no extract at/i.test(t.error) ? 'synthetic' : 'error';
}
