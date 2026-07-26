#!/usr/bin/env bash
# Engine-test CI runner — the single source of truth for the exact gradle
# invocations. The GitHub workflow (.github/workflows/engine-tests.yml) and a
# dedicated/self-hosted runner (and a developer at a terminal) all call this,
# so "what CI runs" and "what I can run by hand" never drift.
#
#   scripts/ci/engine-tests.sh hosted      # functional suite + allocation gates (hosted-runner-safe)
#   scripts/ci/engine-tests.sh dedicated   # 3-node cluster + timing + Epsilon gates (dedicated hardware)
#
# Assumes the effective tree is already rendered:
#   TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh <STATE_ID>
#
# Suites run ONE AT A TIME on purpose: concurrent gradle trips an Aeron
# RegistrationException in ThreeMemberClusterTest and a timing miss in
# SnapshotBarrierPerformanceTest. Nothing here parallelises gradle.
set -euo pipefail

MODE="${1:?usage: engine-tests.sh <hosted|dedicated>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OM="${ROOT}/generated/code/target-generated/order-matcher"
INIT="${ROOT}/.github/ci/exclude-heavy.gradle"

[[ -x "${OM}/gradlew" ]] || { echo "[fail] no rendered tree at ${OM} — run pipeline/generate-state.sh first" >&2; exit 3; }
cd "${OM}"

G() { ./gradlew --no-daemon --console=plain "$@"; }

# Retry-once-isolated, scoped to EXACTLY the two documented flakes:
#   - the exactly-72-byte allocation-gate artifact (C2 rematerialization, root-caused 2026-07-16)
#   - the SnapshotBarrierPerformanceTest wall-clock miss
# Both are clean on isolated rerun. This is NOT a blanket retry — only the
# selectors passed here get a second attempt; the functional suite does not.
retry_isolated() {
  if G "$@"; then return 0; fi
  echo "::warning::'$*' failed once; retrying isolated (documented flake: 72-byte C2 artifact / timing budget)"
  G --rerun-tasks "$@"
}

case "${MODE}" in
  hosted)
    # Functional suite: everything except the 3-node cluster + timing class
    # (init script) and the four allocation-gate TASKS (run separately so their
    # retry is scoped). ~298 tests on YU15.
    G cleanTest test \
      -x allocationGateTest -x riskAllocationGateTest \
      -x aeronAllocationGateTest -x clusterAllocationGateTest \
      --init-script "${INIT}"
    # The four exact-zero allocation gates (each in its own forked JVM already).
    retry_isolated allocationGateTest riskAllocationGateTest aeronAllocationGateTest clusterAllocationGateTest
    ;;
  dedicated)
    # 3-node consensus test — real Aeron media drivers, heavy; hosted-hostile.
    G cleanTest test --tests 'finos.traderx.ordermatcher.cluster.ThreeMemberClusterTest'
    # Wall-clock snapshot-barrier budget (50 ms) — retry-once-isolated.
    retry_isolated test --tests 'finos.traderx.ordermatcher.cluster.SnapshotBarrierPerformanceTest'
    # The two Epsilon-GC no-GC gates — same 72-byte artifact family; retry-once-isolated.
    retry_isolated noGcTest riskNoGcTest
    ;;
  *)
    echo "[fail] unknown mode: ${MODE} (want hosted|dedicated)" >&2
    exit 2
    ;;
esac
