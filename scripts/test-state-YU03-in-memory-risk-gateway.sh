#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
  REPO_ROOT="$(cd "${GENERATED_ROOT}/.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi
EXPECTED_STATE="YU03-in-memory-risk-gateway"
OM_SRC="${GENERATED_ROOT}/code/target-generated/order-matcher/src/main/java/finos/traderx/ordermatcher"
ORDER_MATCHER_PROPS="${GENERATED_ROOT}/code/target-generated/order-matcher/src/main/resources/application.properties"

source "${REPO_ROOT}/scripts/lib/generated-state-detection.sh"

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  TRADERX_SKIP_LOCKFILE_REFRESH=1 bash "${REPO_ROOT}/pipeline/generate-state.sh" "${EXPECTED_STATE}"
fi

echo "[check] generated output state metadata"
traderx_report_generated_state "${EXPECTED_STATE}" "${GENERATED_ROOT}" >/dev/null || {
  echo "[error] generated output does not match expected state ${EXPECTED_STATE}"
  exit 1
}

echo "[check] inherited MariaDB-backed matcher config"
[[ -f "${ORDER_MATCHER_PROPS}" ]] || {
  echo "[error] missing generated order-matcher properties: ${ORDER_MATCHER_PROPS}"
  exit 1
}
rg -q 'jdbc:mariadb://' "${ORDER_MATCHER_PROPS}" || {
  echo "[error] order-matcher is not configured for MariaDB (inherited YU02 invariant)"
  exit 1
}
rg -q 'snapshot.interval.ms=' "${ORDER_MATCHER_PROPS}" || {
  echo "[error] order-matcher snapshot interval configuration missing"
  exit 1
}

echo "[check] risk-gateway configuration"
for key in 'risk.enabled=' 'risk.seed.securities=' 'risk.control.token=' 'risk.credit-limit-ticks='; do
  rg -q "${key}" "${ORDER_MATCHER_PROPS}" || {
    echo "[error] order-matcher risk config missing: ${key}"
    exit 1
  }
done

echo "[check] risk-gateway source overlay present"
for cls in \
  "risk/BlpRiskState.java" \
  "risk/GatewayReplicaStore.java" \
  "risk/RiskReason.java" \
  "risk/ReplicaBootstrap.java" \
  "controller/RiskControlController.java" \
  "controller/RiskExceptionHandler.java"; do
  [[ -f "${OM_SRC}/${cls}" ]] || {
    echo "[error] missing generated risk-gateway class: ${OM_SRC}/${cls}"
    exit 1
  }
done

echo "[check] sequenced control events + BLP risk wiring"
rg -q 'TYPE_ACCOUNT_CONTROL' "${OM_SRC}/lmax/InputEvent.java" || {
  echo "[error] InputEvent is missing the sequenced control-event types"
  exit 1
}
rg -q 'decideAndReserve' "${OM_SRC}/lmax/MatchingEngine.java" || {
  echo "[error] MatchingEngine does not invoke the authoritative BLP risk decision"
  exit 1
}

if [[ "${TRADERX_RUN_LIVE_CHECKS:-0}" == "1" ]]; then
  echo "[info] live runtime checks requested; delegating inherited YU02 baseline smoke first"
  "${GENERATED_ROOT}/code/target-generated/scripts/test-state-YU02-lmax-kubernetes.sh" "$@" || true
fi

echo "[done] YU03-in-memory-risk-gateway generated-state smoke checks passed"
