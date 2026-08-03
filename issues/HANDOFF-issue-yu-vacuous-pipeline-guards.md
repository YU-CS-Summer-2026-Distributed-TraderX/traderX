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

**Open — not fixed.** Found 2026-08-03 immediately after `361272b5`: with the parser bug gone,
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
check over every catalog id: **14 numbered packs pass, all 15 YU packs are rejected.** It has never
accepted a YU state.

This one carries **both** symptoms at once — which is why it belongs in this file rather than
alongside the parser bug:

- **Loud rejection**, as above.
- **Silent vacuity**, the same shape as the four guards: `seen_feature_pack_numbers` is appended to
  *only inside the numeric branch*, so no YU pack is ever recorded, and the duplicate-Feature-Pack-
  number detection at line 99 has never covered a single YU state. Two YU packs could share a number
  and nothing would notice.

Not fixed here because the correct behaviour is a **decision, not a mechanical edit** — there are
three heading styles in play and no stated canon:

| style | used by |
|---|---|
| `# Feature Pack 014: FDC3 Intent Interoperability on C3` | all 14 numbered packs |
| `# Feature Pack YU01: LMAX Sequencer Architecture (…)` | YU01 only |
| `# Feature Pack: YU15-eod-risk-extract` | YU02–YU15 |

Widening the regex to `([0-9]{3}[a-z]?|YU[0-9]{2})` fixes YU01 and still rejects YU02–YU15; making
the YU02–YU15 style canonical means changing YU01's README instead. Pick the canon first, then the
regex and the duplicate-detection bookkeeping follow.

## The lesson

A guard that returns early on an unrecognised id shape reports success for every state it does not
understand. When a lineage forks to a new id format, the id-parsing sites are the audit surface —
grep for `%%-*` and `^[0-9]+$` together, not for the checks themselves.
