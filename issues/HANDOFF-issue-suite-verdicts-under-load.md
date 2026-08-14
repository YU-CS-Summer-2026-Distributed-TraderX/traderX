# Issue: the proof suite's verdicts are unreliable on a loaded box, in three different ways

**Found** 2026-08-13, running `scripts/yu15/run-proofs.sh` against the YU17 build on kind.
**Status** open. None of these is a bug in what the proofs test; each is a bug in how a failure is
reported, and each makes a green-or-red verdict mean something other than what it says.

The suite reported six failures in one run. The engine under test was fine — the gateway answered
`ready=200` from inside the cluster throughout, no pod restarted, and every failure traced back to
an HTTP call through a port-forward returning **nothing**. What the failures actually demonstrate
is that three separate reporting paths turn "the call did not complete" into a confident statement
about the system.

## 1. A failed `curl` inside a command substitution kills the script silently

`scripts/proofs/yu16-bond-position.sh` runs under `set -euo pipefail` and does:

```bash
order() { curl -s -o /dev/null -w '%{http_code}' --max-time 20 -X POST "${MATCHER_URL}/orders" ... ; }
code="$(order "${side}" "${acct}" "${FACE}")"
[[ "${code}" == "200" ]] || fail "${side} order returned HTTP ${code} — a legal bond order was refused"
```

The `|| fail` line reads as the guard. It is not: when `curl` itself fails (connection, not a
status), the assignment's command substitution exits non-zero and `set -e` aborts the script
*before* the guard runs. The observed log ends after the step-2 header with no message at all, and
the suite recorded FAIL with nothing to explain it.

The guard is written for the case where the call succeeded and answered something other than 200 —
the case that almost never happens — and is bypassed in the case that does.

**Fix shape:** capture the curl exit status explicitly (`code="$(order ...)" || code="000"`), and
make `000` a distinct verdict from a real HTTP status. `000` means *no answer*; it is not `refused`.

## 2. An empty response is reported as a different answer

`scripts/proofs/yu05-regulatory-reproducible.sh` calls the export twice and compares hashes:

```
call 1   records=218   sha=7a1815ef557eb5dc   order-lifecycle=146
call 2   records=      sha=                   order-lifecycle=
✘ MISMATCH (h1=7a1815ef557eb5dc h2=) — the same journal range answered differently twice
```

The second call returned no body, so the JSON parse threw and both fields came back empty. The
proof then reported that the system **answered the same range two different ways** — a claim about
determinism, made from a transport failure. That is `vacuous-pass-audit` rule 7 in reverse: it
already guards the empty-*first*-call case explicitly ("two identical empty answers prove
nothing"), and then treats an empty second call as evidence.

**Fix shape:** shape-test both digests before comparing (`^[0-9]+ [0-9a-f]{16} [0-9]+$`) and refuse
with "call 2 returned no answer" rather than adjudicating a mismatch.

## 3. A proof that prints `[PASS]` is recorded as FAIL

`yu13-otel-reject-trace-log-join` and `yu13-readmodel-effect-end` both end their logs with their
own `[PASS]` verdict and were both recorded FAIL by the runner, which reads the exit code. In
`yu13-otel-reject-trace-log-join` the last thing in the log is its own port-forward being
`Terminated: 15`.

This is the mirror of `vacuous-pass-audit` rule 4 — "the exit code is the verdict" — and it is
worth stating in both directions: a script that prints a verdict the exit code contradicts is
broken whichever way round the two disagree, and a *false red* costs a session of investigation
exactly as a false green costs a session of false confidence.

Note also that `yu13-readmodel-effect-end` printed `[FAIL] order did not rest (kind=, body=)`
mid-run and then continued to `[PASS]` — `fail` was called inside a `$(...)` subshell, so it exited
the subshell and nothing else. A proof that prints a failure and carries on is a third variant of
the same defect.

## 4. One failure was correctly reported, and is a real fixture problem

`yu15-option-persistence` failed with "expected 2 trade rows (both sides) for
AAPL261218C00260000, got 4". The rows were ids `177-S`..`180-B`, contiguous and from the current
epoch, so this is a population problem rather than cross-epoch residue: the proof's own cross
landed twice. `yu16-accrued-interest` then failed downstream because `yu16-bond-position` (item 1
above) never left it a Treasury position — which the runner's own ordering comment predicts, and
which the accrual proof correctly refuses on rather than passing having checked nothing.

## Why this matters more than the individual failures

The suite is what "every inherited proof still passes" is measured with. Three of its verdicts are
currently a function of host load rather than of the code, and two of them invert. A state whose
acceptance criterion is "the inherited suite stays green" cannot be accepted or rejected on a run
that contains these, and re-running individually — which is what was done here — is a workaround,
not a fix.
