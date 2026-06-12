#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
STATE_ID="009b-lmax-sequencer-architecture"

# No-GC conformance gate (state 009b, NGC-02 / SC-NGC-01 / SC-09B05, task T09B18).
#
# Runs the hot-path allocation gate for every node hosting LMAX hot-path code — in this
# state that is the order-matcher (gateway encode, input ring, journaler/replicator, BLP,
# output ring emit). The Gradle `noGcTest` task executes AllocationGateTest under
#   -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms256m -Xmx256m -XX:+AlwaysPreTouch
# Epsilon never reclaims, so any steady-state allocation exhausts the fixed heap and the
# run FAILS; the test additionally asserts exact zero allocated-byte deltas for the
# producer, journaler, and BLP threads via ThreadMXBean, and re-runs as part of the
# regular `test` task under the default collector.
#
# Usage:
#   pipeline/validate-no-gc-conformance.sh [order-matcher-module-dir]
# Default module: ${TRADERX_GENERATED_ROOT:-generated}/code/target-generated/order-matcher
# (produce it with: bash pipeline/generate-state.sh 009b-lmax-sequencer-architecture)

MODULE_DIR="${GENERATED_ROOT}/code/target-generated/order-matcher"
if [[ $# -gt 0 ]]; then
  MODULE_DIR="$1"
  shift
fi

if [[ ! -f "${MODULE_DIR}/gradlew" ]]; then
  echo "[error] no gradle module at ${MODULE_DIR}" >&2
  echo "[hint] generate the state first: bash pipeline/generate-state.sh ${STATE_ID}" >&2
  echo "[hint] or pass the order-matcher module directory explicitly" >&2
  exit 1
fi

echo "[gate] no-GC conformance (Epsilon allocation gate) for ${STATE_ID}: ${MODULE_DIR}"
(cd "${MODULE_DIR}" && ./gradlew --no-daemon noGcTest "$@")

echo "[done] no-GC conformance gate passed: zero steady-state allocation on the hot path"
