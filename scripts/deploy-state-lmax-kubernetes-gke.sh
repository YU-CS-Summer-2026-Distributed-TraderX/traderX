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
echo "[info] edge-proxy is ClusterIP; external access is via ingress-nginx"
echo "[hint] check ingress: kubectl get svc ingress-nginx-controller -n ingress-nginx"
