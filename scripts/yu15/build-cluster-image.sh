#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OM="${ROOT}/generated/code/target-generated/order-matcher"
IMAGE="${YU15_CLUSTER_IMAGE:-traderx/cluster-node:yu15}"

[[ -d "${OM}" ]] || {
  echo "[fail] generated order-matcher missing; run: bash pipeline/generate-state.sh YU15-eod-risk-extract"
  exit 1
}

echo "[build] bootJar (host gradle — container gradle builds stall on this host)"
(cd "${OM}" && ./gradlew -q bootJar)

JAR="$(ls "${OM}"/build/libs/*.jar | grep -v plain | head -1)"
[[ -n "${JAR}" ]] || { echo "[fail] no boot jar built"; exit 1; }

echo "[build] docker image ${IMAGE} from ${JAR}"
# Guarded AND expanded on the same variable. It used to guard on YU15_PLATFORM and expand
# YU14_PLATFORM, so setting YU15_PLATFORM=linux/amd64 passed `--platform` with an EMPTY value
# — invisible on kind (arm64 host, arm64 nodes) and fatal on the first amd64 GKE build, which
# is the project's recurring ImagePullBackOff "no match for platform" trap.
docker build ${YU15_PLATFORM:+--platform ${YU15_PLATFORM}} -f "${OM}/Dockerfile.cluster" \
  --build-arg JAR_FILE="build/libs/$(basename "${JAR}")" \
  -t "${IMAGE}" "${OM}"

echo "[ok] built ${IMAGE}"
