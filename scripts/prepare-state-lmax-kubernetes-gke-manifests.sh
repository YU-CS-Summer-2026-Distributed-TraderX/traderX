#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: bash scripts/prepare-state-lmax-kubernetes-gke-manifests.sh <artifact-registry-prefix> [output-dir]"
  echo "example: bash scripts/prepare-state-lmax-kubernetes-gke-manifests.sh us-east1-docker.pkg.dev/traderx-501015/traderx"
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "[error] jq command not found"
  exit 1
fi

if ! command -v perl >/dev/null 2>&1; then
  echo "[error] perl command not found"
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${REPO_ROOT}/generated}"
PREFIX="${1%/}"
OUTPUT_DIR="${2:-${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/gke-rendered}"
BUILD_PLAN="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/build-plan.json"
SOURCE_DIR="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/manifests/base"
CORS_ALLOWED_ORIGINS="${TRADERX_CORS_ALLOWED_ORIGINS:-*}"
STATIC_IP="${TRADERX_STATIC_IP:-}"

[[ -f "${BUILD_PLAN}" ]] || {
  echo "[error] missing build plan: ${BUILD_PLAN}"
  echo "[hint] run: bash pipeline/generate-state.sh lmax-kubernetes"
  exit 1
}
[[ -d "${SOURCE_DIR}" ]] || {
  echo "[error] missing source manifests: ${SOURCE_DIR}"
  exit 1
}

rm -rf "${OUTPUT_DIR}"
mkdir -p "${OUTPUT_DIR}"
cp -R "${SOURCE_DIR}/." "${OUTPUT_DIR}/"

# StatefulSet conflict fix: replace Deployment + standalone PVC with StatefulSet.
# Copy before the substitution loop so image tags are rewritten like the other deployments.
cp "${REPO_ROOT}/cluster-addons/order-matcher-statefulset.yaml" "${OUTPUT_DIR}/"
cp "${REPO_ROOT}/cluster-addons/order-matcher-headless-service.yaml" "${OUTPUT_DIR}/"
KUST="${OUTPUT_DIR}/kustomization.yaml"
perl -0pi -e 's/  - order-matcher-lmax-data-pvc\.yaml\n//' "${KUST}"
perl -0pi -e 's/  - order-matcher-deployment\.yaml/  - order-matcher-statefulset.yaml\n  - order-matcher-headless-service.yaml/' "${KUST}"

while IFS=$'\t' read -r name source_image context dockerfile; do
  tag="${source_image##*:}"
  remote_image="${PREFIX}/${name}:${tag}"
  source_escaped="$(printf '%s' "${source_image}" | perl -pe 's/([\\\/])/\\$1/g')"
  remote_escaped="$(printf '%s' "${remote_image}" | perl -pe 's/([\\\/])/\\$1/g')"
  find "${OUTPUT_DIR}" -type f -name '*.yaml' -print0 | while IFS= read -r -d '' file; do
    perl -0pi -e "s/image:\\s*${source_escaped}/image: ${remote_escaped}/g" "${file}"
  done
done < <(jq -r '.images[] | [.name, .image, .context, .dockerfile] | @tsv' "${BUILD_PLAN}")

EDGE_SERVICE="${OUTPUT_DIR}/edge-proxy-service.yaml"
perl -0pi -e 's/type:\s*NodePort/type: ClusterIP/g; s/\n\s*nodePort:\s*\d+\n/\n/g' "${EDGE_SERVICE}"

find "${OUTPUT_DIR}" -type f -name '*.yaml' -print0 | while IFS= read -r -d '' file; do
  perl -0pi -e 's/value:\s*"http:\/\/localhost:8080"/value: "'"${CORS_ALLOWED_ORIGINS}"'"/g' "${file}"
done

# Pod anti-affinity: spread replicas of the same service across nodes
for dep in "${OUTPUT_DIR}"/*-deployment.yaml; do
  app_name="$(grep -m1 '^\s*app:' "${dep}" | awk '{print $2}')"
  [[ -z "${app_name}" ]] && continue
  perl -0pi -e "s/(      containers:)/      affinity:\n        podAntiAffinity:\n          preferredDuringSchedulingIgnoredDuringExecution:\n          - weight: 100\n            podAffinityTerm:\n              topologyKey: kubernetes.io\/hostname\n              labelSelector:\n                matchLabels:\n                  app: ${app_name}\n\$1/m" "${dep}"
done

echo "[done] rendered GKE manifest set at ${OUTPUT_DIR}"
echo "[info] image prefix: ${PREFIX}"
echo "[info] cors allowed origins: ${CORS_ALLOWED_ORIGINS}"
