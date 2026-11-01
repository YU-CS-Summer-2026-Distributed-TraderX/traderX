import test from 'node:test';
import assert from 'node:assert/strict';
import { KDB_PRESETS, kdbQueryText } from './kdb-query.mjs';

test('FR-DA10: a preset runs its own q for anyone; an unknown preset is 404', () => {
  assert.equal(kdbQueryText({ preset: 'count' }, false).q, KDB_PRESETS[0].q);
  assert.equal(kdbQueryText({ preset: 'drop-everything' }, true).status, 404);
});

test('FR-DA10: free-form q needs an administrator, and is bounded', () => {
  assert.equal(kdbQueryText({ q: 'count trades' }, false).code, 'admin_auth_required');
  assert.equal(kdbQueryText({ q: 'count trades' }, true).q, 'count trades');
  assert.equal(kdbQueryText({ q: 'x'.repeat(2001) }, true).status, 400);
  assert.equal(kdbQueryText({}, true).status, 400);
});
