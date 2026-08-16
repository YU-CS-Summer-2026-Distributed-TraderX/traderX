# Issue: spec-layer forward-propagation gaps (recurring pattern) + open instances

**Status: pattern documented 2026-07-14; YU04/YU05 instance FIXED same day; YU02 database-manifests
instance FIXED same day (committed on all 8 branches); `pipeline/*.sh` instance FIXED 2026-08-03
(committed on all 15 non-YU15 branches). **A fourth instance was found and fixed 2026-08-14 —
see below. No open instances.**

## Fourth instance, 2026-08-14: `pipeline/validate-generated-state-lineage-invariants.sh`

Same shape, found while closing `HANDOFF-issue-yu-vacuous-pipeline-guards.md`. The YU allowlist case
(`YU[0-9][0-9]-*) yu_allowed_roots`) existed on **YU15, YU16 and YU17 only**; the other fourteen
branches still carried the pre-fix file and still failed exactly as that issue documents. Measured
on the YU07 worktree: `[fail] no allowlist entries resolved for state YU07-historical-tick-store`.

Note what makes this instance instructive: the fix was NOT missing, it was *partially propagated*,
so any check on the tip reported success. "Is it fixed?" is a per-branch question in this repo, and
a single-worktree answer to it is not an answer. Carried to all sixteen non-`main` branches;
`main` deliberately excluded as the default branch.

## The pattern

Each YUxx branch carries the spec packs (`specs/YUxx-*/`) of **itself and all ancestors** — never
descendants. Generation composes those layers cumulatively (last-wins overlay). This means a fix or
file added to a state's spec layer **after** descendant branches were cut does NOT reach those
branches automatically. Symptoms only appear when someone generates/brings up an *older* state from
a *newer* branch — which is exactly what the state-by-state demo tour does.

Known instances of this class (chronological):

1. **CORS origins** — `http://127.0.0.1:8080` added to `CORS_ALLOWED_ORIGINS` had to be hand-copied
   to every `*-deployment.yaml` across all 8 worktrees (fixed 2026-07-14).
2. **Spec-pack reformatting** — reformatted specs existed on home branches but descendants still
   carried none/old copies; initially misdiagnosed as a revert (resolved 2026-07-14, nothing lost).
3. **YU04/YU05 kind runtime manifests** (fixed 2026-07-14, commits on all 5 descendant branches):
   YU04's k8s layer (`database-init-configmap.yaml` with the 5 control-feed tables
   `account_control_outbox`, `account_source_epoch`, `stocks`, `stocks_control_outbox`,
   `stocks_source_epoch`; `reference-data-deployment.yaml` with first-ever DB/NATS env; plus
   account/database manifests) was committed on the YU04 home branch but absent from every
   descendant. Bringing YU04 up on kind from the YU08 worktree crashlooped `account-service`
   (`Table 'traderx.account_source_epoch' doesn't exist`) and `reference-data`
   (`ECONNREFUSED 127.0.0.1:3306` — mysql2 defaulting to localhost). Live cluster was hot-patched
   (`CREATE TABLE IF NOT EXISTS` + `kubectl set env` + restarts) instead of recreated; both YU04
   proofs (live-delta no-restart, offline catch-up) then green.
4. **`pipeline/*.sh` prepublish gate — macOS bash 3.2 crash + missing NVD preflight** (fixed
   2026-08-03, commits on all 15 non-YU15 branches). **This instance widens the pattern beyond
   `specs/`:** `pipeline/*.sh` is shared root tooling with no overlay semantics at all, but every
   branch still carries its own copy, so a fix landed on YU15 reached nothing — the same gap, a
   different mechanism. Two defects, both present on every branch and both predating `b434b08e`:
   (a) under `set -u`, bash 3.2.57 — what macOS ships — treats `"${arr[@]}"` as an unbound variable
   when `arr` is empty (bash 4.4+ made it legal). `update_args` in `run_dependency_check_local` is
   empty unless `TRADERX_DEPENDENCY_CHECK_NO_UPDATE=1`, so `publish-generated-state-branch.sh` died
   in the Node CVE scan on every Mac run — *after* the container build preflight had already spent
   several minutes. `smoke-dependency-version-targets.sh` had the same shape in `args`, which a bare
   `--branch-consistency` run hits. Fixed with `${arr[@]+"${arr[@]}"}` at all three sites.
   (b) the CVE scan is the last gate, so a missing `NVD_API_KEY` surfaced as an OWASP
   dependency-check stack trace (`Invalid API Key, length of 0`) roughly fifteen minutes in; now
   checked alongside the up-front validations.
   **Propagated by re-applying the edit, NOT by copying the file** — YU15's copy carries `b434b08e`
   (accept YU state ids), which ancestors must not have. **`main` needed a different guard:** its
   gate predates `--noupdate` support, so it has no `update_args` (the array fix is N/A there) and
   no `DEPENDENCY_CHECK_NO_UPDATE` variable — referencing that var in the guard would itself have
   been an unbound variable under `set -u`, reintroducing the exact bug on the one branch CI
   watches. Generalisable lesson: when the propagated fix *adds* a reference to a variable, check
   that the variable exists on every target branch, not just that the patch applies.

## Open instances

None. Most recently closed:

- **YU02-layer database manifests untracked on every branch (FIXED 2026-07-14).** The 2026-07-09
  kind MariaDB fix (`.../YU02-lmax-kubernetes/.../base/database-{deployment,service,init-configmap}.yaml`)
  existed only as untracked working-tree files, duplicated per worktree — while the *tracked*
  kustomization.yaml referenced all three, so a fresh clone could not bring YU02/YU03 up on kind.
  The YU08 copies were newest (explanatory headers + `protocol: TCP`, content otherwise identical
  to the other 5 worktrees'); committed verbatim on all 8 branches. Note: the legacy
  `lmax-kubernetes` branch (pre-YU02-rename, carries `specs/lmax-kubernetes`) was deliberately NOT
  updated — it predates the YUxx layout entirely; the YU02 blp-ha branch is the effective home.

## Intentional divergences — DO NOT re-sync (they will look like drift and are not)

A cross-worktree md5 diff will flag these as inconsistent. **Leave them.** Re-syncing to a single
version re-breaks the diverging branch.

- **YU01 `009b` overlay patch — `RUN_FROM_GENERATED.md` hunk deliberately dropped (2026-07-24, upstream
  rebase).** `specs/YU01-lmax-sequencer/generation/patches/0001-state-overlay.patch` on
  `YU01-lmax-sequencer` is missing the one cosmetic hunk that modifies the *generated*
  `RUN_FROM_GENERATED.md` (retitle "State 009"→"009b" + start-script names) that YU02–YU15 still carry.
  **Why the divergence is correct, not drift:** that hunk applies with exact 28-line context against the
  generated baseline doc. YU02–YU15 render `009b` **in-chain**, where the baseline still matches, so the
  hunk applies. YU01 renders `009b` **standalone as its target state**, where the upstream-refreshed
  baseline no longer matches, so the hunk fails and takes the *entire* overlay patch (57 file sections)
  down with it — YU01 won't render at all. Dropping the cosmetic hunk is the fix; the generated YU01
  `009b` keeps the baseline `RUN_FROM_GENERATED.md` (a doc titled "State 009" — harmless). **A future
  propagator that copies YU02–15's 009b patch onto YU01 to "restore consistency" will re-break YU01's
  standalone render.** If this ever needs re-unifying, the correct move is to regenerate the hunk against
  upstream's current baseline (via `create-state-patchset`), not to copy another branch's version.
  Full narrative: `docs/handoff/production-readiness/REBASE-EXPERIENCE.md` (Surprise #6).

## Convention going forward

The rule is not limited to `specs/` — it covers **any per-branch copy of a shared file**, including
root tooling like `pipeline/*.sh` (see instance 4). There the fix is re-applied as an edit rather
than copied, because the branches' copies legitimately differ.

When a fix lands in an **ancestor state's** spec layer (`specs/YUxx-*/generation/...`) on its home
branch, immediately copy it to that path on **every descendant branch** and commit — the
`spec-pack-audit` skill's cross-worktree diff is the verification step. Rule of thumb: a spec-layer
file's md5 should be identical across every branch that carries it (the home branch is
authoritative when they differ) — **except for the intentional divergences listed above, which are
context-dependent and must not be re-synced.**
