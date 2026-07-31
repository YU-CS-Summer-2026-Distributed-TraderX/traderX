#!/usr/bin/env bash
# Build the Spring service images the YU15 cluster rig runs, FROM THE CURRENT RENDER.
#
# Why this exists. start-cluster-kind.sh consumes traderx/{trade-processor,position-service,
# price-publisher}:yu15 but nothing in the repo ever built them -- the GKE script's header says
# outright "assumes images are already built". So the tags on a given laptop were whatever was
# built by hand, whenever. That is invisible until a proof fails for a reason that looks like a
# missing feature: yu13-readmodel-effect-end reports "deployed trade-processor lacks the order
# read model" when in fact the SOURCE has it and the local :yu15 tag simply predates it.
#
# Rebuilding from generated/code/target-generated makes the rig reproducible: the images match the
# tree that was rendered, and a proof failure means the code is wrong rather than the tag being old.
#
#   bash scripts/yu15/build-service-images.sh [service ...]
#
# YU15_PLATFORM=linux/amd64 for GKE. Guarded AND expanded on the same variable -- the recurring
# arm64-laptop/amd64-cloud ImagePullBackOff trap.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GEN="${ROOT}/generated/code/target-generated"
TAG="${YU15_SERVICE_TAG:-yu15}"

SERVICES=("$@")
if [[ ${#SERVICES[@]} -eq 0 ]]; then
  SERVICES=(trade-processor position-service price-publisher)
fi

[[ -d "${GEN}" ]] || {
  echo "[fail] no rendered tree at ${GEN}"
  echo "[hint] bash pipeline/generate-state.sh YU15-eod-risk-extract"
  exit 1
}

for svc in "${SERVICES[@]}"; do
  dir="${GEN}/${svc}"
  [[ -d "${dir}" ]] || { echo "[fail] not in the rendered tree: ${svc}"; exit 1; }

  # price-publisher ships only Dockerfile.compose; the others carry both and the plain one is the
  # deployable. Pick whichever exists rather than assuming, so adding a service here does not need
  # this script edited too.
  dockerfile=""
  for candidate in Dockerfile Dockerfile.compose; do
    if [[ -f "${dir}/${candidate}" ]]; then dockerfile="${candidate}"; break; fi
  done
  [[ -n "${dockerfile}" ]] || { echo "[fail] no Dockerfile for ${svc}"; exit 1; }

  if [[ -x "${dir}/gradlew" ]]; then
    echo "[build] ${svc}: bootJar"
    ( cd "${dir}" && ./gradlew --no-daemon -q bootJar )
  fi

  echo "[build] ${svc}: docker image traderx/${svc}:${TAG} (${dockerfile})"
  docker build ${YU15_PLATFORM:+--platform ${YU15_PLATFORM}} \
    -f "${dir}/${dockerfile}" -t "traderx/${svc}:${TAG}" "${dir}" >/dev/null
  echo "[ok] traderx/${svc}:${TAG}"
done

echo "[ok] built ${#SERVICES[@]} service image(s) at tag ${TAG}"
