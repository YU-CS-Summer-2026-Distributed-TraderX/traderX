#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OM="${ROOT}/generated/code/target-generated/order-matcher"
IMAGE="${YU12_CLUSTER_IMAGE:-traderx/cluster-node:yu12}"

[[ -d "${OM}" ]] || {
  echo "[fail] generated order-matcher missing; run: bash pipeline/generate-state.sh YU12-aeron-cluster"
  exit 1
}

echo "[build] bootJar (host gradle — container gradle builds stall on this host)"
(cd "${OM}" && ./gradlew -q bootJar)

JAR="$(ls "${OM}"/build/libs/*.jar | grep -v plain | head -1)"
[[ -n "${JAR}" ]] || { echo "[fail] no boot jar built"; exit 1; }

echo "[build] docker image ${IMAGE} from ${JAR}"
docker build ${YU12_PLATFORM:+--platform ${YU12_PLATFORM}} -f "${OM}/Dockerfile.cluster" \
  --build-arg JAR_FILE="build/libs/$(basename "${JAR}")" \
  -t "${IMAGE}" "${OM}"

echo "[ok] built ${IMAGE}"
