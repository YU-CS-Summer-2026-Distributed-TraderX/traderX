# The suite gate cannot tell a reduced run from a complete one

> A record, not a rig you can query. Counts come from the day this was found; re-derive them.

Found 2026-08-21 by the algo-engine lane, while auditing its own session. It caught the case in
flight and re-ran; the gate would not have caught it.

## What the gate asserts

`scripts/ci/assert-suites-executed.sh` walks every module with test sources, globs
`{module}/build/test-results/{test,integrationTest}/*.xml`, sums the `tests` attribute, and fails
the module when the total is **zero**:

```
if [[ "${ran}" -eq 0 ]]; then
  echo "::error::${module} has test sources but EXECUTED NO TESTS ..."
```

That is a good guard and it has earned its keep — it is what catches a NO-SOURCE `sourceSets`, an
uncompilable test tree, or a module no list invokes.

## What it cannot see

**It reads a directory, and any filtered run rewrites that directory.** A `cleanTest test --tests
'*Something*'` deletes the module's existing XMLs and leaves only the filtered ones. The gate then
sums those, gets a small non-zero number, and **passes** — reporting a module's suite as executed
when almost all of it was deleted moments earlier.

The failure is not that the gate goes quiet. **The gate still answers, confidently, with a number
that is now wrong in the direction of confidence.** A lane that runs a focused probe and then reports
"N tests / M modules" is reporting a corrupted count, and nothing in the output says so.

Zero it catches. **Reduced it cannot.** Those are different failures and only one is guarded.

## Why this bites here specifically

Suite counts are load-bearing in this project. Lanes report them to the coordinator as evidence, the
coordinator banks them, and they are compared across sessions to attribute deltas — on one day a
figure was only trustworthy because two lanes ran it independently from opposite sides of a
contamination and agreed. A silently-reduced count entering that chain is worse than a missing one.

## Directions

1. **Freshness.** Require the XMLs to postdate the run that is being asserted about — a partial run
   leaves a directory whose mtimes are uniform and recent, but whose *population* shrank.
2. **A floor per module**, recorded and updated deliberately. Turns "reduced" into a loud failure at
   the cost of a number that must be maintained — and a stale floor is its own trap.
3. **Detect the filtered run instead of the artifact.** Refuse to read results at all if the last
   invocation carried `--tests`. Narrow, and it puts the check where the cause is.
4. **Accept it, and make the discipline explicit** in the gate's own output: print the per-module
   counts it summed, so a human reporting them sees `1` where they expected `46`.

Direction 4 is the cheapest and is not nothing — the count is currently summed and discarded, so
today the gate knows the answer and does not say it.

**Break it first, whichever is taken:** run a filtered subset, then assert the gate fails. A check
that passes on both a complete and a reduced run is asserting nothing — the exact shape this file is
about. See `.claude/skills/vacuous-pass-audit`.

## Not established

Whether CI has ever actually reported a reduced count as complete. The mechanism is confirmed by
reading the gate; no historical run was audited for it. Nobody has looked at whether the same
directory-globbing pattern backs any other gate in `scripts/ci/`.
