#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: bash scripts/deploy-state-lmax-kubernetes-gke.sh <artifact-registry-prefix> [output-dir]"
  echo "example: bash scripts/deploy-state-lmax-kubernetes-gke.sh us-east1-docker.pkg.dev/traderx-501015/traderx"
  exit 1
fi

if ! command -v kubectl >/dev/null 2>&1; then
  echo "[error] kubectl command not found"
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="${1%/}"
OUTPUT_DIR="${2:-${REPO_ROOT}/generated/code/target-generated/kubernetes-runtime/manifests/gke-rendered}"
NAMESPACE="${TRADERX_K8S_NAMESPACE:-traderx}"

bash "${REPO_ROOT}/scripts/prepare-state-lmax-kubernetes-gke-manifests.sh" "${PREFIX}" "${OUTPUT_DIR}"

echo "[apply] kubectl apply -k ${OUTPUT_DIR}"
kubectl apply -k "${OUTPUT_DIR}"

echo "[wait] deployments available in namespace ${NAMESPACE}"
kubectl wait --for=condition=Available deployment --all -n "${NAMESPACE}" --timeout=900s

echo
echo "[status] services"
kubectl get svc -n "${NAMESPACE}"

echo
echo "[wait] edge-proxy external endpoint"
for ((i=1; i<=60; i++)); do
  endpoint="$(kubectl get svc edge-proxy -n "${NAMESPACE}" -o jsonpath='{.status.loadBalancer.ingress[0].ip}{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
  if [[ -n "${endpoint}" ]]; then
    echo "[done] edge-proxy endpoint: ${endpoint}"
    echo "[ui] http://${endpoint}:8080"
    echo "[api-explorer] http://${endpoint}:8080/api/docs"
    exit 0
  fi
  sleep 10
done

echo "[warn] edge-proxy does not have an external IP yet"
echo "[hint] run: kubectl get svc edge-proxy -n ${NAMESPACE} -w"
