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
# AND WHY IT READS EVERY TEST SOURCE ROOT, NOT A FIXED PAIR (2026-08-24, second pass). The tier
# glob above was half the fix. The SOURCE side had the same shape: it looked in src/test and
# src/integrationTest only, while gradle is told where the test sources are by the module's own
# `sourceSets { test { java.srcDirs = … } }`. On the rendered tree that made this gate silently
# blind to a whole module -- account-service has no src/test AT ALL, all nine of its test classes
# live at src/main/test/java, and this gate did not fail it, exempt it, or mention it. It was
# absent from the output entirely, which reads exactly like "nothing to check here". Same reason
# it could not be pointed at templates/*-specfirst, where every baseline service uses that layout.
#
# So the source roots are now DERIVED from the module's build.gradle rather than assumed. That
# distinction is load-bearing in both directions: composed trade-service also carries a file at
# src/main/test/java, but declares NO override, so gradle never compiles it and requiring a result
# for it would be wrong. Deriving gets account-service and trade-service right at once; a second
# hardcoded directory would have got one right and the other wrong.
#
# WHY IT SWEEPS THE TREE INSTEAD OF TAKING A MODULE LIST. service-tests.sh and baseline-tests.sh
# used to assert this -- each for its OWN hardcoded MODULES array, and each far more weakly (a
# result-FILE count that could not see a reduced run, and nothing at all, respectively). That
# cannot catch the failure that actually bit us, which is a suite NO list mentions (engine-tests.sh
# had no such assert at all, which is exactly where YU01's gates went missing). A list can only
# check what someone remembered to add; this walks what is on disk.
#
# Both of those scripts now CALL this one instead, a module at a time, so there is one reading of
# "did the suite run" in the repo rather than three of differing strength that drift apart. That is
# why the root may be a single module directory, and why the exemption entries below are scoped to
# a module -- see the note on EXEMPT_CLASSES.
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
# where that mechanism lives -- "the build is red" is not a reason. Format is
# `module/ClassName:reason`.
#
# THE MODULE PREFIX IS LOAD-BEARING, NOT DECORATION (2026-08-24). This gate is now called with a
# SINGLE MODULE as its root (scripts/ci/service-tests.sh, scripts/ci/baseline-tests.sh) and with
# templates/ as its root, not only with the whole rendered tree. An unscoped exemption is checked
# for staleness against whatever root it was handed, so under any narrower root every entry here
# reads as stale and the gate goes red for a reason that has nothing to do with the suite. Scoping
# each entry to the module that owns it makes the check root-independent AND stricter: the class
# must exist in THAT module, not merely somewhere under the root. Entries whose module is not
# present under the current root are counted and reported as not applied, rather than passed over
# in silence -- an exemption that is silently inert is the thing this format exists to prevent.
#
# It also makes the entries honest about trees that differ. `trade-processor/…ApplicationTests`
# below is excluded in the COMPOSED trade-processor and is NOT excluded in
# templates/trade-processor-specfirst, where it runs and passes -- one unscoped entry would have
# opened a hole in the template suite to cover the composed one.
#
# A STALE entry fails the gate: if the named module is present and has no test source of that name,
# the exemption is pointing at nothing and is removed rather than carried. That is what stops a
# hole outliving the reason it was opened for.
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
  "order-matcher/ThreeMemberClusterTest:hosted-hostile (three real Aeron media drivers) -- excluded by .github/ci/exclude-heavy.gradle; runs on the dedicated leg via engine-tests.sh dedicated"
  "order-matcher/SnapshotBarrierPerformanceTest:wall-clock timing budget, hosted-hostile -- excluded by .github/ci/exclude-heavy.gradle; runs on the dedicated leg via engine-tests.sh dedicated"
  "trade-processor/TradeProcessorApplicationTests:excluded by \`filter { excludeTestsMatching … }\` in the COMPOSED trade-processor build.gradle test task -- a @SpringBootTest whose context dials nats.address during bean creation, so it cannot start on a broker-free leg; the composed context load is covered by TradeProcessorContextIT on the integration-trade-processor job. Became visible to this gate on 2026-08-24 when source discovery started honouring the module's redirected test sourceSet."
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
  CASES="good bad notests reduced integ-skipped integ-required stale-exempt srcdirs-redirected srcdirs-reduced srcdirs-dormant"
  for k in ${CASES}; do
    mkdir -p "${tmp}/${k}/mod/build/test-results/test" "${tmp}/${k}/mod/src/test/java"
    : > "${tmp}/${k}/mod/build.gradle"
    # Each standing exemption now names a MODULE, so its subject has to be planted in a module of
    # that name — and that module needs a class that DOES run, or every case would go red on
    # "executed no tests" before the exemption was ever consulted.
    for e in ${EXEMPT_CLASSES[@]+"${EXEMPT_CLASSES[@]}"}; do
      em="${e%%/*}"; ec="${e#*/}"; ec="${ec%%:*}"
      mkdir -p "${tmp}/${k}/${em}/build/test-results/test" "${tmp}/${k}/${em}/src/test/java"
      : > "${tmp}/${k}/${em}/build.gradle"
      echo 'class KTest {}' > "${tmp}/${k}/${em}/src/test/java/KTest.java"
      printf '<testsuite name="p.KTest" tests="1" failures="0" errors="0" skipped="0"/>' \
        > "${tmp}/${k}/${em}/build/test-results/test/TEST-p.KTest.xml"
      # stale-exempt is the same tree with the exempted SOURCES removed — a deleted-but-still-
      # exempted class — so its module still runs KTest and the only defect is the dead entry.
      [[ "${k}" == "stale-exempt" ]] && continue
      echo "class ${ec} {}" > "${tmp}/${k}/${em}/src/test/java/${ec}.java"
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

  # The three srcdirs cases are the source-side counterpart of the tier fix, and they are a THREE-
  # arm harness for the same reason integ-skipped/integ-required is a two-arm one: the reading has
  # to change with the declaration, or "derived" is just a second hardcoded directory.
  #   srcdirs-redirected — override present, its class ran            -> green (discovery works)
  #   srcdirs-reduced    — same override, one of its two classes ran  -> red   (and the ONLY way to
  #                        see it is through the override; without it the module reads as testless)
  #   srcdirs-dormant    — a file under src/main/test with NO override, so gradle never compiles
  #                        it -> green. This is composed trade-service, and it is why the roots are
  #                        read from build.gradle instead of a second hardcoded path.
  for k in srcdirs-redirected srcdirs-reduced; do
    mkdir -p "${tmp}/${k}/mod/src/main/test/java"
    printf 'sourceSets {\n  test {\n    java.srcDirs = ["src/main/test/java"]\n  }\n}\n' > "${tmp}/${k}/mod/build.gradle"
    echo 'class RTest {}' > "${tmp}/${k}/mod/src/main/test/java/RTest.java"
    printf '<testsuite name="p.RTest" tests="4" failures="0" errors="0" skipped="0"/>' \
      > "${tmp}/${k}/mod/build/test-results/test/TEST-p.RTest.xml"
  done
  echo 'class STest {}' > "${tmp}/srcdirs-reduced/mod/src/main/test/java/STest.java"
  mkdir -p "${tmp}/srcdirs-dormant/mod/src/main/test/java"
  echo 'class DTest {}' > "${tmp}/srcdirs-dormant/mod/src/main/test/java/DTest.java"
  echo 'class TTest {}' > "${tmp}/srcdirs-dormant/mod/src/test/java/TTest.java"
  printf '<testsuite name="p.TTest" tests="2" failures="0" errors="0" skipped="0"/>' \
    > "${tmp}/srcdirs-dormant/mod/build/test-results/test/TEST-p.TTest.xml"

  rc=0
  for case in "good 0" "bad 1" "notests 1" "reduced 1" "integ-skipped 0" "integ-required 1" "stale-exempt 1" \
              "srcdirs-redirected 0" "srcdirs-reduced 1" "srcdirs-dormant 0"; do
    set -- ${case}
    bash "$0" "${tmp}/$1" >/dev/null 2>&1; got=$?
    if [[ "${got}" -ne "$2" ]]; then echo "[fail] selftest ${1}: expected exit $2, got ${got}"; rc=1
    else echo "  ok   selftest ${1}: exit ${got}"; fi
  done
  [[ ${rc} -eq 0 ]] && echo "selftest: 10 checks passed."
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

# `module/Name:reason` -> the bare class names that apply to ONE module, for the python below.
# An entry for another module is not passed in, so it cannot silence a class of the same name here.
exempt_names_for() {
  local m="$1" e scoped names=""
  for e in ${EXEMPT_CLASSES[@]+"${EXEMPT_CLASSES[@]}"}; do
    scoped="${e%%:*}"
    [[ "${scoped%%/*}" == "${m}" ]] || continue
    names="${names}${names:+,}${scoped#*/}"
  done
  printf '%s' "${names}"
}

fail=0
checked=0
total=0
all_source_classes=""
walked_modules=""

# A module is any directory with a build.gradle that also carries at least one test source. Both
# halves matter: build.gradle alone is a module with no tests (fine), test sources alone are not
# a gradle module (also fine) -- the failure case is a module that HAS tests and ran none.
while IFS= read -r gradle_file; do
  dir="$(dirname "${gradle_file}")"
  module="${dir#"${ROOT}"/}"
  [[ "${module}" == "${dir}" ]] && module="$(basename "${dir}")"

  # "Does this module have test sources?" is NOT decided here any more. It used to be a bash probe
  # over a hardcoded src/test + src/integrationTest, duplicating the same list the python below
  # carried -- and when that list turned out to be wrong (a module whose sourceSet is redirected to
  # src/main/test/java), it was wrong in two places, and the bash copy was the one that made the
  # module vanish before python ever saw it. There is now one implementation, in the reader, and a
  # module with no test sources is one the reader reports no source classes for.

  # One python pass per module. It reports, pipe-separated:
  #   total tests | per-tier summary | classes that ran | source classes with NO result | all source classes
  #
  # Counting FILES is not enough (a suite can emit a result XML reporting zero tests) and counting
  # TESTS is not enough (a filtered run leaves a small non-zero total) — so it does both.
  # Computed into a variable rather than substituted inline: bash 3.2 mis-parses a nested $( )
  # inside a command substitution that also carries a heredoc, and the failure is a syntax error
  # at end of file, nowhere near the line responsible.
  module_exempt="$(exempt_names_for "${module}")"

  report="$(python3 - "${dir}" "${module_exempt}" <<'PY'
import glob, os, re, sys, xml.etree.ElementTree as ET

mod_dir = sys.argv[1]
exempt = {c for c in sys.argv[2].split(',') if c}

# JUnit/Gradle name a result file after the class, so the comparison is class-name to class-name.
# Restrict the source side to the discovery conventions the runners actually use — a helper or an
# abstract base in src/test is not a class Gradle will ever emit a report for.
TEST_FILE = re.compile(r'^(.*(?:Test|Tests|IT))\.(?:java|kt)$')

# WHERE THE TEST SOURCES ARE IS THE MODULE'S DECISION, NOT THIS SCRIPT'S. The two conventional
# roots are the default, but a module that overrides its test sourceSet has moved them, and
# assuming otherwise made this gate skip account-service entirely (no src/test at all) and made it
# unusable against templates/*-specfirst. Read the declaration instead of guessing it.
#
# DO NOT "SIMPLIFY" THIS TO A THIRD HARDCODED DIRECTORY. Adding src/main/test to the default list
# looks like the same fix and is not: composed trade-service carries
# src/main/test/java/.../TradeServiceApplicationTests.java and declares NO srcDirs override, so
# gradle never compiles that class and it never runs -- deliberately, because its context cannot
# start without a live NATS broker (see scripts/ci/service-tests.sh). A blanket walk of
# src/main/test would demand a result for it and fail the module, and it would be wrong in the
# direction that looks like thoroughness. Deriving from the build.gradle of the module itself gets
# account-service (override, must be required) and trade-service (no override, must not be) right
# with ONE rule. Two hardcoded directories get one of them right.
#
# Only srcDirs entries with a `test` path SEGMENT count, so `src/main/java` cannot become a test
# root while `src/main/test/java` can. And this is deliberately additive to the defaults rather
# than a replacement: a module that declares one root and also carries the conventional one should
# have both read.
SRC_DIRS = re.compile(r'srcDirs\s*=\s*\[([^\]]*)\]')
# The two quote characters are spelled with chr() rather than written out. This whole block lives
# in a heredoc nested inside $( ), and bash 3.2 scans heredoc bodies for the closing paren: an odd
# number of quote characters in here is a syntax error reported at end of file, two hundred lines
# from the line that caused it. Cost an hour once; do not "tidy" these back to literals.
QUOTE_CHARS = chr(39) + chr(34)

roots = ['src/test', 'src/integrationTest']
try:
    with open(os.path.join(mod_dir, 'build.gradle'), encoding='utf-8', errors='replace') as fh:
        gradle = fh.read()
except OSError:
    gradle = ''
for decl in SRC_DIRS.finditer(gradle):
    for raw in decl.group(1).split(','):
        path = raw.strip().strip(QUOTE_CHARS)
        parts = path.strip('/').split('/')
        if ('test' in parts or 'integrationTest' in parts) and path not in roots:
            roots.append(path)

sources = {}
for sub in roots:
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

  # No test sources at all: a gradle module that ships none is fine, and is NOT counted as checked.
  # This is the only place that decision is made now -- see the note at the top of the loop.
  [[ -n "${src_classes}" ]] || continue

  # Accumulated MODULE-QUALIFIED, and accumulated here rather than after the exempt skip below, so
  # the stale-exemption sweep sees every module the walk saw. Qualifying matters: an exemption for
  # order-matcher must not be kept alive by a same-named class in some other module.
  for c in ${src_classes//,/ }; do
    all_source_classes="${all_source_classes}${all_source_classes:+,}${module}/${c}"
  done
  walked_modules="${walked_modules}${walked_modules:+,}${module}"

  checked=$((checked + 1))

  if is_exempt "${module}"; then
    echo "     ${module}: exempt (integration-only)"
    continue
  fi

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
#
# Scoped to the module the entry names, so this stays a real check under every root the gate is
# now called with. Three outcomes, all of them stated out loud:
#   - module walked, class present -> honoured, and printed (a declared opt-out is not coverage)
#   - module walked, class ABSENT  -> stale, and red
#   - module not under this root   -> not applicable; counted and reported, because an exemption
#     that is silently inert is indistinguishable from one that is silently wrong. Counted rather
#     than listed one per line only because the per-module callers would otherwise print three
#     lines of it per module, and a guard nobody reads is a guard nobody has. Staleness of those
#     entries is still decided, on every whole-tree run.
inapplicable=0
for e in ${EXEMPT_CLASSES[@]+"${EXEMPT_CLASSES[@]}"}; do
  scoped="${e%%:*}"
  case ",${all_source_classes}," in
    *",${scoped},"*)
      echo "     exempt: ${scoped} — ${e#*:}"
      continue ;;
  esac
  case ",${walked_modules}," in
    *",${scoped%%/*},"*)
      echo "::error::stale exemption: EXEMPT_CLASSES names ${scoped}, but module ${scoped%%/*} under ${ROOT} has no test source named ${scoped#*/}. Remove it — an exemption nobody can point at a file for is a hole that outlived its reason."
      fail=1 ;;
    *)
      inapplicable=$((inapplicable + 1)) ;;
  esac
done
if [[ "${inapplicable}" -gt 0 ]]; then
  echo "     ${inapplicable} standing exemption(s) name a module not under ${ROOT} — not applied here, and checked for staleness on whole-tree runs"
fi

# The total is the number lanes copy into reports, so it must never be readable as a clean result
# on a run that went red — the whole point of this file is a count that overstates itself.
if [[ "${fail}" -ne 0 ]]; then
  echo "==== INCOMPLETE: ${total} tests across the module(s) that passed — do NOT report this as a suite count ===="
else
  echo "==== ${total} tests executed across ${checked} module(s) with test sources ===="
fi
exit "${fail}"
