#!/usr/bin/env bash
# Assert that every module carrying test sources actually EXECUTED tests.
#
# WHY THIS EXISTS. Three separate defects in one week were the same shape: a check that cannot run
# reporting success.
#
#   - the generation prune step never ran for YU02-YU15 (non-numeric id -> parse null -> exit 0,
#     with a reassuring log line);
#   - four pipeline validators silently skipped every YU state and exited 0;
#   - YU01's three allocation gates -- the gates that test the state's HEADLINE no-GC claim -- had
#     never once compiled, so they had never once run, while the suite reported green.
#
# A green build is not evidence a suite ran. `test` is UP-TO-DATE with nothing to do, NO-SOURCE
# because sourceSets point at a directory the tree does not have, or simply never invoked because
# no list mentions the module. In all three the exit code is 0.
#
# The rule this enforces: A TEST THAT CANNOT COMPILE IS NOT A TEST THAT IS PASSING. If a module
# ships test sources, it has to produce executed tests, or the build fails and says which module.
#
# WHY IT SWEEPS THE TREE INSTEAD OF TAKING A MODULE LIST. service-tests.sh and baseline-tests.sh
# already assert this -- each for its OWN hardcoded MODULES array. That cannot catch the failure
# that actually bit us, which is a suite NO list mentions (engine-tests.sh had no such assert at
# all, which is exactly where YU01's gates went missing). A list can only check what someone
# remembered to add; this walks what is on disk.
#
# Run it AFTER the suites, in the same workspace -- it reads their JUnit XML, it does not run them.
#
#   bash scripts/ci/assert-suites-executed.sh [rendered-tree-root]
#
# Exit: 0 all good | 1 a module with test sources executed nothing | 3 bad usage / no tree.
set -uo pipefail

# --selftest builds three throwaway module trees and asserts this gate gives the right verdict on
# each. A gate that cannot be falsified is precisely the thing this gate exists to catch, so it
# ships with its own falsification. The middle case is the one worth having: a result XML that
# EXISTS but reports tests="0" — counting result FILES would call that a pass.
if [[ "${1:-}" == "--selftest" ]]; then
  tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"' EXIT
  for k in good bad notests; do mkdir -p "${tmp}/${k}/mod/build/test-results/test"; : > "${tmp}/${k}/mod/build.gradle"; done
  for k in good bad; do mkdir -p "${tmp}/${k}/mod/src/test/java"; echo 'class T {}' > "${tmp}/${k}/mod/src/test/java/T.java"; done
  printf '<testsuite name="T" tests="3" failures="0" errors="0" skipped="0"/>' > "${tmp}/good/mod/build/test-results/test/TEST-T.xml"
  printf '<testsuite name="T" tests="0" failures="0" errors="0" skipped="0"/>' > "${tmp}/bad/mod/build/test-results/test/TEST-T.xml"
  rc=0
  for case in "good 0" "bad 1" "notests 1"; do
    set -- ${case}
    bash "$0" "${tmp}/$1" >/dev/null 2>&1; got=$?
    if [[ "${got}" -ne "$2" ]]; then echo "[fail] selftest ${1}: expected exit $2, got ${got}"; rc=1
    else echo "  ok   selftest ${1}: exit ${got}"; fi
  done
  [[ ${rc} -eq 0 ]] && echo "selftest: 3 checks passed."
  exit "${rc}"
fi

ROOT="${1:-generated/code/target-generated}"
[[ -d "${ROOT}" ]] || { echo "[fail] no rendered tree at ${ROOT} — run pipeline/generate-state.sh first" >&2; exit 3; }

# Modules whose test sources are ALL container-backed and therefore legitimately absent from a leg
# that does not run the integration tier. Keep this empty unless a real case appears, and name the
# reason when you add one — an entry here is a hole in the gate, so it should be a considered one
# rather than the first way to make a red build green.
EXEMPT=()

is_exempt() {
  local m="$1" e
  for e in ${EXEMPT[@]+"${EXEMPT[@]}"}; do [[ "${m}" == "${e}" ]] && return 0; done
  return 1
}

fail=0
checked=0
total=0

# A module is any directory with a build.gradle that also carries at least one test source. Both
# halves matter: build.gradle alone is a module with no tests (fine), test sources alone are not
# a gradle module (also fine) -- the failure case is a module that HAS tests and ran none.
while IFS= read -r gradle_file; do
  dir="$(dirname "${gradle_file}")"
  module="${dir#"${ROOT}"/}"
  [[ "${module}" == "${dir}" ]] && module="$(basename "${dir}")"

  # Test sources present? (java or kotlin, unit or integration source sets).
  # Written the long way on purpose: `find a b -name x -o -name y` binds -o loosely AND errors on a
  # missing path, which silently made this gate detect zero modules and then pass. Check each
  # directory exists first, parenthesise the alternation, and stop at the first hit.
  have_tests=0
  for sub in src/test src/integrationTest; do
    [[ -d "${dir}/${sub}" ]] || continue
    if find "${dir}/${sub}" -type f \( -name '*.java' -o -name '*.kt' \) -print -quit 2>/dev/null | grep -q .; then
      have_tests=1
      break
    fi
  done
  [[ "${have_tests}" -eq 1 ]] || continue

  checked=$((checked + 1))

  if is_exempt "${module}"; then
    echo "     ${module}: exempt (integration-only)"
    continue
  fi

  # Sum the `tests` attribute across every result tier this module produced. Counting FILES is not
  # enough: a suite can emit a result XML reporting zero tests, which is the same lie in a
  # different shape.
  ran="$(python3 - "${dir}" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
total = 0
for tier in ('test', 'integrationTest'):
    for path in glob.glob(f"{sys.argv[1]}/build/test-results/{tier}/*.xml"):
        try:
            total += int(ET.parse(path).getroot().get('tests', 0))
        except Exception:
            pass   # an unparseable report is not a passing test; it counts as zero
print(total)
PY
)"

  if [[ "${ran}" -eq 0 ]]; then
    echo "::error::${module} has test sources but EXECUTED NO TESTS — the suite did not run (NO-SOURCE sourceSets, uncompilable tests, or no list invokes this module). A green build here is not evidence."
    fail=1
    continue
  fi

  total=$((total + ran))
  echo "     ${module}: ${ran} tests executed"
done < <(find "${ROOT}" -name build.gradle -not -path '*/build/*' 2>/dev/null | sort)

if [[ "${checked}" -eq 0 ]]; then
  # The gate finding nothing to check is itself the failure mode it exists to catch.
  echo "::error::no modules with test sources found under ${ROOT} — this gate checked nothing and would have passed silently"
  exit 1
fi

echo "==== ${total} tests executed across ${checked} module(s) with test sources ===="
exit "${fail}"
