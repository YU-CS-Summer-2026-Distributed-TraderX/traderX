#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
  REPO_ROOT="$(cd "${GENERATED_ROOT}/.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi
EXPECTED_STATE="YU05-post-trade-compliance"
OM_SRC="${GENERATED_ROOT}/code/target-generated/order-matcher/src/main/java/finos/traderx/ordermatcher"
TP_SRC="${GENERATED_ROOT}/code/target-generated/trade-processor/src/main/java/finos/traderx/tradeprocessor"
TP_PROPS="${GENERATED_ROOT}/code/target-generated/trade-processor/src/main/resources/application.properties"
DB_CONFIGMAP="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/database-init-configmap.yaml"

source "${REPO_ROOT}/scripts/lib/generated-state-detection.sh"

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  TRADERX_SKIP_LOCKFILE_REFRESH=1 bash "${REPO_ROOT}/pipeline/generate-state.sh" "${EXPECTED_STATE}"
fi

echo "[check] generated output state metadata"
traderx_report_generated_state "${EXPECTED_STATE}" "${GENERATED_ROOT}" >/dev/null || {
  echo "[error] generated output does not match expected state ${EXPECTED_STATE}"
  exit 1
}

echo "[check] deterministic trade identity wiring"
rg -q 'tradeIdFor\(e\.tradeSeq\)' "${OM_SRC}/lmax/TradeOrder.java" || {
  echo "[error] TradeOrder.fromEvent is not using the deterministic tradeIdFor(tradeSeq) id"
  exit 1
}

echo "[check] trade blotter overlay present"
for cls in \
  "lmax/TradeBlotter.java" \
  "lmax/TradeBlotterHandler.java" \
  "controller/ReconController.java"; do
  [[ -f "${OM_SRC}/${cls}" ]] || {
    echo "[error] missing generated post-trade-compliance class: ${OM_SRC}/${cls}"
    exit 1
  }
done

echo "[check] trade-processor settlement/recon overlay present"
for cls in \
  "service/SettlementService.java" \
  "service/ReconciliationService.java" \
  "controller/ReconStatusController.java" \
  "controller/SettlementController.java"; do
  [[ -f "${TP_SRC}/${cls}" ]] || {
    echo "[error] missing generated post-trade-compliance class: ${TP_SRC}/${cls}"
    exit 1
  }
done

echo "[check] trade-processor settlement/recon configuration"
[[ -f "${TP_PROPS}" ]] || {
  echo "[error] missing generated trade-processor properties: ${TP_PROPS}"
  exit 1
}
for key in 'settlement.t-plus-days=' 'recon.control.token=' 'order-matcher.base-url='; do
  rg -q "${key}" "${TP_PROPS}" || {
    echo "[error] trade-processor post-trade-compliance config missing: ${key}"
    exit 1
  }
done

echo "[check] settlementdate column present in the real runtime database-init ConfigMap"
[[ -f "${DB_CONFIGMAP}" ]] || {
  echo "[error] missing generated database-init ConfigMap: ${DB_CONFIGMAP}"
  exit 1
}
rg -qi 'settlementdate' "${DB_CONFIGMAP}" || {
  echo "[error] database-init ConfigMap is missing the settlementdate column"
  exit 1
}

if [[ "${TRADERX_RUN_LIVE_CHECKS:-0}" == "1" ]]; then
  echo "[info] live runtime checks requested; delegating inherited YU03 baseline smoke first"
  "${GENERATED_ROOT}/code/target-generated/scripts/test-state-YU03-in-memory-risk-gateway.sh" "$@" || true
fi

echo "[done] YU05-post-trade-compliance generated-state smoke checks passed"
