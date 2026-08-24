#!/usr/bin/env bash
# Assert that every module carrying test sources actually EXECUTED tests — and executed ALL of
# them, not a filtered remnant.
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
# WHY IT ALSO CHECKS THE CLASS SET, NOT JUST A NON-ZERO COUNT (2026-08-24). Until this date the
# gate summed the `tests` attribute and failed only when the total was ZERO. That distinguishes
# "ran nothing" from "ran something" and cannot distinguish "ran everything" from "ran three of
# four hundred. It reads a DIRECTORY, and any filtered run rewrites that directory: a throwaway
# `cleanTest test --tests '*Dump*'` deletes the module's existing XMLs and leaves only the probe's,
# after which this gate summed the survivors, got a small non-zero number, and PASSED — reporting a
# module's suite as executed when almost all of it had been deleted moments earlier. The gate did
# not go quiet; it answered confidently with a number wrong in the direction of confidence, and
# suite counts are reported onward from here as evidence. Filed as
# issues/open/the-suite-gate-cannot-tell-reduced-from-complete.md; see .claude/skills/vacuous-pass-audit.
#
# So the assertion is now: EVERY test class in a module's sources produced a result. A count is
# maintenance (it moves on every added @Test method and a pinned one is stale within a week); the
# class SET is derived from the tree on both sides, so adding or deleting a test class costs this
# file nothing. Only a class that deliberately does not run costs an entry, below, with its reason.
#
# AND WHY IT READS EVERY RESULT TIER. The same 2026-08-24 pass found this, and it is worth stating
# plainly because of what it cost: THIS GATE COULD NOT SEE THE ALLOCATION GATES. order-matcher runs
# its four isolated allocation gates as their own Test tasks, so their results land in
# build/test-results/allocationGateTest/ and siblings -- and the glob here only ever looked at
# {test,integrationTest}. The one gate whose founding story is "YU01's allocation gates silently
# never ran" was structurally blind to the allocation gates. It now globs build/test-results/*,
# so AllocationGateTest, AeronTransportAllocationGateTest and ClusterServiceAllocationGateTest are
# required like anything else. Do not narrow that glob back to a fixed pair of tiers.
#
# WHY IT SWEEPS THE TREE INSTEAD OF TAKING A MODULE LIST. service-tests.sh and baseline-tests.sh
# already assert this -- each for its OWN hardcoded MODULES array. That cannot catch the failure
# that actually bit us, which is a suite NO list mentions (engine-tests.sh had no such assert at
# all, which is exactly where YU01's gates went missing). A list can only check what someone
# remembered to add; this walks what is on disk.
#
# WHAT IT STILL CANNOT TELL APART, stated so nobody has to rediscover it. It compares a class SET,
# so it cannot see a suite that lost METHODS: a `--tests 'Foo.someMethod'` run, or a class whose
# @Test methods were deleted, still produces that class's XML and passes here. And it has no
# reference clock, so a STALE tier passes: `cleanTest` cleans only the `test` task's output, so an
# allocationGateTest directory left by an earlier build satisfies the class requirement without
# that gate having run in this one. Issue direction 1 (freshness) is the fix for both and is NOT
# implemented -- it needs a trustworthy "when did the run being asserted about start", which this
# script is not given. Whoever adds it should pass that timestamp in rather than infer it.
#
# Run it AFTER the suites, in the same workspace -- it reads their JUnit XML, it does not run them.
#
#   bash scripts/ci/assert-suites-executed.sh [rendered-tree-root]
#
# Exit: 0 all good | 1 a module executed nothing, or executed only part of its classes, or an
#       exemption below is stale | 3 bad usage / no tree.
set -uo pipefail

# Test classes that legitimately produce no result XML in a normal run.
#
# An entry here is a HOLE in the gate, so it must name the mechanism that excludes the class and
# where that mechanism lives -- "the build is red" is not a reason. Format is `ClassName:reason`.
#
# A STALE entry fails the gate: if no module in the tree has a test source of that name, the
# exemption is pointing at nothing and is removed rather than carried. That is what stops a hole
# outliving the reason it was opened for.
#
# THAT STALE-ENTRY CHECK IS NOT CEREMONY -- DO NOT DELETE IT AS TIDY-UP. This list is a SILENCER:
# every entry buys a class the right to produce no result and not be noticed. vacuous-pass-audit
# rule 5 is that a loud guard destroyed at its call site is less visible than a guard nobody wrote,
# and an exemption list is that call site. The stale-entry check is the guard ON the silencer -- it
# is the only thing that makes the list self-cleaning, and without it a class deleted years ago
# keeps a hole open in this gate forever. Same reasoning for printing every honoured exemption on a
# green run: a declared opt-out is not coverage, and a green run that does not say so overstates
# itself.
#
# NOT listed here, on purpose:
#   - the four isolated allocation gates (AllocationGateTest, AeronTransportAllocationGateTest,
#     ClusterServiceAllocationGateTest). They are excluded from the `test` task but run as their
#     own Test tasks, so their results land in build/test-results/<taskName>/. This gate reads
#     EVERY tier, not just test/integrationTest, so they are required like anything else -- which
#     matters here more than anywhere, since a silently-missing allocation gate is this file's
#     own origin story.
#   - @Tag("integration") classes. Handled below as a CONDITIONAL exemption, not a standing one.
EXEMPT_CLASSES=(
  "ThreeMemberClusterTest:hosted-hostile (three real Aeron media drivers) -- excluded by .github/ci/exclude-heavy.gradle; runs on the dedicated leg via engine-tests.sh dedicated"
  "SnapshotBarrierPerformanceTest:wall-clock timing budget, hosted-hostile -- excluded by .github/ci/exclude-heavy.gradle; runs on the dedicated leg via engine-tests.sh dedicated"
)

# --selftest builds throwaway module trees and asserts this gate gives the right verdict on each.
# A gate that cannot be falsified is precisely the thing this gate exists to catch, so it ships
# with its own falsification.
#
# `notests` is the case worth having for the zero-check: a result XML that EXISTS but reports
# tests="0" — counting result FILES would call that a pass.
#
# `reduced` is the case worth having for the class-set check, and it is the defect this gate was
# extended for: two test classes in source, one result XML. The pre-2026-08-24 gate PASSED this.
#
# `integ-skipped` / `integ-required` are a two-arm harness rather than a single detonator: same
# subject, same readings, only the presence of an integrationTest tier differing. Both readings
# have to flip, or the conditional exemption is just an unconditional one. DO NOT COLLAPSE THIS TO
# ONE ARM -- the `integ-skipped` arm alone is satisfied by an exemption that always fires, which is
# the failure this pair exists to make impossible.
#
# Every case except `stale-exempt` plants source files for the EXEMPT_CLASSES entries and NO
# results for them, so each case also asserts that a standing exemption is honoured. `stale-exempt`
# is the same tree with those sources removed, which is what a deleted-but-still-exempted class
# looks like: it must go red.
if [[ "${1:-}" == "--selftest" ]]; then
  tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"' EXIT
  for k in good bad notests reduced integ-skipped integ-required stale-exempt; do
    mkdir -p "${tmp}/${k}/mod/build/test-results/test" "${tmp}/${k}/mod/src/test/java"
    : > "${tmp}/${k}/mod/build.gradle"
    [[ "${k}" == "stale-exempt" ]] && continue
    for e in ${EXEMPT_CLASSES[@]+"${EXEMPT_CLASSES[@]}"}; do
      echo "class ${e%%:*} {}" > "${tmp}/${k}/mod/src/test/java/${e%%:*}.java"
    done
  done
  # good/bad/notests/stale-exempt: one ordinary class. bad reports tests="0"; notests reports
  # nothing at all — counting result FILES would call bad a pass.
  for k in good bad notests stale-exempt; do echo 'class TTest {}' > "${tmp}/${k}/mod/src/test/java/TTest.java"; done
  for k in good stale-exempt; do
    printf '<testsuite name="p.TTest" tests="3" failures="0" errors="0" skipped="0"/>' > "${tmp}/${k}/mod/build/test-results/test/TEST-p.TTest.xml"
  done
  printf '<testsuite name="p.TTest" tests="0" failures="0" errors="0" skipped="0"/>' > "${tmp}/bad/mod/build/test-results/test/TEST-p.TTest.xml"
  # reduced: ATest and BTest in source, only ATest ran. Non-zero total, so the OLD gate passed this.
  for k in reduced integ-skipped integ-required; do
    echo 'class ATest {}' > "${tmp}/${k}/mod/src/test/java/ATest.java"
    printf '<testsuite name="p.ATest" tests="5" failures="0" errors="0" skipped="0"/>' > "${tmp}/${k}/mod/build/test-results/test/TEST-p.ATest.xml"
  done
  echo 'class BTest {}' > "${tmp}/reduced/mod/src/test/java/BTest.java"
  # integ-skipped: BTest is @Tag("integration") and no integrationTest tier exists -> exempt.
  # integ-required: same class, but the tier DID run (for some other class) -> no longer exempt.
  for k in integ-skipped integ-required; do
    printf '@Tag("integration")\nclass BTest {}\n' > "${tmp}/${k}/mod/src/test/java/BTest.java"
  done
  mkdir -p "${tmp}/integ-required/mod/build/test-results/integrationTest"
  printf '<testsuite name="p.CTest" tests="1" failures="0" errors="0" skipped="0"/>' \
    > "${tmp}/integ-required/mod/build/test-results/integrationTest/TEST-p.CTest.xml"
  # CTest has no source file; that is fine — the gate asserts source ⊆ executed, not the converse.
  rc=0
  for case in "good 0" "bad 1" "notests 1" "reduced 1" "integ-skipped 0" "integ-required 1" "stale-exempt 1"; do
    set -- ${case}
    bash "$0" "${tmp}/$1" >/dev/null 2>&1; got=$?
    if [[ "${got}" -ne "$2" ]]; then echo "[fail] selftest ${1}: expected exit $2, got ${got}"; rc=1
    else echo "  ok   selftest ${1}: exit ${got}"; fi
  done
  [[ ${rc} -eq 0 ]] && echo "selftest: 7 checks passed."
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

# `Name:reason` -> the bare names, for the python below and the stale-entry sweep.
exempt_names=""
for e in ${EXEMPT_CLASSES[@]+"${EXEMPT_CLASSES[@]}"}; do
  exempt_names="${exempt_names}${exempt_names:+,}${e%%:*}"
done

fail=0
checked=0
total=0
all_source_classes=""

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

  # One python pass per module. It reports, pipe-separated:
  #   total tests | per-tier summary | classes that ran | source classes with NO result | all source classes
  #
  # Counting FILES is not enough (a suite can emit a result XML reporting zero tests) and counting
  # TESTS is not enough (a filtered run leaves a small non-zero total) — so it does both.
  report="$(python3 - "${dir}" "${exempt_names}" <<'PY'
import glob, os, re, sys, xml.etree.ElementTree as ET

mod_dir = sys.argv[1]
exempt = {c for c in sys.argv[2].split(',') if c}

# JUnit/Gradle name a result file after the class, so the comparison is class-name to class-name.
# Restrict the source side to the discovery conventions the runners actually use — a helper or an
# abstract base in src/test is not a class Gradle will ever emit a report for.
TEST_FILE = re.compile(r'^(.*(?:Test|Tests|IT))\.(?:java|kt)$')

sources = {}
for sub in ('src/test', 'src/integrationTest'):
    for root, _, files in os.walk(os.path.join(mod_dir, sub)):
        for f in files:
            m = TEST_FILE.match(f)
            if m:
                sources[m.group(1)] = os.path.join(root, f)

# EVERY result tier, not just test/integrationTest. order-matcher runs its allocation gates as
# their own Test tasks (allocationGateTest, aeronAllocationGateTest, ...), and each writes to
# build/test-results/<taskName>/. Globbing only the two default tiers made those gates invisible
# to the one gate whose job is to notice a missing gate.
executed, tiers, total = set(), [], 0
for tier_dir in sorted(glob.glob(os.path.join(mod_dir, 'build/test-results/*'))):
    if not os.path.isdir(tier_dir):
        continue
    n = t = 0
    for path in glob.glob(os.path.join(tier_dir, '*.xml')):
        try:
            root = ET.parse(path).getroot()
        except Exception:
            continue          # an unparseable report is not a passing test; it counts as zero
        t += int(root.get('tests', 0) or 0)
        n += 1
        # Prefer the recorded suite name over the filename; strip package and any @Nested suffix.
        name = root.get('name') or os.path.basename(path)[5:-4]
        executed.add(name.rsplit('.', 1)[-1].split('$', 1)[0])
    if n:
        tiers.append(f"{os.path.basename(tier_dir)} {t}")
        total += t

# CONDITIONAL exemption. A @Tag("integration") class runs in the `integrationTest` task, which CI
# does not invoke (scripts/ci/service-tests.sh runs `cleanTest test` only). So it is legitimately
# absent from a unit-only leg — and becomes REQUIRED the moment that tier is invoked at all. This
# is deliberately not a standing entry in EXEMPT_CLASSES: a new integration test then costs the
# gate nothing, and a leg that DOES run the tier gets the full assertion for free.
tier_names = {t.split(' ')[0] for t in tiers}
if 'integrationTest' not in tier_names:
    for cls, path in list(sources.items()):
        try:
            with open(path, encoding='utf-8', errors='replace') as fh:
                body = fh.read()
        except OSError:
            continue
        if '@Tag("integration")' in body:
            del sources[cls]

missing = sorted(set(sources) - executed - exempt)
print('|'.join([str(total), ', '.join(tiers) or 'none',
                str(len(executed)), ','.join(missing), ','.join(sorted(sources))]))
PY
)"

  IFS='|' read -r ran tier_summary nclasses missing src_classes <<<"${report}"

  # Shape-test the reading. If python died, `ran` is empty — and bash -eq evaluates "" as 0, so an
  # unread module would otherwise be indistinguishable from one that ran nothing.
  if [[ ! "${ran}" =~ ^[0-9]+$ ]]; then
    echo "::error::${module}: could not read test results (the gate's own reader failed) — this is not a pass"
    fail=1
    continue
  fi

  all_source_classes="${all_source_classes}${all_source_classes:+,}${src_classes}"

  if [[ "${ran}" -eq 0 ]]; then
    echo "::error::${module} has test sources but EXECUTED NO TESTS — the suite did not run (NO-SOURCE sourceSets, uncompilable tests, or no list invokes this module). A green build here is not evidence."
    fail=1
    continue
  fi

  if [[ -n "${missing}" ]]; then
    echo "::error::${module} executed a REDUCED suite: ${ran} tests from ${nclasses} class(es), but these test classes produced no result at all — ${missing//,/ }"
    echo "::error::${module}: a --tests-filtered or single-class run deletes the other classes' XML, and the count above is then wrong in the direction of confidence. Re-run the full suite before reading or reporting counts. If a class is deliberately never run, add it to EXEMPT_CLASSES in $(basename "$0") with the mechanism that excludes it."
    fail=1
    continue
  fi

  total=$((total + ran))
  echo "     ${module}: ${ran} tests / ${nclasses} classes  (${tier_summary})"
done < <(find "${ROOT}" -name build.gradle -not -path '*/build/*' 2>/dev/null | sort)

if [[ "${checked}" -eq 0 ]]; then
  # The gate finding nothing to check is itself the failure mode it exists to catch.
  echo "::error::no modules with test sources found under ${ROOT} — this gate checked nothing and would have passed silently"
  exit 1
fi

# A standing exemption that names a class the tree no longer has is a hole with nothing behind it.
# Fail on it rather than carrying it: this is the only thing that makes the list self-cleaning.
for e in ${EXEMPT_CLASSES[@]+"${EXEMPT_CLASSES[@]}"}; do
  name="${e%%:*}"
  case ",${all_source_classes}," in
    *",${name},"*) echo "     exempt: ${name} — ${e#*:}" ;;
    *) echo "::error::stale exemption: EXEMPT_CLASSES names ${name}, which has no test source anywhere under ${ROOT}. Remove it — an exemption nobody can point at a file for is a hole that outlived its reason."
       fail=1 ;;
  esac
done

# The total is the number lanes copy into reports, so it must never be readable as a clean result
# on a run that went red — the whole point of this file is a count that overstates itself.
if [[ "${fail}" -ne 0 ]]; then
  echo "==== INCOMPLETE: ${total} tests across the module(s) that passed — do NOT report this as a suite count ===="
else
  echo "==== ${total} tests executed across ${checked} module(s) with test sources ===="
fi
exit "${fail}"
