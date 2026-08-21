import { parseSpans } from './trace-view';

/**
 * A trace id is a pure function of the CLIENT order id, so two orders that reuse one land in the
 * SAME trace. Measured on the cloud rig 2026-08-21: two orders posted with an identical ClOrdID
 * were refused (PRICE_COLLAR, refs 72 and 73) and Tempo returned ONE id holding 10 spans, 5 per
 * order — the payload below is that shape, trimmed.
 *
 * Worth a committed test because of how it fails: a panel headed "this order's trace" that lists a
 * second order's spans without saying so is wrong the way a bad id is wrong. Every span is real,
 * every number is right, and the whole is misleading. The only thing separating the two orders is
 * `traderx.order_ref` on each span, so if that attribute ever stops arriving the panel silently
 * loses its ability to tell — and this is what notices.
 */
describe('parseSpans order refs', () => {
  const span = (name: string, ref: string, start: string) => ({
    name, startTimeUnixNano: start, endTimeUnixNano: String(Number(start) + 1000),
    attributes: [{ key: 'traderx.order_ref', value: { stringValue: ref } }],
  });
  const batch = (svc: string, spans: ReturnType<typeof span>[]) => ({
    resource: { attributes: [{ key: 'service.name', value: { stringValue: svc } }] },
    scopeSpans: [{ spans }],
  });

  it('carries the order ref through, so a shared trace is detectable', () => {
    const rows = parseSpans({ batches: [
      batch('traderx-cluster-gateway', [span('order', '72', '1000'), span('order', '73', '2000')]),
      batch('traderx-cluster-member', [span('cluster.apply', '72', '1500')]),
    ] });
    expect(rows.map(r => r.ref)).toEqual(['72', '72', '73']);
    expect([...new Set(rows.map(r => r.ref))].length).toBe(2);
  });

  it('sorts by start time across services, not by batch', () => {
    const rows = parseSpans({ batches: [
      batch('gw', [span('a', '1', '3000')]),
      batch('mem', [span('b', '1', '1000')]),
    ] });
    expect(rows.map(r => r.name)).toEqual(['b', 'a']);
  });

  it('leaves ref undefined when the attribute is absent, rather than inventing one', () => {
    const rows = parseSpans({ batches: [{
      resource: { attributes: [{ key: 'service.name', value: { stringValue: 'gw' } }] },
      scopeSpans: [{ spans: [{ name: 'order', startTimeUnixNano: '1', endTimeUnixNano: '2' }] }],
    }] });
    expect(rows[0].ref).toBeUndefined();
  });
});
