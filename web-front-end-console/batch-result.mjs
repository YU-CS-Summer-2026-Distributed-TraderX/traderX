// Explicit operator selection. HTTP clients cannot select files, run IDs or trust roots.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
const execute = promisify(execFile);
export async function readBatchResult(env = process.env, run = execute) {
  const unavailable = code => ({ status: 503, body: {
    schema: 'traderx.batch-result.v1', availability: 'UNAVAILABLE', usableForRisk: false, code,
  } });
  if (!env.RISK_BATCH_DIRECTORY || !env.RISK_BATCH_SHA256 || !env.RISK_BATCH_STATUS_SCRIPT) return unavailable('NOT_CONFIGURED');
  if (!path.isAbsolute(env.RISK_BATCH_DIRECTORY) || !path.isAbsolute(env.RISK_BATCH_STATUS_SCRIPT)
      || !/^[a-f0-9]{64}$/.test(env.RISK_BATCH_SHA256)) return unavailable('INVALID_CONFIGURATION');
  try {
    const { stdout } = await run(env.EOD_PYTHON || 'python3', [
      '-B', env.RISK_BATCH_STATUS_SCRIPT, 'status', '--directory', env.RISK_BATCH_DIRECTORY,
      '--sha256', env.RISK_BATCH_SHA256,
    ], { env, timeout: 10000, maxBuffer: 128 * 1024 });
    const body = JSON.parse(stdout);
    if (body.schema !== 'traderx.batch-result.v1' || body.availability !== 'AVAILABLE'
        || body.status !== 'VERIFIED' || body.usableForRisk !== false || body.synthetic !== true
        || body.financialValidation !== false || body.scenarioRiskAvailable !== false
        || !Number.isFinite(body.baseNpvUsd) || !Number.isInteger(body.tradeCount) || body.tradeCount < 1
        || typeof body.accountId !== 'string' || !body.accountId || typeof body.runId !== 'string'
        || !body.cut?.cutSha256 || typeof body.market?.rate !== 'string' || !Number.isFinite(Number(body.market.rate))
        || body.currentPortfolioComparison !== 'NOT_CHECKED') throw Error('invalid');
    return { status: 200, body };
  } catch { return unavailable('VERIFICATION_FAILED'); }
}
