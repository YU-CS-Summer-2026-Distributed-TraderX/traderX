// FR-DA09: one `kubectl get pods` line per gateway (name|phase|deletionTimestamp|Ready) -> the System tab's counts.
export function parseGatewayCount(out) {
  const pods = out.split(';').map(l => l.trim()).filter(Boolean).map(l => {
    const [name, phase, deleting, ready] = l.split('|');
    return { name, phase, state: deleting ? 'draining' : ready === 'True' ? 'ready' : 'starting' };
  });
  const n = st => pods.filter(p => p.state === st).length;
  return { ready: n('ready'), starting: n('starting'), draining: n('draining'), pods };
}
