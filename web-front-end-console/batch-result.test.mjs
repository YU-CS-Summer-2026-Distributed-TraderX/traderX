import test from 'node:test';
import assert from 'node:assert/strict';
import { readBatchResult } from './batch-result.mjs';
const env = { RISK_BATCH_DIRECTORY: '/private/retained/run', RISK_BATCH_SHA256: 'a'.repeat(64), RISK_BATCH_STATUS_SCRIPT: '/owned/status.py' };
const valid = { schema:'traderx.batch-result.v1', availability:'AVAILABLE', status:'VERIFIED', usableForRisk:false,
  synthetic:true, financialValidation:false, scenarioRiskAvailable:false, baseNpvUsd:12, tradeCount:1,
  market:{rate:'0.05'}, accountId:'22214', runId:'run', cut:{cutSha256:'c'.repeat(64)}, currentPortfolioComparison:'NOT_CHECKED' };
test('unconfigured and relative paths never run a subprocess', async () => {
  const never = () => { throw Error('must not run'); };
  assert.equal((await readBatchResult({}, never)).body.code, 'NOT_CONFIGURED');
  assert.equal((await readBatchResult({...env, RISK_BATCH_DIRECTORY:'relative'}, never)).body.code, 'INVALID_CONFIGURATION');
});
test('explicit fixed invocation has bounded output/time and returns verified values', async () => {
  const r = await readBatchResult(env, async (exe, args, options) => {
    assert.equal(exe, 'python3'); assert.deepEqual(args, ['-B', '/owned/status.py', 'status', '--directory', env.RISK_BATCH_DIRECTORY, '--sha256', env.RISK_BATCH_SHA256]);
    assert.equal(options.timeout, 10000); assert.equal(options.maxBuffer, 131072);
    return {stdout: JSON.stringify(valid)};
  });
  assert.deepEqual(r, {status:200,body:valid});
});
test('fail closed without stale data or private exceptions', async () => {
  for (const body of [{...valid,financialValidation:true},{...valid,tradeCount:0},{...valid,baseNpvUsd:null},{}]) {
    const r=await readBatchResult(env, async()=>({stdout:JSON.stringify(body)}));
    assert.equal(r.status,503); assert.equal(r.body.baseNpvUsd,undefined);
  }
  const r=await readBatchResult(env, async()=>{throw Error('/private/secret');});
  assert.equal(r.status,503); assert.ok(!JSON.stringify(r).includes('secret'));
});
