#!/usr/bin/env bash
# Composed-service test runner — the single source of truth for these gradle invocations,
# mirroring scripts/ci/engine-tests.sh and scripts/ci/baseline-tests.sh. The GitHub workflow
# and a developer at a terminal both call this, so "what CI runs" and "what I can run by hand"
# never drift.
#
#   scripts/ci/service-tests.sh
#
# Scope: the four NON-order-matcher Java service modules in the COMPOSED (generated) tree —
# the per-state effective code, not the plain-vanilla templates (those are baseline-tests.sh)
# and not the engine (engine-tests.sh). These carried real coverage that no pipeline executed
# until 2026-07-28: 107 tests on YU13/YU14, 115 on YU15.
#
# Assumes the effective tree is ALREADY RENDERED — this is meant to run as a step in the same
# job as engine-tests.sh so the ~117s render is paid once:
#   TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh <STATE_ID>
#
# Modules run ONE AT A TIME, matching the sibling scripts. Nothing here parallelises gradle.
#
# NOTE ON COUNTS: `BUILD SUCCESSFUL` does NOT mean tests ran — a module whose test sources sit
# at the non-default src/main/test/java reports NO-SOURCE and passes having executed nothing
# (this is exactly what brief 03 found). So this script ASSERTS a non-zero executed count per
# module rather than trusting the exit code.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GEN="${ROOT}/generated/code/target-generated"

MODULES=(
  execution-algo-engine
  trade-processor
  position-service
  account-service
  aeron-replication-sidecar
  trade-service
)

# trade-service was excluded here until 2026-08-02, and the reason is worth keeping because it is
# NOT the sourceSets story brief 03 fixed elsewhere. Its only test was TradeServiceApplicationTests,
# a @SpringBootTest whose context cannot start without a live NATS broker (the tradePublisher bean
# dials nats.address during bean creation) -- verified by experiment 2026-07-28, same root cause as
# TradeProcessorApplicationTests. Registering its source directory would therefore not have produced
# a passing module; it would have produced a red or hanging job.
#
# It is listed now because TradeOrderControllerTest gives it seven tests that construct the
# controller directly and stub its RestTemplate, so they need neither a broker nor a network. They
# live in the STANDARD src/test/java, which Gradle already compiles -- no build.gradle change.
#
# TradeServiceApplicationTests still sits, dormant and uncompiled, at src/main/test/java. Leave it
# there: it belongs in the Testcontainers tier, and moving it into src/test/java would re-introduce
# exactly the broker dependency this note exists to warn about. The executed-count assertion below
# is what stops that mistake being silent.

[[ -d "${GEN}" ]] || { echo "[fail] no rendered tree at ${GEN} — run pipeline/generate-state.sh first" >&2; exit 3; }

fail=0
total=0
for m in "${MODULES[@]}"; do
  dir="${GEN}/${m}"
  if [[ ! -x "${dir}/gradlew" ]]; then
    echo "::error::expected module missing from the rendered tree: ${m}"
    fail=1
    continue
  fi
  echo "==== ${m} ===="
  if ! ( cd "${dir}" && ./gradlew --no-daemon --console=plain cleanTest test ); then
    echo "::error::service suite failed: ${m}"
    fail=1
    continue
  fi

  # Guard against the silent-pass class: a green build that executed nothing.
  ran="$(find "${dir}/build/test-results/test" -name '*.xml' 2>/dev/null | wc -l | tr -d ' ')"
  if [[ "${ran}" == "0" ]]; then
    echo "::error::${m} reported success but executed NO tests (NO-SOURCE / wrong sourceSets)"
    fail=1
    continue
  fi
  echo "     ${m}: ${ran} test classes executed"
  total=$(( total + ran ))
done

echo "==== ${total} service test classes executed across ${#MODULES[@]} modules ===="
exit "${fail}"
