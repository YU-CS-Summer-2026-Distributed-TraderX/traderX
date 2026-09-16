// Read-only synthetic pricing demo artifact for the Risk tab. The path is server configuration only;
// the file is re-read and re-validated on every request, so a removed or corrupted artifact reads as
// unavailable rather than as the last good copy. Contract: docs/risk-integration/risk-demo-console.md.
import { readFile } from 'node:fs/promises';
import path from 'node:path';

const SCHEMA = 'traderx.risk-demo.v1';
const MAX_BYTES = 1024 * 1024;
// Only the two original dated synthetic fixtures are admissible (pricing_result.SUPPORTED_BUNDLES).
const BUNDLES = {
  bill: 'c3211337d0c3e61b5276613972fd6af9b6e7e8ec18070019963c61fc8c1b525d',
  note: '1b64bdcb2497423a72ddd7ca70b664a9a1cf6b8b6b595a562b87e6b2ac211dc9',
};
const CALCULATIONS = ['npv', 'accruedInterest', 'rateSensitivity', 'rateGamma', 'theta', 'vega', 'varEs'];
const STATUSES = ['ok', 'unsupported', 'unavailable', 'failed', 'notApplicable'];
const DECIMAL = /^-?\d+(\.\d+)?$/;
const HEX40 = /^[0-9a-f]{40}$/;
const HEX64 = /^[0-9a-f]{64}$/;
const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/;

function check(ok) { if (!ok) throw new Error('invalid artifact'); }
function fields(o, keys) {
  check(o !== null && typeof o === 'object' && !Array.isArray(o));
  const actual = Object.keys(o).sort();
  check(actual.length === keys.length && [...keys].sort().every((k, i) => k === actual[i]));
}
function dec(v) { check(typeof v === 'string' && DECIMAL.test(v)); return Number(v); }
function str(v, re) { check(typeof v === 'string' && (re ? re.test(v) : v.length > 0 && v.length <= 200)); }

function comparison(c) {
  fields(c, ['alexUsd', 'referenceUsd', 'differenceUsd', 'toleranceUsd', 'withinTolerance']);
  const alex = dec(c.alexUsd), ref = dec(c.referenceUsd), diff = dec(c.differenceUsd), tol = dec(c.toleranceUsd);
  check(c.toleranceUsd === '0.00000001' && c.withinTolerance === true);
  // ponytail: float cross-check of the generator's Decimal arithmetic; 1e-9 slack covers binary rounding.
  check(Math.abs(alex - ref - diff) <= 1e-9 && Math.abs(diff) <= tol);
}

function job(j, instrument) {
  fields(j, ['instrument', 'jobId', 'bundleId', 'status', 'resultIntegrity', 'resultSha256', 'validatedAt',
    'attemptCount', 'clusterEpoch', 'cut', 'positions', 'coverage']);
  check(j.instrument === instrument && j.bundleId === BUNDLES[instrument]);
  str(j.jobId);
  check(j.status === 'SYNTHETIC_PRICING_VALIDATED' && j.resultIntegrity === 'VERIFIED');
  str(j.resultSha256, HEX64); str(j.validatedAt, ISO);
  check(Number.isInteger(j.attemptCount) && j.attemptCount >= 1);
  check(j.clusterEpoch === 'synthetic-shared-examples-v1');
  fields(j.cut, ['sessionDate', 'consensusSequence', 'priceSnapshotVersion', 'cutSha256']);
  check(j.cut.sessionDate === '2025-06-02' && j.cut.consensusSequence === '8' && j.cut.priceSnapshotVersion === '1');
  str(j.cut.cutSha256, HEX64);
  check(Array.isArray(j.positions) && j.positions.length === 2);
  ['long', 'short'].forEach((side, i) => {
    const p = j.positions[i];
    fields(p, ['side', 'signedFaceUsd', 'npv', 'noteDetail']);
    check(p.side === side && p.signedFaceUsd === (side === 'long' ? '100000' : '-100000'));
    comparison(p.npv);
    if (instrument === 'bill') return check(p.noteDetail === null);
    const n = p.noteDetail;
    fields(n, ['cleanNpv', 'accruedInterest', 'dirtyMinusCleanMinusAccruedUsd', 'accrualReconciliation', 'parallelBump1bpUsd']);
    comparison(n.cleanNpv); comparison(n.accruedInterest); comparison(n.parallelBump1bpUsd);
    check(Math.abs(dec(n.dirtyMinusCleanMinusAccruedUsd)) <= 1e-8);
    const r = n.accrualReconciliation;
    fields(r, ['exportedFraction', 'recomputedFraction', 'differenceUsd', 'boundUsd', 'withinBound']);
    check(r.exportedFraction === '0.018571' && dec(r.recomputedFraction) > 0 && r.withinBound === true);
    check(Math.abs(dec(r.differenceUsd)) <= dec(r.boundUsd));
  });
  const c = j.coverage;
  fields(c, ['itemCount', 'byCalculation', 'allOutcomesAccountedFor', 'allApplicableComputed']);
  check(c.itemCount === 2 && c.allOutcomesAccountedFor === true && c.allApplicableComputed === false);
  fields(c.byCalculation, CALCULATIONS);
  const expected = { npv: 'ok', accruedInterest: 'ok', rateSensitivity: instrument === 'note' ? 'ok' : 'unsupported',
    rateGamma: 'unsupported', theta: 'unsupported', vega: 'notApplicable', varEs: 'notApplicable' };
  for (const calc of CALCULATIONS) {
    const counts = c.byCalculation[calc];
    fields(counts, STATUSES);
    check(STATUSES.every((s) => Number.isInteger(counts[s]) && counts[s] >= 0));
    check(counts[expected[calc]] === 2 && STATUSES.reduce((sum, s) => sum + counts[s], 0) === 2);
  }
}

export function validateRiskDemo(a) {
  fields(a, ['schema', 'scope', 'usableForRisk', 'portfolioRiskAvailable', 'workerConnectivity', 'businessDate',
    'valuationTime', 'assumedMarketProfile', 'marketProvenance', 'generatedAt', 'traderxCommit',
    'compatibilityProfile', 'producerExecution', 'jobs']);
  check(a.schema === SCHEMA && a.scope === 'synthetic-fixture-validation' && a.usableForRisk === false
    && a.portfolioRiskAvailable === false && a.workerConnectivity === 'NOT_PROBED'
    && a.businessDate === '2025-06-02' && a.valuationTime === '2025-06-02T16:00:00-04:00'
    && a.assumedMarketProfile === 'flat-3pct-v1' && a.marketProvenance === 'assumed');
  str(a.generatedAt, ISO); str(a.traderxCommit, HEX40);
  // The producer ran on an operator machine; this route only serves the resulting evidence.
  fields(a.producerExecution, ['location', 'completedAt']);
  check(a.producerExecution.location === 'LOCAL'); str(a.producerExecution.completedAt, ISO);
  fields(a.compatibilityProfile, ['adapter', 'engineCommit']);
  check(a.compatibilityProfile.adapter === 'alex-pricing-local-provisional-v1'
    && a.compatibilityProfile.engineCommit === 'e7246e1765a2f9b4d4dd6049c97d1baa66319be7');
  check(Array.isArray(a.jobs) && a.jobs.length === 2);
  job(a.jobs[0], 'bill'); job(a.jobs[1], 'note');
  check(a.jobs[0].jobId !== a.jobs[1].jobId);
  return a;
}

export async function readRiskDemo(env = process.env, read = readFile) {
  const unavailable = (code) => ({ status: 503, body: { schema: SCHEMA, availability: 'UNAVAILABLE', code, usableForRisk: false } });
  if (!env.RISK_DEMO_ARTIFACT) return unavailable('NOT_CONFIGURED');
  if (!path.isAbsolute(env.RISK_DEMO_ARTIFACT)) return unavailable('INVALID_CONFIGURATION');
  try {
    const bytes = await read(env.RISK_DEMO_ARTIFACT);
    check(bytes.length <= MAX_BYTES);
    const artifact = validateRiskDemo(JSON.parse(bytes.toString('utf8')));
    return { status: 200, body: { schema: SCHEMA, availability: 'AVAILABLE', servedAt: new Date().toISOString(), artifact } };
  } catch {
    // No path, parse error or validation detail leaves the server.
    return unavailable('ARTIFACT_INVALID');
  }
}
