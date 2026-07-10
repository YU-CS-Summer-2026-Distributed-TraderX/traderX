#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
  REPO_ROOT="$(cd "${GENERATED_ROOT}/.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi
EXPECTED_STATE="YU08-execution-algo-engine"
ALGO_ENGINE_DIR="${GENERATED_ROOT}/code/target-generated/execution-algo-engine"
KUST="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/kustomization.yaml"

source "${REPO_ROOT}/scripts/lib/generated-state-detection.sh"

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  TRADERX_SKIP_LOCKFILE_REFRESH=1 bash "${REPO_ROOT}/pipeline/generate-state.sh" "${EXPECTED_STATE}"
fi

echo "[check] generated output state metadata"
traderx_report_generated_state "${EXPECTED_STATE}" "${GENERATED_ROOT}" >/dev/null || {
  echo "[error] generated output does not match expected state ${EXPECTED_STATE}"
  exit 1
}

echo "[check] execution-algo-engine component present"
for f in \
  "build.gradle" \
  "Dockerfile" \
  "src/main/java/finos/traderx/algoengine/AlgoEngineApplication.java" \
  "src/main/java/finos/traderx/algoengine/service/AlgoOrderService.java" \
  "src/main/java/finos/traderx/algoengine/eventstore/AlgoEventStore.java" \
  "src/main/java/finos/traderx/algoengine/volume/DuckDbVolumeProfileSource.java" \
  "src/main/java/finos/traderx/algoengine/api/AlgoOrderController.java"; do
  [[ -f "${ALGO_ENGINE_DIR}/${f}" ]] || { echo "[error] missing generated execution-algo-engine file: ${ALGO_ENGINE_DIR}/${f}"; exit 1; }
done

# Shared-file no-clobber verification (research.md / generation-hook.md): kustomization.yaml is
# the one file overridden by every ancestor through YU07 AND by YU08 -- every ancestor's resource
# entry must survive alongside YU08's two additions, or generation silently clobbered them.
echo "[check] kustomization.yaml: ancestor entries AND YU08 entries both survive"
for marker in \
  'eod-session-close-cronjob.yaml' \
  'order-matcher-lmax-data-pvc.yaml' \
  'tick-store-deployment.yaml' \
  'execution-algo-engine-deployment.yaml' \
  'execution-algo-engine-service.yaml'; do
  rg -q "${marker}" "${KUST}" || { echo "[error] kustomization.yaml missing (clobber?): ${marker}"; exit 1; }
done

echo "[check] execution-algo-engine deployment wired (order-matcher/price-publisher URLs, port 18120)"
DEPLOY="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/execution-algo-engine-deployment.yaml"
rg -q 'ORDER_MATCHER_URL' "${DEPLOY}" || { echo "[error] execution-algo-engine-deployment.yaml missing ORDER_MATCHER_URL"; exit 1; }
rg -q 'PRICE_SERVICE_URL' "${DEPLOY}" || { echo "[error] execution-algo-engine-deployment.yaml missing PRICE_SERVICE_URL"; exit 1; }
rg -q '18120' "${DEPLOY}" || { echo "[error] execution-algo-engine-deployment.yaml missing port 18120"; exit 1; }

echo "[check] execution-algo-engine unit tests pass"
if command -v java >/dev/null 2>&1; then
  (cd "${ALGO_ENGINE_DIR}" && ./gradlew test -q) || { echo "[error] execution-algo-engine tests failed"; exit 1; }
else
  echo "[warn] java not available in this environment; skipping in-process test run"
fi

echo "[done] YU08-execution-algo-engine generated-state smoke checks passed"
