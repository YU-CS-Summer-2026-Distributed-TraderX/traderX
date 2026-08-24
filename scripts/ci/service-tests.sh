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
# (this is exactly what brief 03 found). So this script asserts execution per module rather than
# trusting the exit code — by calling scripts/ci/assert-suites-executed.sh, which compares the
# module's SOURCE test-class set against the classes that produced results, in every result tier.
# It does not carry its own reading any more; see the comment at the call site for why the reading
# it used to carry could not tell a full run from a filtered one.
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
# exactly the broker dependency this note exists to warn about. The assertion below is what stops
# that mistake being silent -- and note that it does NOT demand a result for that dormant file,
# because trade-service declares no test-sourceSet override, so gradle never compiles it. Add such
# an override and the gate starts requiring it, which is the correct moment to be told.

[[ -d "${GEN}" ]] || { echo "[fail] no rendered tree at ${GEN} — run pipeline/generate-state.sh first" >&2; exit 3; }

# Hard requirement, never a soft skip. A runner that drops its execution assertion because the
# assertion script is absent is the exact defect this file exists to prevent, one level up.
ASSERT="${ROOT}/scripts/ci/assert-suites-executed.sh"
[[ -f "${ASSERT}" ]] || {
  echo "::error::cannot assert that these suites executed — ${ASSERT} is missing on this branch. Carry it from a branch that has it; do not drop the assertion." >&2
  exit 3
}

fail=0
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

  # Guard against the silent-pass class: a green build that executed nothing -- DELEGATED, because
  # this script used to answer it with its own reading and that reading was the weakest of the
  # three in scripts/ci/. It counted result FILES in build/test-results/test and failed only on
  # zero, which meant: a suite emitting one XML reporting tests="0" passed; a filtered
  # `cleanTest test --tests …` run that deleted seven of eight classes' results passed with "1 test
  # classes executed"; and any Test task writing to a tier other than `test` was invisible.
  # Exercised 2026-08-24 rather than argued: a real reduced run of account-service (8 result XMLs
  # down to 1, BUILD SUCCESSFUL) passed that check and reported a count.
  #
  # One reading of "did the suite run" now lives in assert-suites-executed.sh and everything calls
  # it. Per MODULE rather than once over the whole tree, so a failure still names the module the
  # loop is on, and so this script stays usable on its own -- a whole-tree call would go red on
  # order-matcher for anyone who has not just run engine-tests.sh, and a gate that is unusable by
  # hand is a gate people route around.
  if ! bash "${ASSERT}" "${dir}"; then
    fail=1
    continue
  fi
done

exit "${fail}"
