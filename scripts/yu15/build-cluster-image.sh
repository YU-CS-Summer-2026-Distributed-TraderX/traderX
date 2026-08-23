#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib-state-image.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib-state-image.sh"
# OM_DIR overrides the source tree, and exists for exactly one caller:
# build-stp-boundary-images.sh builds the `pre` side of yu13-stp-and-replace's version
# boundary from a PATCHED COPY of the generated tree. The copy is what keeps the shared
# generated/ directory -- which other lanes build from, with no lock -- from ever holding a
# tree with self-trade prevention removed. Everything else uses the default and should.
OM="${OM_DIR:-${ROOT}/generated/code/target-generated/order-matcher}"
# DERIVED, like everything else that answers "which build is this". The default used to be a
# literal traderx/cluster-node:yu15, so running this from the YU16 worktree built YU16 code and
# stamped it :yu15 — overwriting the YU15 image in the local daemon with something that is not
# YU15, which is the stale-tag class in its most direct form.
#
# state_tag, not declared_cluster_image: the manifests are the authority for what RUNS, but this
# builds what this worktree IS. On a state with no cluster manifest layer those differ, and tagging
# YU17 code :yu16 because YU16's manifests are the ones it would apply is exactly the poison.
IMAGE="${CLUSTER_IMAGE:-${YU15_CLUSTER_IMAGE:-traderx/cluster-node:$(state_tag "${ROOT}")}}"

[[ -d "${OM}" ]] || {
  echo "[fail] generated order-matcher missing; run: bash pipeline/generate-state-$(state_pack "${ROOT}").sh"
  exit 1
}

# `clean`, and it is load-bearing rather than tidiness. Generation writes sources with mtimes OLDER
# than the previously compiled output, so gradle's up-to-date check judges compileJava current and
# bootJar packages classes predating the regeneration. The image is then tagged for a build it does
# not contain, and every downstream check reads a plausible verdict from the wrong code — one
# :yu17-ackfix image shipped without the fix it was named for, and without the state's own feature
# set, so it was not even a YU17 gateway. Nothing about that is visible from the tag.
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
