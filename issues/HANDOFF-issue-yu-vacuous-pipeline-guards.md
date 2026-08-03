# Issue: the YU states silently skipped three pipeline guards, and one of them is still unreachable

**Status:** three guards fixed 2026-08-03 (`^[0-9]+$` → YU rank). One of the three, the
decommission invariant, remains unreachable for YU states for a *second*, independent reason.
**Related:** `HANDOFF-issue-spec-layer-propagation-gaps.md` — same "reported success, checked
nothing" family. Same bug class as the prune step that has never run for YU02–YU15.

---

## Summary

Three pipeline validators derived a state's numeric rank with `state_num="${STATE_ID%%-*}"` and then
guarded on `[[ "${state_num}" =~ ^[0-9]+$ ]]`, returning early when it did not match. `YU07` is not
`^[0-9]+$`, so **every one of these checks skipped all 15 YU states while exiting 0** — a vacuous
pass, not a pass.

| site | guard | what it guards |
|---|---|---|
| `pipeline/validate-generated-state-contracts.sh:49` | `… && (( 10#${state_num} >= 6 ))` | messaging subject-map presence/shape |
| `pipeline/validate-generated-state-lineage-invariants.sh:312` | same shape | "trade-feed must not reappear after state 006" |
| `pipeline/install-generated-runtime-harness.sh:728` | `\|\| return 0` | wrapper ↔ compose-project alignment |
| `pipeline/install-generated-runtime-harness.sh:1407` | `… \|\| (( 10#${state_num} < 3 ))` | writes `AGENTS.md`/`ARCHITECTURE.md`/`CONTRIBUTING.md` |

The fix is the rank already used by `publish-generated-state-branch.sh` and
`prepublish-generated-state-gate.sh`: `YU01..YU15 → 101..115`. The YU lineage forks off after 014,
so every "is this state at or past N?" threshold must answer yes.

`install-generated-runtime-harness.sh:728` is the exception that proves the rule — it builds a
*name* (`traderx-state-<prefix>`), not a rank comparison, so it keeps the id's own prefix. The
101..115 rank would have it expect `traderx-state-107`, a compose project no script declares.

## What switching them on actually found

Nothing broken. Verified per state, before and after:

- **Subject map** — all 15 packs carry `system/messaging-subject-map.md` with `## Subject Families`
  and all four required fields; the state map file resolves from the catalog `featurePack` for
  every YU id. All 15 now emit `[ok] messaging subject-map presence/shape validated`.
- **Decommission invariant** — no YU branch and no YU generated tree carries a top-level
  `trade-feed`; it was decommissioned at 006 and has not come back.
- **Compose alignment** — no YU lifecycle script declares `COMPOSE_PROJECT_NAME`, so the check
  reaches its own no-op return. No behaviour change.
- **Agentic docs** — all 15 YU states now get the three docs, naming the right state id. Previously
  the generated tree kept whatever the last numbered generation left behind (a `target-generated`
  built for `YU15-eod-risk-extract` still carried an `AGENTS.md` that said
  `014-fdc3-intent-interoperability`).

Numbered states are unaffected: harness output for 001/004/009/014 is byte-identical before and
after, and 001–005 still skip the subject-map check while 006–014 still run it.

## The part that is still unreachable

**Fixing the guard at `validate-generated-state-lineage-invariants.sh:312` does not switch that
check on for YU states.** It is dominated by a check earlier in the same function:
`validate_state_entries()` resolves an allowlist from `allowed_roots_for_state()`, which has a case
per numbered state and **no YU case at all**, and fails hard on the empty result:

```
$ bash pipeline/validate-generated-state-lineage-invariants.sh \
    --state-id YU07-historical-tick-store --snapshot-dir generated/code/target-generated
[fail] no allowlist entries resolved for state YU07-historical-tick-store
```

Upstream of that, `publish-generated-state-branch.sh` refuses YU states outright
(`state … is implemented with generation.mode=specified`), so the publisher never reaches any of
these three validators — before or after this fix. **`bash pipeline/publish-generated-state-branch.sh
<YU-state>` is not a usable end-to-end check for YU states yet**; run the validators directly.

Wiring it is the bounded work described in `ae0a9399`: declare each YU state's allowed generated-tree
roots cumulatively from 014's set — across the whole lineage only three new component directories
appear (`tick-store` at YU07, `execution-algo-engine` at YU08, `aeron-replication-sidecar` at YU11)
— plus the bootstrap ordering in `validate-generated-branch-dependency-consistency.sh`, which
demands a `code/generated-state-*` ref for every implemented state and so cannot be satisfied by the
first publish of a new lineage. The invariant itself is already known to hold (see above), so that
work is expected to land green.

## The same gap from the other side: a validator that loudly rejects a YU state

Found 2026-08-03 while the `pipeline/*.sh` bash-3.2 sweep was running. **FIXED 2026-08-03** in the
propagation pass that closes this file's sibling gap — one commit per branch, all 16
(`361272b5` on YU15). It was deliberately not fixed at discovery, because
`validate-state-doc-consistency.sh` is on all 16 branches and a YU15-only edit would reopen the
propagation gap that `a5cbd18a` documents.

```
$ bash pipeline/validate-state-doc-consistency.sh
[fail] specs/README.md Active Feature Packs missing YU01-lmax-sequencer
```

`specs/README.md` is not missing it. Line 28 reads:

```
- `YU01-lmax-sequencer` (directory: `YU01-lmax-sequencer`)
```

The awk block that parses the section strips a leading ``- ` `` and then a trailing backtick
*anchored at end of line* (`gsub(/`$/, "", line)`). YU01 is the only entry carrying a
` (directory: …)` annotation, so its line ends in `)`, the anchor misses, and the extracted token
comes out as the whole mangled remainder:

```
[YU01-lmax-sequencer` (directory: `YU01-lmax-sequencer`)]
```

which matches no catalog id. One-line fix — take the first backticked token instead of trusting the
line to end at one: `sub(/`.*$/, "", line)` in place of `gsub(/`$/, "", line)`. Verified: that
extracts `YU01-lmax-sequencer` and leaves every numbered entry unchanged.

Only one `[fail]` prints because `fail` exits; the second complaint the mangled token would trigger
(`has non-catalog Active Feature Pack: …`) is never reached.

Same family as the four guards above, opposite symptom. The guards did not recognise a YU **id** and
silently passed; this does not recognise a YU **line shape** and loudly rejects. A validator that
rejects correct input is the louder failure but the cheaper one — it gets noticed.

## Unmasked by that fix: the Feature Pack heading check has never accepted a YU state

**FIXED 2026-08-03, all 16 branches.** yaakov picked option 1 below; landed on YU15 as `f7bb7cf6`
and propagated to the other 15.

**`main` was not a verbatim copy — it had already diverged**, and the divergence is worth knowing
about for future passes over this file. main's copy already carried the YU-aware regex
`([0-9]{3}[a-z]?|YU[0-9]{2})` plus an explanatory comment, because **main's own YU packs use a
different heading shape than the YU branches do**: `# Feature Pack YU02: LMAX Kubernetes` on main
versus `# Feature Pack: YU02-lmax-kubernetes` on YU02–YU15. So main never had the loud failure —
its only gap was the `001` bookkeeping hole. The equivalent change was hand-merged there, keeping
and extending its comment. `pack_id_re` also had to be *introduced* on main; it does not exist in
that copy, and referencing it without defining it would have been an unbound variable under
`set -u` — the same variable-existence trap recorded against the NVD guard in
`HANDOFF-issue-spec-layer-propagation-gaps.md` instance 4. main now records 28 of 28, up from 27.

Found immediately after `361272b5`: with the parser bug gone,
`validate-state-doc-consistency.sh` advances past the Active Feature Packs section and fails on the
next check, which was unreachable while `fail` exited earlier.

```
[fail] specs/YU01-lmax-sequencer/README.md heading must begin with '# Feature Pack YU01:' or '# YU01 ...'
```

YU01's heading is *literally* `# Feature Pack YU01: LMAX Sequencer Architecture (Trading Hot Path)`
— it satisfies the rule the error message states. The check does not:

```bash
if [[ "${first_line}" =~ ^#\ Feature\ Pack\ ([0-9]{3}[a-z]?): ]]; then
```

Three digits with an optional letter suffix. `YU01` cannot match, and the `elif` fallback
(`^# YU01 `) does not match either, because the heading says `# Feature Pack YU01:`. Simulating the
check over all 29 catalog ids: **13 take the numeric branch, `001` passes through the alt-style
`elif`, and all 15 YU packs are rejected.** It has never accepted a YU state.

This one carries **both** symptoms at once — which is why it belongs in this file rather than
alongside the parser bug:

- **Loud rejection**, as above.
- **Silent vacuity**, the same shape as the four guards: `seen_feature_pack_numbers` is appended to
  *only inside the numeric branch*. Every id that passes via the alt-style `elif` is therefore
  invisible to it as well — so duplicate-Feature-Pack-number detection at line 99 covers **13 of 29
  states**, not 14 and not 15. It has never covered a YU pack *or* `001`. Two uncovered packs could
  share a number and nothing would notice.

Not fixed here because the correct behaviour is a **decision, not a mechanical edit** — there are
four heading styles in play and no stated canon:

| style | used by |
|---|---|
| `# Feature Pack 014: FDC3 Intent Interoperability on C3` | 002–014 (13 packs) |
| `# 001 Simple App - Base Uncontainerized App` | `001` only — passes, never recorded |
| `# Feature Pack YU01: LMAX Sequencer Architecture (…)` | YU01 only |
| `# Feature Pack: YU15-eod-risk-extract` | YU02–YU15 |

The three options, priced. Note the second touches `specs/YU01-lmax-sequencer/README.md`, which is
mid-merge in the two-unmerged-threads adjudication (11-file interlock) — editing it now walks into
an in-flight merge.

1. **Accept every shape, edit no packs.** Capture the id from either `# Feature Pack <id>:` or
   `# Feature Pack: <id>`, keep the `001` alt-style branch, and record into
   `seen_feature_pack_numbers` on *every* accepting path. Zero spec-pack churn, duplicate detection
   finally covers all 29, no collision with the YU01 merge. Leaves four heading styles standing.
2. **Canon = `# Feature Pack: <full-id>`.** 14 of 15 YU packs already match; only YU01's README
   changes — but that is the file in flight.
3. **Canon = `# Feature Pack <id>: <title>`.** YU01 already matches; 14 YU READMEs change.

Option 1 is the standing recommendation from both sessions that looked at it: the check exists to
catch mismatched and duplicate pack numbers, not to enforce prose style, and it can do that job
without touching a single pack. It is a public-facing docs question as much as a validator one, so
the canon is yaakov's call.

**Whichever is picked, the duplicate-detection bookkeeping must be fixed alongside the regex** — a
regex widened on its own trades this loud failure for a quiet one, which is precisely the failure
mode the four guards above document.

### What landed

Option 1, both halves. `pack_id_re='[0-9]{3}[a-z]?|YU[0-9]{2}'`, three accepting branches
(`# Feature Pack <id>:`, `# Feature Pack: <id>-…`, and the `001` alt style), each setting
`actual_num`; the id-match, duplicate-scan and `seen_feature_pack_numbers+=` bookkeeping moved out
of the numeric branch and onto the shared path after the `if`. No `specs/` file touched.

Re-simulated over all 29 catalog ids: **29 accepted, 29 recorded, 29 distinct, zero id mismatches**
— 14 via `# Feature Pack <id>:` (13 numbered + YU01), 14 via `# Feature Pack: <id>`, `001` via the
alt style. Duplicate-number coverage goes 13 → 29.

## Unmasked in turn: three YU packs fail the shell/PowerShell parity check

**Open — not fixed.** With the heading check accepting YU states, the validator advances one further
and stops at the *first* genuine content violation this chain has produced:

```
[fail] specs/YU09-ops-hardening/README.md contains shell invocation(s) without PowerShell equivalent
```

`YU09-ops-hardening`, `YU10-fix-ingress` and `YU11-aeron-replication` mention a `.sh` file and carry
no `.ps1` anywhere. The other twelve YU packs pass only because they never trip the trigger — none
of them carries a `.ps1` either.

Unlike every other finding in this file, **this one is not a parsing gap** — the check works, it has
simply never been pointed at these packs. What it caught is arguably a false positive, and that is
the decision to make before editing anything: the check fires on any *mention* of a `.sh` file,
whereas all three hits are prose references to developer tooling, not run instructions a Windows
user would follow —

| pack | the line that trips it |
|---|---|
| YU09 | `` `pipeline/publish-generated-state-branch.sh` rebuilds a fresh jar before every JVM service's … `` |
| YU10 | `` `scripts/bench/load/fix-load.mjs`, `scripts/proofs/yu10-fix-session.sh` — throughput sender and … `` |
| YU11 | `` `scripts/bench/run-yu11-aeron-transport.sh` — transport A/B harness and allocation proof `` |

The numbered packs that trip it (001, 002, 004, 011, 012, 013) all do carry `.ps1` equivalents,
because there the `.sh` references *are* run instructions — so the check's intent is real and it
should not be weakened on the strength of these three. Left alone here: those packs are in flight,
and this is content work on `specs/`, which the option-1 decision deliberately avoided.

### …and `main` already "fixed" it in a way that is itself a vacuous pass

`main` carries its own copies of these three packs and `validate-state-doc-consistency.sh` reports
`[ok] state-doc consistency validated (28 states)` there. It passes because each of the three gained
this bullet (`specs/YU09-ops-hardening/README.md:58` and the same in YU10/YU11):

> - No PowerShell parity: the scripts named above are POSIX shell only. The `.ps1` runners the
>   numbered states ship have no equivalent in the YU lineage — on Windows, run them under WSL
>   or another POSIX shell.

The prose is honest and useful — it tells a Windows reader exactly where they stand. But the check
is `rg -q '\.ps1`|\.ps1$|\.ps1 '`, a substring test, so **a README stating that there is no
PowerShell equivalent satisfies a check whose entire purpose is to catch READMEs with no PowerShell
equivalent.** The gate is green on the appearance of the string `.ps1`, not on the property.

So `main`'s 7/7 overstates coverage for these three packs, and "just take `main`'s copy" is not a
resolution for the YU branches — it imports the caveat, turns the check green, and changes nothing
about the underlying fact. Same family as everything else in this file, one level down: the earlier
findings were checks that never ran, this is a check that runs and is answered by a substring.

Deciding it is the same shape of call as the heading canon. Either the caveat is a legitimate answer
— in which case the check should look for an explicit marker rather than any `.ps1` mention, so an
incidental one cannot pass — or it is not, in which case `main`'s three packs need the same fix the
YU branches do. Not decided here.

## The lesson

A guard that returns early on an unrecognised id shape reports success for every state it does not
understand. When a lineage forks to a new id format, the id-parsing sites are the audit surface —
grep for `%%-*` and `^[0-9]+$` together, not for the checks themselves.
