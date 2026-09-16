import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readEodJobs } from './eod-jobs.mjs';
const env = { EOD_COORDINATOR_STATE: '/private/tmp/state', EOD_STATUS_SCRIPT: '/component/job_status.py' };
test('unconfigured is unavailable and executes nothing', async () => {
  const result = await readEodJobs({}, () => { throw new Error('must not run'); });
  assert.equal(result.status,503); assert.equal(result.body.code,'NOT_CONFIGURED');
});
test('fixed operator paths, bounded process and no shell', async () => {
  const result = await readEodJobs(env, async (command,args,options) => {
    assert.equal(command,'python3'); assert.deepEqual(args,['-B',env.EOD_STATUS_SCRIPT,'--state',env.EOD_COORDINATOR_STATE]);
    assert.equal(options.timeout,10000);assert.equal(options.shell,undefined);
    return {stdout:JSON.stringify({schema:'traderx.eod-job-status.v1',availability:'AVAILABLE',usableForRisk:false,jobs:[]})};
  });
  assert.equal(result.status,200);assert.deepEqual(result.body.jobs,[]);
});
test('failed or malformed reads never turn into successful empty results', async () => {
  for (const run of [async()=>{throw new Error('/private/secret');},async()=>({stdout:'<html>'}),async()=>({stdout:'{"jobs":[]}'})]) {
    const result=await readEodJobs(env,run);
    assert.equal(result.status,503);assert.equal(result.body.code,'READ_FAILED');
    assert.ok(!JSON.stringify(result).includes('/private/secret'));
  }
});
