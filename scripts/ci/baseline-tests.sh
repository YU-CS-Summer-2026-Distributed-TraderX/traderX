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
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

SERVICES=(
  account-service-specfirst
  trade-service-specfirst
  position-service-specfirst
  trade-processor-specfirst
)

fail=0
for svc in "${SERVICES[@]}"; do
  dir="${ROOT}/templates/${svc}"
  [[ -x "${dir}/gradlew" ]] || { echo "[fail] no gradle wrapper at ${dir}" >&2; exit 3; }
  echo "==== ${svc} ===="
  if ! ( cd "${dir}" && ./gradlew --no-daemon --console=plain cleanTest test ); then
    echo "::error::baseline suite failed: ${svc}"
    fail=1
  fi
done

exit "${fail}"
