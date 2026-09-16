import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { readRiskDemo } from './risk-demo.mjs';
// Built from the committed real e7246e1 producer outputs and the independent Decimal reference.
const FIXTURE = new URL('./test-fixtures/risk-demo-synthetic.json', import.meta.url);
const good = () => JSON.parse(readFileSync(FIXTURE, 'utf8'));
const serve = async (doc) => {
  const file = path.join(mkdtempSync(path.join(tmpdir(), 'risk-demo-')), 'a.json');
  writeFileSync(file, typeof doc === 'string' ? doc : JSON.stringify(doc));
  return { file, result: await readRiskDemo({ RISK_DEMO_ARTIFACT: file }) };
};

test('unconfigured and relative configuration read nothing', async () => {
  const never = () => { throw new Error('must not read'); };
  assert.equal((await readRiskDemo({}, never)).body.code, 'NOT_CONFIGURED');
  const rel = await readRiskDemo({ RISK_DEMO_ARTIFACT: 'demo.json' }, never);
  assert.equal(rel.status, 503); assert.equal(rel.body.code, 'INVALID_CONFIGURATION');
  assert.equal(rel.body.artifact, undefined);
});

test('the validated synthetic artifact is served verbatim with explicit scope', async () => {
  const { result } = await serve(good());
  assert.equal(result.status, 200);
  assert.equal(result.body.availability, 'AVAILABLE');
  assert.deepEqual(result.body.artifact, good());
  assert.equal(result.body.artifact.usableForRisk, false);
});

test('missing, oversized and non-JSON artifacts are unavailable without detail', async () => {
  const missing = await readRiskDemo({ RISK_DEMO_ARTIFACT: '/nonexistent/secret/path.json' });
  assert.equal(missing.status, 503); assert.equal(missing.body.code, 'ARTIFACT_INVALID');
  assert.ok(!JSON.stringify(missing.body).includes('secret'));
  for (const bad of ['', '{', '<html>', 'null', '[]', ' '.repeat(1024 * 1024 + 1)]) {
    const { result } = await serve(bad);
    assert.equal(result.status, 503); assert.equal(result.body.code, 'ARTIFACT_INVALID');
  }
});

test('a corrupted artifact replaces the previous good read instead of falling back to it', async () => {
  const { file, result } = await serve(good());
  assert.equal(result.status, 200);
  writeFileSync(file, '{"schema":"traderx.risk-demo.v1"');
  const after = await readRiskDemo({ RISK_DEMO_ARTIFACT: file });
  assert.equal(after.status, 503); assert.equal(after.body.artifact, undefined);
});

test('every semantic corruption is rejected', async () => {
  const note = (a) => a.jobs[1].positions[0].noteDetail;
  const mutations = {
    'extra top field': (a) => { a.accounts = ['22214']; },
    'missing field': (a) => { delete a.generatedAt; },
    'usable for risk': (a) => { a.usableForRisk = true; },
    'connectivity claimed': (a) => { a.workerConnectivity = 'CONNECTED'; },
    'other date': (a) => { a.businessDate = '2025-06-03'; },
    'other profile': (a) => { a.assumedMarketProfile = 'observed'; },
    're-pinned engine': (a) => { a.compatibilityProfile.engineCommit = 'bb9cf0ee08214a58abcecca58bdae540c631b5e6'; },
    'merged jobs': (a) => { a.jobs = [a.jobs[0]]; },
    'swapped instruments': (a) => { a.jobs.reverse(); },
    'same job id': (a) => { a.jobs[1].jobId = a.jobs[0].jobId; },
    'unknown bundle': (a) => { a.jobs[0].bundleId = 'f'.repeat(64); },
    'W0 status': (a) => { a.jobs[1].status = 'W0_VALIDATED'; },
    'mock status': (a) => { a.jobs[1].status = 'MOCK_COMPLETE'; },
    'invalid integrity': (a) => { a.jobs[0].resultIntegrity = 'INVALID'; },
    'zero attempts': (a) => { a.jobs[0].attemptCount = 0; },
    'float not string': (a) => { a.jobs[0].positions[0].npv.alexUsd = 98507.14563826029; },
    'exponent notation': (a) => { a.jobs[0].positions[0].npv.differenceUsd = '1e-13'; },
    'empty value': (a) => { a.jobs[0].positions[0].npv.referenceUsd = ''; },
    'difference inconsistent': (a) => { a.jobs[0].positions[0].npv.alexUsd = '98507.15'; },
    'out of tolerance': (a) => { const c = a.jobs[0].positions[0].npv; c.alexUsd = '98507.14563828029'; c.differenceUsd = '0.00000002'; },
    'tolerance widened': (a) => { a.jobs[0].positions[0].npv.toleranceUsd = '1'; },
    'fail flag': (a) => { a.jobs[0].positions[0].npv.withinTolerance = false; },
    'truthy string flag': (a) => { a.jobs[0].positions[0].npv.withinTolerance = 'true'; },
    'sides swapped': (a) => { a.jobs[0].positions.reverse(); },
    'face sign': (a) => { a.jobs[0].positions[1].signedFaceUsd = '100000'; },
    'bill with note detail': (a) => { a.jobs[0].positions[0].noteDetail = note(a); },
    'note without detail': (a) => { a.jobs[1].positions[0].noteDetail = null; },
    'bump missing': (a) => { delete note(a).parallelBump1bpUsd; },
    'bump wrong sign': (a) => { const c = note(a).parallelBump1bpUsd; c.alexUsd = c.alexUsd.slice(1); },
    'dirty != clean+accrued': (a) => { note(a).dirtyMinusCleanMinusAccruedUsd = '0.01'; },
    'accrual beyond bound': (a) => { note(a).accrualReconciliation.differenceUsd = '0.07'; },
    'accrual export changed': (a) => { note(a).accrualReconciliation.exportedFraction = '0.018572'; },
    'bill sensitivity as zero': (a) => { const r = a.jobs[0].coverage.byCalculation.rateSensitivity; r.ok = 2; r.unsupported = 0; },
    'note gamma claimed': (a) => { const r = a.jobs[1].coverage.byCalculation.rateGamma; r.ok = 2; r.unsupported = 0; },
    'coverage count drift': (a) => { a.jobs[1].coverage.byCalculation.npv.failed = 1; },
    'all applicable computed': (a) => { a.jobs[1].coverage.allApplicableComputed = true; },
    'remote execution claimed': (a) => { a.producerExecution.location = 'REMOTE'; },
    'execution time missing': (a) => { delete a.producerExecution.completedAt; },
    'bad commit': (a) => { a.traderxCommit = 'HEAD'; },
  };
  for (const [name, mutate] of Object.entries(mutations)) {
    const doc = good(); mutate(doc);
    const { result } = await serve(doc);
    assert.equal(result.status, 503, name); assert.equal(result.body.code, 'ARTIFACT_INVALID', name);
  }
});
