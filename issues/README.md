# What `issues/` is, and the one rule that governs it

**`issues/` is ONE project-wide record, identical on every YUxx branch, synced from the lineage
tip.** A reader landing on any branch sees the current record, not a snapshot of whenever that
branch last had one propagated to it.

That is the whole rule. Everything below is consequence.

This file is itself synced by the mechanism it describes, so it lands on every branch and cannot go
stale on a subset.

## Why this file exists

The rule is not new. It was decided and applied on 2026-08-16 (commit `e3fb96c7`, "issues: sort into
open/ and resolved/, and sync the canonical record") and again, in different words, on 2026-07-14
(`24bbb520`, "Consolidate issues/ as the single home for backlog + issue docs").

**Both times it was written only into a commit message, and both times it decayed within weeks** —
the second time within eight days, into eighteen branches disagreeing with no discernible pattern.
A rule that lives in an archive is not a rule. It lives here now because this is a path people open.

## `issues/` does NOT follow lineage — and that is deliberate

Spec packs under `specs/` follow the lineage rule: a branch carries its own layer and its ancestors',
never its descendants'. **`issues/` deliberately does not**, and the temptation to "make it
consistent with spec packs" should be resisted. It has been considered and rejected twice.

Spec packs describe *what a state is*. Issues describe *defects in machinery* — and a defect's blast
radius is not its lineage. `HANDOFF-issue-spec-layer-propagation-gaps.md` is about propagation across
the whole family; `three-ci-scripts-assert-execution-at-three-different-strengths.md` is about shared
CI. Neither has a home state.

The deciding argument is operational rather than philosophical: **lineage would require a per-issue
classification judgement, made by whoever files it, forever.** It is the design with the most
decisions per file, and therefore the one most certain to regress. This rule has zero decisions per
file. Given that it has already decayed twice, "fewest judgement calls" is the deciding criterion,
not a tiebreaker.

## Then how does a branch-specific issue work?

**Per-branch nuance lives inside the document, never in which folder or which branch it sits on.**

An issue about the Aeron cluster is present on YU05, which has no cluster. That is correct and
intended. The document says which states it applies to and which branches carry its fix. A directory
cannot say that, because "fixed" in this repo is per-branch — a fix in `specs/`, `scripts/` or
`pipeline/` lives in a per-branch copy, so one issue can be closed on five branches and open on
twelve. Only prose can carry that. `HANDOFF-issue-gateway-wedges-after-leader-kill.md` is the worked
example: it names exactly which branches carry its fix.

**Corollary: `open/` vs `resolved/` is a project-level verdict, not a per-branch one.** A document in
`resolved/` may still describe something broken on some branch; the document says so.

## Layout

| path | holds |
|---|---|
| `issues/open/` | issues believed open at project level |
| `issues/resolved/` | issues believed resolved. **Records, not clutter** — several carry corrections learned expensively, some made *after* resolution. Do not delete them to shrink the sync surface. |
| `issues/*.md` at root | **not issues**: idea and theme docs (`HANDOFF-idea-*.md`), window reviews, design docs |

`issues/HANDOFF-*.md` are **tracked**, unlike root-level `HANDOFF-*.md` at the worktree root, which
are deliberately untracked scratch. Do not conflate them: the test is purpose. A durable record of a
project fact or gap belongs here; a task handoff to another session does not.

A resolved issue **moves into `resolved/`**. Leaving it at the root is the current known failure mode
— it is how a resolved defect stayed listed as open on eleven branches for eight days.

## Who applies the rule

**1. File on the tip.** New issues are filed on the lineage tip, full stop. This is what removes the
per-file judgement call — nobody decides where an issue lives.

**2. Carry in batches, never per-issue.** Per-issue propagation is the thing nobody does. One sweep,
at branch-cut time and on demand. It is cheap because it is a verbatim directory copy.

**3. Check before believing.** Print the per-branch hash of `issues/` and read the diff:

```bash
cd ~/dev/lmax/traderX-YU17-otc-rates   # any worktree
for b in $(git branch --format='%(refname:short)' | grep -E '^YU[0-9]+'); do
  printf '%-32s %s\n' "$b" "$(git ls-tree -r "$b" -- issues/ | shasum | cut -c1-12)"
done
```

Identical hashes on every row = in sync. Any row differing = a carry is owed. This is **not** wired
into CI, on purpose: CI runs on `main`, which by design has no `issues/` at all. See
`propagate-spec-fix` and `new-yu-state`.

## Before you sync: the source must be a superset

**A sync from a source that is not a superset is a deletion.** The tip being canonical does not make
it complete.

On 2026-08-24 the tip was missing six documents that existed on exactly one other branch each — five
YU12 issues and one YU15 evidence document that two tip documents already referenced by name. A
straight tip→all sync would have destroyed all six silently, and it would have looked like a
successful sync.

**Lift first, then sync.** Diff both directions before copying anything:

```bash
comm -23 <(git ls-tree -r <branch> --name-only -- issues/ | sort) \
         <(git ls-tree -r <tip>    --name-only -- issues/ | sort)
```

Anything that prints exists only on `<branch>` and must be lifted to the tip before the sweep.

Lift **verbatim, status untouched**. A stale-but-visible issue is strictly better than an invisible
one, and triage is judgement that must not be allowed to block a mechanical sweep. File the triage
as its own issue.

## `main` is excluded, and its empty `issues/` is CORRECT

`main` holds no `issues/` and **is not a propagation target**. Do not sync to it; it is reached by PR
only.

The reason is not merely mechanical. **`main` is a product branch** — it carries the composed,
published states, not a lane's working record. An empty `issues/` there is the right state, not drift.

This is written down because `main` otherwise reads as the family's worst divergence while being the
one branch that is right, and an audit that re-flags a deliberate absence every time is a tax. That
tax has been paid at least once already.

## When you carry

Read `propagate-spec-fix` first — particularly the two hash checks and the decision table
(*target == pre-change source → copy; target != → hand-merge*). Issue documents have already caused
one incident of exactly this kind: one file 299 lines on one branch and 493 on two others, where an
edit anchored on shared text landed everywhere and left the short copy short.

**Hash-check at commit time, not at copy time.** A verbatim copy is correct only against a fixed
source, and these worktrees are shared with live sessions that may be mid-edit. That rule was earned
by an incident (`22c8bf6e`) in which twelve branches received a document asserting a gap that had
just been closed on five of them — because it was copied while another session was editing it.
