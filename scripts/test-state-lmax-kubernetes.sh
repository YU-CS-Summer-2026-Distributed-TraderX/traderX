#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${TRADERX_GENERATED_ROOT:-}" ]]; then
  GENERATED_ROOT="${TRADERX_GENERATED_ROOT}"
  REPO_ROOT="$(cd "${GENERATED_ROOT}/.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  GENERATED_ROOT="${REPO_ROOT}/generated"
fi
EXPECTED_STATE="lmax-kubernetes"
ORDER_MATCHER_PROPS="${GENERATED_ROOT}/code/target-generated/order-matcher/src/main/resources/application.properties"
K8S_ORDER_MATCHER_DEPLOY="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/order-matcher-deployment.yaml"
K8S_ORDER_MATCHER_PVC="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/order-matcher-lmax-data-pvc.yaml"
K8S_KUSTOMIZATION="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/kustomization.yaml"
TRADE_SERVICE_DEPLOY="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base/trade-service-deployment.yaml"
TILT_ORDER_MATCHER_DEPLOY="${GENERATED_ROOT}/code/target-generated/tilt-kubernetes-dev-loop/manifests/base/order-matcher-deployment.yaml"
TILT_ORDER_MATCHER_PVC="${GENERATED_ROOT}/code/target-generated/tilt-kubernetes-dev-loop/manifests/base/order-matcher-lmax-data-pvc.yaml"

source "${REPO_ROOT}/scripts/lib/generated-state-detection.sh"

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  TRADERX_SKIP_LOCKFILE_REFRESH=1 bash "${REPO_ROOT}/pipeline/generate-state.sh" "${EXPECTED_STATE}"
fi

echo "[check] generated output state metadata"
traderx_report_generated_state "${EXPECTED_STATE}" "${GENERATED_ROOT}" >/dev/null || {
  echo "[error] generated output does not match expected state ${EXPECTED_STATE}"
  exit 1
}

echo "[check] MariaDB-backed matcher config"
[[ -f "${ORDER_MATCHER_PROPS}" ]] || {
  echo "[error] missing generated order-matcher properties: ${ORDER_MATCHER_PROPS}"
  exit 1
}
rg -q 'jdbc:mariadb://' "${ORDER_MATCHER_PROPS}" || {
  echo "[error] order-matcher is not configured for MariaDB"
  exit 1
}
rg -q 'management.endpoint.health.group.readiness.include=readinessState,lmaxRecovery' "${ORDER_MATCHER_PROPS}" || {
  echo "[error] order-matcher readiness group is missing lmaxRecovery"
  exit 1
}
rg -q 'snapshot.interval.ms=' "${ORDER_MATCHER_PROPS}" || {
  echo "[error] order-matcher snapshot interval configuration missing"
  exit 1
}
rg -q 'journal.batch.records=' "${ORDER_MATCHER_PROPS}" || {
  echo "[error] order-matcher journal batch records configuration missing"
  exit 1
}
rg -q 'blp.terminal.retain=' "${ORDER_MATCHER_PROPS}" || {
  echo "[error] order-matcher BLP terminal retain configuration missing"
  exit 1
}

echo "[check] Kubernetes runtime order-matcher semantics"
for required in "${K8S_ORDER_MATCHER_DEPLOY}" "${K8S_ORDER_MATCHER_PVC}" "${K8S_KUSTOMIZATION}" "${TRADE_SERVICE_DEPLOY}" "${TILT_ORDER_MATCHER_DEPLOY}" "${TILT_ORDER_MATCHER_PVC}"; do
  [[ -f "${required}" ]] || {
    echo "[error] missing expected generated artifact: ${required}"
    exit 1
  }
done

rg -q 'path: /actuator/health/readiness' "${K8S_ORDER_MATCHER_DEPLOY}" || {
  echo "[error] Kubernetes order-matcher readiness probe missing"
  exit 1
}
rg -q 'path: /actuator/health/liveness' "${K8S_ORDER_MATCHER_DEPLOY}" || {
  echo "[error] Kubernetes order-matcher liveness probe missing"
  exit 1
}
rg -q 'claimName: order-matcher-lmax-data' "${K8S_ORDER_MATCHER_DEPLOY}" || {
  echo "[error] Kubernetes order-matcher PVC mount missing"
  exit 1
}
rg -q 'ORDER_MATCHER_JOURNAL_PATH' "${K8S_ORDER_MATCHER_DEPLOY}" || {
  echo "[error] Kubernetes order-matcher journal path env missing"
  exit 1
}
rg -q 'order-matcher-lmax-data-pvc.yaml' "${K8S_KUSTOMIZATION}" || {
  echo "[error] Kubernetes kustomization does not include the matcher PVC"
  exit 1
}
rg -q 'ORDER_MATCHER_URL' "${TRADE_SERVICE_DEPLOY}" || {
  echo "[error] trade-service deployment does not route to order-matcher"
  exit 1
}
rg -q 'order-matcher-primary' "${TRADE_SERVICE_DEPLOY}" || {
  echo "[error] trade-service does not route to order-matcher-primary (replication service not wired)"
  exit 1
}
rg -q 'path: /actuator/health/readiness' "${TILT_ORDER_MATCHER_DEPLOY}" || {
  echo "[error] Tilt order-matcher readiness probe missing"
  exit 1
}
rg -q 'claimName: order-matcher-lmax-data' "${TILT_ORDER_MATCHER_DEPLOY}" || {
  echo "[error] Tilt order-matcher PVC mount missing"
  exit 1
}

if [[ "${TRADERX_RUN_LIVE_CHECKS:-0}" == "1" ]]; then
  echo "[info] live runtime checks requested; delegating inherited baseline smoke first"
  "${REPO_ROOT}/generated/code/target-generated/scripts/test-state-014-fdc3-intent-interoperability.sh" "$@"
fi

echo "[done] lmax-kubernetes generated-state smoke checks passed"
