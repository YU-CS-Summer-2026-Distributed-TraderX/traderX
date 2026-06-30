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
perl -0pi -e 's/type:\s*NodePort/type: LoadBalancer/g; s/\n\s*nodePort:\s*\d+\n/\n/g' "${EDGE_SERVICE}"

find "${OUTPUT_DIR}" -type f -name '*.yaml' -print0 | while IFS= read -r -d '' file; do
  perl -0pi -e 's/value:\s*"http:\/\/localhost:8080"/value: "'"${CORS_ALLOWED_ORIGINS}"'"/g' "${file}"
done

echo "[done] rendered GKE manifest set at ${OUTPUT_DIR}"
echo "[info] image prefix: ${PREFIX}"
echo "[info] cors allowed origins: ${CORS_ALLOWED_ORIGINS}"
