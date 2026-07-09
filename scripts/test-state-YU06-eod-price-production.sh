#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
  REPO_ROOT="$(cd "${GENERATED_ROOT}/.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi
EXPECTED_STATE="YU06-eod-price-production"
TP_SRC="${GENERATED_ROOT}/code/target-generated/trade-processor/src/main/java/finos/traderx/tradeprocessor"
PS_SRC="${GENERATED_ROOT}/code/target-generated/position-service/src/main/java/finos/traderx/positionservice"
TP_PROPS="${GENERATED_ROOT}/code/target-generated/trade-processor/src/main/resources/application.properties"
PS_PROPS="${GENERATED_ROOT}/code/target-generated/position-service/src/main/resources/application.properties"
DB_CONFIGMAP="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/database-init-configmap.yaml"
DASH_CONFIGMAP="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/observability-grafana-dashboards-configmap.yaml"

source "${REPO_ROOT}/scripts/lib/generated-state-detection.sh"

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  TRADERX_SKIP_LOCKFILE_REFRESH=1 bash "${REPO_ROOT}/pipeline/generate-state.sh" "${EXPECTED_STATE}"
fi

echo "[check] generated output state metadata"
traderx_report_generated_state "${EXPECTED_STATE}" "${GENERATED_ROOT}" >/dev/null || {
  echo "[error] generated output does not match expected state ${EXPECTED_STATE}"
  exit 1
}

echo "[check] trade-processor EOD producer overlay present"
for cls in \
  "model/EodQuality.java" \
  "model/EodPrice.java" \
  "model/EodReport.java" \
  "repository/EodPriceSnapshotRepository.java" \
  "service/EodQualityChecker.java" \
  "service/EodPriceService.java" \
  "service/EodEventPublisher.java" \
  "controller/EodController.java"; do
  [[ -f "${TP_SRC}/${cls}" ]] || { echo "[error] missing generated EOD class: ${TP_SRC}/${cls}"; exit 1; }
done

echo "[check] position-service EOD consumer overlay present"
for cls in \
  "eod/EodSnapshotPrice.java" \
  "eod/EodPriceSnapshotReader.java" \
  "eod/EodPnlRepository.java" \
  "eod/EodPnlConsumer.java"; do
  [[ -f "${PS_SRC}/${cls}" ]] || { echo "[error] missing generated EOD class: ${PS_SRC}/${cls}"; exit 1; }
done

# Shared-file no-clobber verification (research.md): every overridden-by-an-ancestor file must
# carry BOTH the YU06 marker and the ancestor marker, or generation silently dropped one.
echo "[check] trade-processor application.properties: YU06 eod.* AND YU05 markers both survive"
for key in 'eod.quality.staleness-seconds=' 'eod.stream=' 'settlement.t-plus-days=' 'auth.jwt.secret='; do
  rg -q "${key}" "${TP_PROPS}" || { echo "[error] trade-processor props missing (clobber?): ${key}"; exit 1; }
done

echo "[check] PriceHistoryStore: YU06 tickers() AND YU05 twap() both survive"
rg -q 'Set<String> tickers\(\)' "${TP_SRC}/service/PriceHistoryStore.java" || { echo "[error] PriceHistoryStore.tickers() missing"; exit 1; }
rg -q 'twap\(String ticker' "${TP_SRC}/service/PriceHistoryStore.java" || { echo "[error] PriceHistoryStore.twap() clobbered by YU06 override"; exit 1; }

echo "[check] position-service application.properties: YU06 consumer config present"
for key in 'eod.consumer.durable=' 'eod.subject.pnl-done=' 'nats.address='; do
  rg -q "${key}" "${PS_PROPS}" || { echo "[error] position-service props missing: ${key}"; exit 1; }
done

echo "[check] database-init ConfigMap: YU06 EOD tables AND YU05 settlementdate both survive"
for marker in 'eod_price_session' 'eod_price_snapshot' 'eod_position_pnl' 'settlementdate'; do
  rg -qi "${marker}" "${DB_CONFIGMAP}" || { echo "[error] database-init ConfigMap missing (clobber?): ${marker}"; exit 1; }
done

echo "[check] Grafana dashboards ConfigMap: YU06 dashboard AND YU05 dashboard both survive"
rg -q 'traderx-eod-batch-chain' "${DASH_CONFIGMAP}" || { echo "[error] EOD dashboard missing"; exit 1; }
rg -q 'traderx-post-trade-compliance' "${DASH_CONFIGMAP}" || { echo "[error] YU05 dashboard clobbered by YU06 override"; exit 1; }

echo "[check] eod-session-close CronJob manifest present and wired into kustomization"
KUST="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/kustomization.yaml"
[[ -f "${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/eod-session-close-cronjob.yaml" ]] || { echo "[error] CronJob manifest missing"; exit 1; }
rg -q 'eod-session-close-cronjob.yaml' "${KUST}" || { echo "[error] CronJob not referenced in kustomization"; exit 1; }

echo "[done] YU06-eod-price-production generated-state smoke checks passed"
