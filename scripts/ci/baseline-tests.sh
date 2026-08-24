#!/usr/bin/env bash
# Baseline-service unit-test runner — the single source of truth for the exact gradle
# invocations, mirroring scripts/ci/engine-tests.sh. The GitHub workflow
# (.github/workflows/engine-tests.yml) and a developer at a terminal both call this, so
# "what CI runs" and "what I can run by hand" never drift.
#
#   scripts/ci/baseline-tests.sh
#
# Scope: the plain-vanilla TraderX baseline services we forked and built on top of —
# the standalone gradle projects under templates/*-specfirst. These are NOT the engine
# (covered by engine-tests.sh) and NOT the per-state composed trees (brief 04). They are
# the inherited Spring services (account, trade, position, trade-processor). Each ships
# its test sources at src/main/test/java (a pre-existing TraderX convention); the build
# files carry the sourceSets override that makes gradle actually pick them up — without
# it, `./gradlew test` silently reports NO-SOURCE and every test is inert.
#
# Run ONE AT A TIME (no cross-project gradle parallelism), matching engine-tests.sh.
#
# WHY IT ASSERTS EXECUTION AND DOES NOT TRUST THE EXIT CODE (2026-08-24). Until this date the
# gradle exit code was this script's ENTIRE verdict — it had no assertion at all, the weakest of
# the three scripts in scripts/ci/ that answer "did the suite run". Its own header above explains
# why that cannot be enough: these services keep their tests at src/main/test/java and only the
# sourceSets override makes gradle see them, so without it `test` is NO-SOURCE and green. That is
# not hypothetical here — it is this job's history: these suites had ZERO tests running in CI until
# the sourceSets fix, and nothing in this script would have said a word.
#
# It asserts by calling scripts/ci/assert-suites-executed.sh per service, so there is ONE reading
# of "did the suite run" in the repo rather than three of differing strength. The obvious version
# of this — point the sweeping gate at templates/ once — did not work and is worth recording:
# the gate discovered test sources at src/test / src/integrationTest only, found nothing whatsoever
# under templates/*-specfirst, and exited on its own "this gate checked nothing" guard. Reusing it
# here required teaching it to read the module's declared test sourceSet, which it now does.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

SERVICES=(
  account-service-specfirst
  trade-service-specfirst
  position-service-specfirst
  trade-processor-specfirst
)

# Hard requirement, never a soft skip -- see the note above.
ASSERT="${ROOT}/scripts/ci/assert-suites-executed.sh"
[[ -f "${ASSERT}" ]] || {
  echo "::error::cannot assert that these suites executed — ${ASSERT} is missing on this branch. Carry it from a branch that has it; do not drop the assertion." >&2
  exit 3
}

fail=0
for svc in "${SERVICES[@]}"; do
  dir="${ROOT}/templates/${svc}"
  [[ -x "${dir}/gradlew" ]] || { echo "[fail] no gradle wrapper at ${dir}" >&2; exit 3; }
  echo "==== ${svc} ===="
  if ! ( cd "${dir}" && ./gradlew --no-daemon --console=plain cleanTest test ); then
    echo "::error::baseline suite failed: ${svc}"
    fail=1
    continue
  fi
  if ! bash "${ASSERT}" "${dir}"; then
    fail=1
  fi
done

exit "${fail}"
