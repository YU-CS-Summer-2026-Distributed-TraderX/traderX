#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
STATE_ID="${1:-}"
TARGET_ROOT="${2:-${GENERATED_ROOT}/code/target-generated}"
COMPONENTS_ROOT="${3:-${GENERATED_ROOT}/code/components}"

if [[ -z "${STATE_ID}" ]]; then
  echo "usage: bash pipeline/validate-generated-ui-status-checks.sh <state-id> [target-root] [components-root]"
  exit 1
fi

# shellcheck source=lib/state-rank.sh
source "${ROOT}/pipeline/lib/state-rank.sh"
STATE_RANK="$(traderx_state_rank "${ROOT}/catalog/state-catalog.json" "${STATE_ID}")" || {
  echo "[fail] unable to rank state id: ${STATE_ID}"
  exit 1
}

ROOT="${ROOT}" \
STATE_ID="${STATE_ID}" \
STATE_RANK="${STATE_RANK}" \
TARGET_ROOT="${TARGET_ROOT}" \
COMPONENTS_ROOT="${COMPONENTS_ROOT}" \
node <<'NODE'
const fs = require('node:fs');
const path = require('node:path');

const stateId = process.env.STATE_ID;
const targetRoot = process.env.TARGET_ROOT;
const componentsRoot = process.env.COMPONENTS_ROOT;

function fail(message) {
  console.error(`[fail] ${message}`);
  process.exit(1);
}

// Ranked by pipeline/lib/state-rank.sh and passed in, so the producer of state-ui.json and
// its validator read one number by construction instead of two parsers agreeing.
const stateNo = Number.parseInt(process.env.STATE_RANK || '', 10);
if (!Number.isInteger(stateNo)) {
  fail(`STATE_RANK not supplied for ${stateId}`);
}

const requiredBase = ['account-service', 'reference-data', 'position-service', 'trade-service', 'people-service'];
const requiredProxy = ['edge-health'];
const requiredPreNats = ['trade-feed'];
const requiredPostNats = ['nats-ws'];
const requiredPricing = ['price-publisher'];
const requiredOrderMgmt = ['order-matcher'];

const forbiddenPostNats = ['trade-feed'];
const forbiddenPreNats = ['nats-ws'];
const forbiddenPrePricing = ['price-publisher'];
const forbiddenPreOrderMgmt = ['order-matcher'];

const candidatePaths = [
  path.join(componentsRoot, 'web-front-end-angular-specfirst', 'main', 'assets', 'state-ui.json'),
  path.join(targetRoot, 'web-front-end', 'angular', 'main', 'assets', 'state-ui.json'),
];

const existing = candidatePaths.filter((p) => fs.existsSync(p));
if (existing.length === 0) {
  fail('no generated UI metadata files found');
}

for (const metadataPath of existing) {
  const raw = fs.readFileSync(metadataPath, 'utf8');
  const metadata = JSON.parse(raw);
  const checks = Array.isArray(metadata.statusChecks) ? metadata.statusChecks : [];
  const ids = checks.map((check) => check && check.id).filter(Boolean);
  const idSet = new Set(ids);

  if (typeof metadata.runtimeMetadataUrl !== 'string' || metadata.runtimeMetadataUrl.trim() === '') {
    fail(`${metadataPath}: missing runtimeMetadataUrl`);
  }
  if (stateNo >= 2 && metadata.features?.statusPage !== true) {
    fail(`${metadataPath}: expected features.statusPage=true for state ${stateId}`);
  }
  if (stateNo < 2 && metadata.features?.statusPage !== false) {
    fail(`${metadataPath}: expected features.statusPage=false for state ${stateId}`);
  }
  if (stateNo >= 2 && metadata.features?.apiExplorer !== true) {
    fail(`${metadataPath}: expected features.apiExplorer=true for state ${stateId}`);
  }
  if (stateNo < 2 && metadata.features?.apiExplorer !== false) {
    fail(`${metadataPath}: expected features.apiExplorer=false for state ${stateId}`);
  }
  if (stateNo >= 8 && metadata.features?.pubSubInspector !== true) {
    fail(`${metadataPath}: expected features.pubSubInspector=true for state ${stateId}`);
  }
  if (stateNo < 8 && metadata.features?.pubSubInspector !== false) {
    fail(`${metadataPath}: expected features.pubSubInspector=false for state ${stateId}`);
  }
  if (metadata.features?.apiExplorer === true && (typeof metadata.apiExplorerUrl !== 'string' || metadata.apiExplorerUrl.trim() === '')) {
    fail(`${metadataPath}: expected non-empty apiExplorerUrl when features.apiExplorer=true`);
  }
  if (metadata.features?.pubSubInspector === true) {
    if (typeof metadata.pubSubInspectorUrl !== 'string' || metadata.pubSubInspectorUrl.trim() === '') {
      fail(`${metadataPath}: expected non-empty pubSubInspectorUrl when features.pubSubInspector=true`);
    }
    if (!metadata.pubSubInspectorUrl.includes('pubsub-inspector')) {
      fail(`${metadataPath}: expected pubSubInspectorUrl to reference pubsub-inspector`);
    }
  }

  if (ids.length !== idSet.size) {
    fail(`${metadataPath}: duplicate statusChecks ids detected`);
  }

  for (const check of checks) {
    if (!check || typeof check !== 'object') {
      fail(`${metadataPath}: invalid status check entry`);
    }
    if (typeof check.id !== 'string' || check.id.trim() === '') {
      fail(`${metadataPath}: status check with missing id`);
    }
    if (typeof check.name !== 'string' || check.name.trim() === '') {
      fail(`${metadataPath}: status check ${check.id} missing name`);
    }
    if (typeof check.url !== 'string' || check.url.trim() === '') {
      fail(`${metadataPath}: status check ${check.id} missing url`);
    }
    if (!Array.isArray(check.expectedStatuses) || check.expectedStatuses.length === 0) {
      fail(`${metadataPath}: status check ${check.id} missing expectedStatuses`);
    }
  }

  const required = [...requiredBase];
  const forbidden = [];
  if (stateNo >= 2) {
    required.push(...requiredProxy);
  }
  if (stateNo < 6) {
    required.push(...requiredPreNats);
    forbidden.push(...forbiddenPreNats);
  } else {
    required.push(...requiredPostNats);
    forbidden.push(...forbiddenPostNats);
  }
  if (stateNo >= 8) {
    required.push(...requiredPricing);
  } else {
    forbidden.push(...forbiddenPrePricing);
  }
  if (stateNo >= 9) {
    required.push(...requiredOrderMgmt);
  } else {
    forbidden.push(...forbiddenPreOrderMgmt);
  }

  for (const id of required) {
    if (!idSet.has(id)) {
      fail(`${metadataPath}: required status check id missing for ${stateId}: ${id}`);
    }
  }
  for (const id of forbidden) {
    if (idSet.has(id)) {
      fail(`${metadataPath}: forbidden status check id present for ${stateId}: ${id}`);
    }
  }
}

console.log(`[ok] ui status metadata validation passed for ${stateId} (${existing.length} file(s))`);
NODE
