# `main` presents 31 states and can only compose up to YU15

**Found 2026-08-24** on `main`, while landing the prune-fix carry
([the-skipped-prune-step-breaks-the-composed-test-tree](resolved/the-skipped-prune-step-breaks-the-composed-test-tree.md)).
**Pre-existing and unrelated to that carry** — proved, see *Not mine* below. **Open.**

## The fact

`bash pipeline/generate-state.sh YU17-otc-rates` on `main` exits **1**, and so does
`YU16-cdm-instruments`. Both die at the same place, while rendering the YU16 layer:

```
[ok] installed ui state metadata for YU16-cdm-instruments (2 target(s))
[ok] ui status metadata validation passed for YU16-cdm-instruments (2 file(s))
[fail] missing mandatory runtime scripts for env wrappers in YU16-cdm-instruments
[hint] expected start/stop/status scripts under .../generated/code/target-generated/scripts
```

`YU15-eod-risk-extract` and everything below it generate cleanly (exit 0). So `main` carries the
packs, the lifecycle scripts, the catalog entries and the site pages for all 31 states — `0bbde648`
put them there — and **cannot actually compose the top two.**

## Why

`pipeline/install-generated-runtime-harness.sh` decides what to copy into the generated tree's
`scripts/` from a **hardcoded per-state list**, not from the catalog or a glob. On `main` that list
runs YU01 → YU15 and mentions YU16 and YU17 **zero times**:

```
$ grep -c YU15-eod-risk-extract pipeline/install-generated-runtime-harness.sh   # 10
$ grep -c YU16-cdm-instruments  pipeline/install-generated-runtime-harness.sh   # 0
$ grep -c YU17-otc-rates        pipeline/install-generated-runtime-harness.sh   # 0
```

The gate is the `case` at line 530. YU16 and YU17 match no arm of it, so the branch that copies
`start|stop|status-state-<id>-generated.sh` never runs, the generated `scripts/` dir ends up with no
`start|stop|status-state-*` at all, and `resolve_generated_script_for_action` (line 672) returns
empty for all three mandatory actions. `write_env_entrypoint_wrappers` (line 794) then fails.

The repo-level scripts are **not** the problem — `scripts/{start,stop,status}-state-YU16-*-generated.sh`
and the YU17 equivalents exist on `main` and match the tip's byte for byte. Nothing is missing from
the repo; the copy list simply never learned about them.

**`0bbde648` ("main: carry YU16-cdm-instruments and YU17-otc-rates") carried the packs, scripts,
catalog entries and wiring, and did not extend the pipeline's hardcoded state lists.** That is the
whole defect.

## The fix is a two-file carry from the tip, and both files were checked

Not one file. A sweep for shared `pipeline/` files that name YU15 and never name YU16 turns up three
candidates; two are real, one is a false positive:

| file | on `main` | verdict |
|---|---|---|
| `pipeline/install-generated-runtime-harness.sh` | YU15×10, YU16×0 | **carry from tip.** `diff` against the tip is 42 lines and is *entirely* the YU16/YU17 extension — no main-specific content to preserve. |
| `pipeline/install-generated-ci-assets.sh` | YU15×2, YU16×0 | **carry from tip.** `diff` is the two missing `state_allowed_roots` arms (`YU16-cdm-instruments)` and `YU17-otc-rates)`) and nothing else. |
| `pipeline/lib/state-rank-selfcheck.sh` | YU15×1, YU16×0 | **leave alone.** Byte-identical to the tip; its lone YU15 mention is an example string, not a list. |

Verifying is one line each — `grep -c YU16-cdm-instruments <file>` must be non-zero — and the real
check is that `generate-state.sh YU17-otc-rates` reaches exit 0 on `main`.

Whoever picks this up: `install-generated-ci-assets.sh` feeds `validate-root-spec-kit-gates` and
`verify-spec-coverage` via `allowed_roots_for_state`, both of which run on every push to `main`, so
run all seven root gates plus the Docusaurus build afterwards, not just the generation.

## Not mine — the control

Both files landed by this session's carry (`pipeline/prune-generated-state-removed-assets.sh`,
`specs/YU11-aeron-replication/generation/prune-manifest.json`) were stashed with
`git stash push -u -- <the two paths>`, the tree regenerated, and `generate-state.sh YU17-otc-rates`
failed **identically** — same state, same line, same hint. The stash was then popped and the prune
script re-confirmed by hash. The prune fix neither causes nor hides this.

## Why it stayed invisible

The failing states are the two newest, the ones least likely to be composed by hand, and every
catalog-driven gate on `main` passes — the gates judge *presentation*, and the presentation is
complete. Nothing on `main` composes a YU16 or YU17 tree as part of a gate, so a state can be fully
presented and wholly ungeneratable at the same time without a single check going red. That is the
same shape as the prune bug it was found next to: a silent skip that reads exactly like a legitimate
outcome.
