#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: bash scripts/push-state-lmax-kubernetes-gke-images.sh <artifact-registry-prefix> [generated-root]"
  echo "example: bash scripts/push-state-lmax-kubernetes-gke-images.sh us-east1-docker.pkg.dev/traderx-501015/traderx"
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "[error] docker command not found"
  exit 1
fi

if ! docker buildx version >/dev/null 2>&1; then
  echo "[error] docker buildx is required"
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "[error] jq command not found"
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${2:-${REPO_ROOT}/generated}"
PREFIX="${1%/}"
BUILD_PLAN="${GENERATED_ROOT}/code/target-generated/kubernetes-runtime/build-plan.json"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
TARGET_PLATFORM="${TRADERX_IMAGE_PLATFORM:-linux/amd64}"

[[ -f "${BUILD_PLAN}" ]] || {
  echo "[error] missing build plan: ${BUILD_PLAN}"
  echo "[hint] run: bash pipeline/generate-state.sh lmax-kubernetes"
  exit 1
}

echo "[info] pushing TraderX images to ${PREFIX}"
echo "[info] build platform: ${TARGET_PLATFORM}"

while IFS=$'\t' read -r name source_image context dockerfile; do
  context_dir="${TARGET_ROOT}/${context}"
  dockerfile_path="${context_dir}/${dockerfile}"
  tag="${source_image##*:}"
  remote_image="${PREFIX}/${name}:${tag}"

  [[ -d "${context_dir}" ]] || {
    echo "[error] missing image context: ${context_dir}"
    exit 1
  }
  [[ -f "${dockerfile_path}" ]] || {
    echo "[error] missing dockerfile: ${dockerfile_path}"
    exit 1
  }

  echo
  echo "[build] ${name}"
  echo "[from] ${context_dir} (${dockerfile})"
  echo "[tag] ${remote_image}"
  echo "[push] ${remote_image}"
  docker buildx build \
    --platform "${TARGET_PLATFORM}" \
    -t "${remote_image}" \
    -f "${dockerfile_path}" \
    "${context_dir}" \
    --push
done < <(jq -r '.images[] | [.name, .image, .context, .dockerfile] | @tsv' "${BUILD_PLAN}")

echo
echo "[done] pushed all TraderX app images"
