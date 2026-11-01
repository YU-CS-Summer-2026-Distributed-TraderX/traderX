import test from 'node:test';
import assert from 'node:assert/strict';
import { parseGatewayCount } from './gateway-count.mjs';

test('FR-DA09: ready, starting and draining gateways are counted apart', () => {
  const out = 'gw-a|Running||True;gw-b|Running||True;gw-c|Pending||;gw-d|Running||False;gw-e|Running|2026-11-05T19:00:00Z|True;';
  const c = parseGatewayCount(out);
  assert.deepEqual([c.ready, c.starting, c.draining], [2, 2, 1]);
  assert.equal(c.pods.find(p => p.name === 'gw-e').state, 'draining');
});

test('FR-DA09: no gateway pods is zero of each, not an error', () => {
  assert.deepEqual(parseGatewayCount(''), { ready: 0, starting: 0, draining: 0, pods: [] });
});
