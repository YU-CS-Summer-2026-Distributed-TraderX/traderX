// Local opt-in read surface. Config paths come only from the server operator, never a request.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
const execute = promisify(execFile);
export async function readEodJobs(env = process.env, run = execute) {
  const unavailable = (code) => ({ status: 503, body: {
    schema: 'traderx.eod-job-status.v1', availability: 'UNAVAILABLE', code,
    usableForRisk: false, workerConnectivity: 'NOT_PROBED',
  } });
  if (!env.EOD_COORDINATOR_STATE || !env.EOD_STATUS_SCRIPT) return unavailable('NOT_CONFIGURED');
  if (!path.isAbsolute(env.EOD_COORDINATOR_STATE) || !path.isAbsolute(env.EOD_STATUS_SCRIPT)) {
    return unavailable('INVALID_CONFIGURATION');
  }
  try {
    const { stdout } = await run(env.EOD_PYTHON || 'python3',
      ['-B', env.EOD_STATUS_SCRIPT, '--state', env.EOD_COORDINATOR_STATE],
      { timeout: 10000, maxBuffer: 4 * 1024 * 1024, env });
    const body = JSON.parse(stdout);
    if (body.schema !== 'traderx.eod-job-status.v1' || body.availability !== 'AVAILABLE'
        || body.usableForRisk !== false || !Array.isArray(body.jobs)) throw new Error('invalid status');
    return { status: 200, body };
  } catch {
    // Do not leak subprocess stderr, private paths or credentials through the public read API.
    return unavailable('READ_FAILED');
  }
}
