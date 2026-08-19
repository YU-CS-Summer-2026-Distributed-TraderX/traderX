#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib-state-image.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib-state-image.sh"
OM="${ROOT}/generated/code/target-generated/order-matcher"
# DERIVED, like everything else that answers "which build is this". The default used to be a
# literal traderx/cluster-node:yu15, so running this from another worktree stamped that worktree's
# code :yu15 — the stale-tag class in its most direct form — and the literal-only env handling
# silently IGNORED an explicit CLUSTER_IMAGE (measured 2026-08-18: a build asked to be
# :yu15-ackB landed on :yu15). CLUSTER_IMAGE is the neutral override every sibling script takes;
# YU15_CLUSTER_IMAGE keeps working. (Carried from YU16/YU17's copies.)
IMAGE="${CLUSTER_IMAGE:-${YU15_CLUSTER_IMAGE:-traderx/cluster-node:$(state_tag "${ROOT}")}}"

[[ -d "${OM}" ]] || {
  echo "[fail] generated order-matcher missing; run: bash pipeline/generate-state.sh YU15-eod-risk-extract"
  exit 1
}

# `clean`, and it is load-bearing rather than tidiness. Generation writes sources with mtimes OLDER
# than the previously compiled output, so gradle's up-to-date check judges compileJava current and
# bootJar packages classes predating the regeneration. The image is then tagged for a build it does
# not contain — one :yu17-ackfix image shipped that way. (Carried from YU16/YU17's copies.)
echo "[build] clean bootJar (host gradle — container gradle builds stall on this host)"
(cd "${OM}" && ./gradlew -q clean bootJar)

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
