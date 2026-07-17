#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: bash scripts/prepare-state-YU02-lmax-kubernetes-gke-manifests.sh <artifact-registry-prefix> [output-dir]"
  echo "example: bash scripts/prepare-state-YU02-lmax-kubernetes-gke-manifests.sh us-east1-docker.pkg.dev/traderx-501015/traderx"
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
  echo "[hint] run: bash pipeline/generate-state.sh YU02-lmax-kubernetes"
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
# execution-algo-engine is CI/CD-owned (cluster-addons/execution-algo-engine-deployment.yaml via
# skaffold/Cloud Deploy, unique ci-$SHA tags) — drop it here so a manual full deploy can't revert
# it to a stale fixed tag. Its Service stays kustomization-managed.
perl -0pi -e 's/  - execution-algo-engine-deployment\.yaml\n//' "${KUST}"

# GKE image tags for the services that have NO CI/CD.
#
# Their manifests carry the LOCAL kind tag (state009) because that is what the local harness
# builds and `kind load`s. But in Artifact Registry, :state009 is the stale 2026-07-01 build that
# predates YU04+ — its account-service has no /account/control-snapshot, so the order-matcher's
# risk bootstrap can never complete and admission stays closed: prod silently 503s every order.
# That is exactly what a manual full deploy did on 2026-07-17 (it reverted all seven services and
# broke prod until they were pinned back by hand).
#
# So: rewrite these to the known-good dated build for the GKE render ONLY. kind is unaffected.
# Update GKE_SERVICE_TAG whenever these services are rebuilt+pushed (or override it per-deploy).
# The durable fix is to extend CI/CD to them with unique ci-$SHORT_SHA tags, the way
# order-matcher and execution-algo-engine already work — then this map goes away.
GKE_SERVICE_TAG="${GKE_SERVICE_TAG:-state009-yu09-20260713}"

gke_tag_for() {  # $1=service name, $2=tag from the manifest; echoes the tag to render for GKE
  case "$1" in
    account-service|reference-data|position-service|trade-processor|trade-service|price-publisher|people-service)
      printf '%s' "${GKE_SERVICE_TAG}" ;;
    *)
      printf '%s' "$2" ;;   # CI/CD-owned or no dated build exists — keep the manifest's own tag
  esac
}

while IFS=$'\t' read -r name source_image context dockerfile; do
  tag="${source_image##*:}"
  remote_tag="$(gke_tag_for "${name}" "${tag}")"
  if [[ "${remote_tag}" != "${tag}" ]]; then
    echo "[gke-tag] ${name}: ${tag} -> ${remote_tag} (no CI/CD; see GKE_SERVICE_TAG)"
  fi
  remote_image="${PREFIX}/${name}:${remote_tag}"
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
