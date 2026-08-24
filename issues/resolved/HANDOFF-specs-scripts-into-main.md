# Handoff: copy specs/ and scripts/ from every YU branch into main

**Written:** 2026-08-03. **Task owner:** next chat. **Untracked by design** — this is a session
handoff, not a project record (see `~/dev/lmax/CLAUDE.md`).

---

## Goal for next chat

`main` today carries the **original 15 upstream states** — `specs/001-…` through `specs/015-…`, plus
their `scripts/start-state-0XX-*-generated.sh` runners. It carries **none of the 15 YU states**.

Make `main` carry all 30 the same way: `specs/YU01-…` through `specs/YU15-…`, and the YU scripts
that go with them.

This is a **content-reconciliation job, not a copy job.** The one-line version of why is in the next
section, and it is the whole reason this task got its own chat.

---

## The finding that defines the work

**"Just copy from the tip" is wrong, and so is "copy each pack from its home branch."**

`YU15-eod-risk-extract` carries all 30 spec packs (its own plus every ancestor's), so it *looks*
like a single source you can copy from. It is not. Measured this session, comparing **tracked
content only** (`git ls-tree -r`, blob hashes — an earlier `find`-based count was wrong because it
counted untracked build artifacts and TAQ data):

| Pack | files on home branch | files on YU15 | differing tracked entries |
|---|---:|---:|---:|
| YU01-lmax-sequencer | 83 | 88 | **111** |
| YU02-lmax-kubernetes | 148 | 151 | 21 |
| YU03-in-memory-risk-gateway | 64 | 62 | 6 |
| YU04-durable-control-feeds | 78 | 78 | 2 |
| YU05-post-trade-compliance | 80 | 80 | 2 |
| YU06-eod-price-production | 55 | 50 | 9 |
| YU07-historical-tick-store | 43 | 42 | 5 |
| YU08-execution-algo-engine | 60 | 60 | 2 |
| YU09-ops-hardening | 35 | 35 | **0** |
| YU10-fix-ingress | 33 | 33 | 6 |
| YU11-aeron-replication | 104 | 104 | **0** |
| YU12-aeron-cluster | 60 | 60 | 8 |
| YU13-limit-order-book | 90 | 89 | 5 |
| YU14-listed-equity-options | 43 | 42 | 19 |

**12 of 14 differ. Only YU09 and YU11 are clean.**

### Divergence runs in BOTH directions — this is the trap

The project's convention is: fix on the home branch, then hand-carry **forward** to descendants
(`.claude/skills/propagate-spec-fix`). So you would expect the tip to be a superset. It is not,
because work also gets authored *on the tip* against an ancestor's layer and never carried back.

Both directions were proven live this session:

- **Home ahead of tip.** `specs/YU13-…/…/MatchingEngineClusteredService.java` was **780 lines with
  the OTEL-01 member spans on the YU13 branch, and 695 lines without them** in the YU14 and YU15
  worktrees' copies of that same layer. On YU15 the staleness is invisible (YU15's own override
  shadows it); on YU14 it was fatal. Copying that pack from the tip would have put the stale file
  into `main`.
- **Tip ahead of home.** The kdb `txstore.q` / `txselfcheck.q` / fixtures half of the YU07 layer was
  authored on YU13/14/15 (it needed a cluster) and had never reached YU07's own branch, nor YU08
  through YU12 at all. Fixed this session, but it is the same shape.
- **Files present on one side only, in both directions, in the same pack.** YU01: the tip has
  `account-service/build.gradle`, `AccountRepository.java`, `CpuAffinity.java`; the home branch has
  `OrderResponse.java`, `AccountTopicCache.java`. YU14: the home branch has
  `generation/kubernetes/cluster/gke/c4d-node-system-config.yaml`, the tip does not.

**YU01 is the worst by a wide margin (111 differing entries).** Its pack was renamed out of `009b`
during the rebase and its worktree was only created 2026-07-31. Do it **last**, or on its own.

---

## The scripts gap

| | tracked files under `scripts/` |
|---|---:|
| `origin/main` | 97 |
| `YU15-eod-risk-extract` | 251 |

~154 files. They fall into clean groups:

- **Per-state runners, 4 × 15 = 60 files:** `start-state-YUxx-*-generated.sh`,
  `stop-state-YUxx-*-generated.sh`, `status-state-YUxx-*-generated.sh`, `test-state-YUxx-*.sh`.
  These mirror exactly what main already has for `0XX` states, so this group is the most
  mechanical part of the job.
- **Directories main has none of:** `scripts/bench/`, `scripts/ci/`, `scripts/proofs/`,
  `scripts/yu11/`, `scripts/yu12/`, `scripts/yu14/`, `scripts/yu15/`.
- **One-offs:** `bench-009-vs-009b.sh`, `deploy-state-YU02-…-gke.sh`,
  `prepare-state-YU02-…-gke-manifests.sh`, `push-state-YU02-…-gke-images.sh`,
  `provision-yu03-staging-secret.sh`, `test-local-jvm-jar-build.sh`,
  `test-messaging-YU01-lmax-sequencer.sh`, `test-web-angular-pricing-ux-contract.sh`.

Scripts are much less likely to have diverged than spec packs (they are not layered), but **verify
rather than assume** — the same home-vs-tip check applies.

---

## main's CI will start judging the new packs

`main` is green today partly because it only sees 15 states. Its workflows:

```
.github/workflows/  archive/  deploy-docusaurus-pages.yml  docs-spec-sanity.yml
                    generate-openapi.yml  runtime-script-parity.yml  spec-kit-root-gates.yml
```

> **Corrected 2026-08-03** after reading the workflows properly. An earlier draft of this section
> claimed the gate globs `specs/` and that the PowerShell check would fail. Both were wrong. What
> follows is verified against the actual scripts.

**The gate is `catalog/state-catalog.json`-driven, not `specs/`-glob-driven.** Dropping 15
directories into `specs/` triggers **nothing**. `pipeline/validate-state-doc-consistency.sh`
iterates `.states[].id` from the catalog (21 entries today). Everything below fires only once you
add the YU states to that catalog — which you must, for them to be *presented* like the original 15.

Per catalog id, the validator requires:

1. `specs/<id>/` exists.
2. `specs/README.md` "Active Feature Packs" lists exactly the catalog ids — **bidirectional**; an
   entry there that is not in the catalog is also a failure.
3. `specs/<id>/README.md` **first line** matches `^# Feature Pack ([0-9]{3}):` with the number equal
   to `${id%%-*}`, or the alternate `^# ${id%%-*} `. For `YU07-historical-tick-store` that prefix is
   `YU07` — not three digits — and our packs read `# Feature Pack: YU07-historical-tick-store`, which
   **matches neither branch**. → 15 first-line edits; `# YU07 historical-tick-store` satisfies the
   alternate form.
4. Both support badges (`badgen.net/badge/linux%2Fmac/`, `badgen.net/badge/windows/`) — **all 15 YU
   packs already have both. Nothing to do.**
5. A README mentioning a `.sh` must also mention a `.ps1`. **Exactly 3 packs trip this: YU09 (1
   mention), YU10 (1), YU11 (2).** Three README edits.
6. For ids marked `implemented`: the getting-started doc must contain a `code/generated-state-<id>`
   reference, and `website/sidebars.js` a `learning/state-<id>` entry. It checks the **doc
   reference**, not that the branch exists.

**On the PowerShell gate in `runtime-script-parity.yml` — it is a non-issue.** The check is
`Get-ChildItem -Path scripts -Recurse -Filter *.ps1`, failing only if **zero** `.ps1` exist anywhere
under `scripts/`, then parsing whichever it finds. `main` has 14. YU states shipping none changes
nothing. Do not relax the gate, and do not generate `.ps1` runners.

**Escalate to yaakov:** there are 28 `code/generated-state-0XX` branches upstream and **zero**
`code/generated-state-YU*`. If "like the original 15" is meant literally, that pattern includes a
per-state generated-code branch each. The validator will not force it (item 6 only greps the docs),
but adding links to branches that do not exist leaves dead links in the getting-started page. That
is a scope call, not a technical one.

- **`docs-spec-sanity.yml`** and **`spec-kit-root-gates.yml`** are the other two root gates.
- `main` has **no `engine-tests.yml`** — YU15 does. Decide deliberately whether the JVM engine
  suites should start running on `main`; that is a separate call from "carry the specs across."

---

## Branch / repo state

- **Branch layout:** every directory under `~/dev/lmax/` is a worktree of one repo. Map is in
  `~/dev/lmax/CLAUDE.md`. `traderX` is **YU03**, not the tip — a path that looks like "the repo" is
  not the newest state.
- **Nothing is pushed.** Unpushed commit counts as of this handoff:

  | Branch | ahead | Branch | ahead |
  |---|---:|---|---:|
  | YU01-lmax-sequencer | 1 | YU09-ops-hardening | 8 |
  | YU02-lmax-kubernetes-blp-ha | 2 | YU10-fix-ingress | 8 |
  | YU03-in-memory-risk-gateway | 2 | YU11-aeron-replication | 9 |
  | YU04-durable-control-feeds | 3 | YU12-aeron-cluster | 9 |
  | YU05-post-trade-compliance | 3 | YU13-limit-order-book | 20 |
  | YU06-eod-price-production | 6 | YU14-listed-equity-options | 20 |
  | YU07-historical-tick-store | 8 | YU15-eod-risk-extract | 19 |
  | YU08-execution-algo-engine | 8 | | |

- All worktrees clean of uncommitted tracked changes in `specs/`, `docs/`, `scripts/`, `website/`.
- **`generated/` is git-ignored**, so CI renders before it can check anything. Do not commit a
  generated tree into `main`.

---

## What this chat accomplished (context for why the audit exists)

- **Documented the KDB-X layer and the derived trace in the spec packs**, which carried them for the
  first time — YU07 gets FR-TS10–15 / NFR-TS05–09 / SC-TS08–10 and ADR-059; YU13 gets FR-TR01–07 /
  NFR-TR01–04 / SC-TR01–03 and ADR-060. Propagated across all 9 and 3 carriers respectively.
- **Carried the `kdb/` directory to YU08–YU12**, which had never received it — the pack described
  files five of its nine carriers did not have.
- **Repaired YU14's clustered service.** Generated YU14 had not compiled since 2026-07-27: commit
  `1792c1f2` made the YU13-layer gateway call `service.spanSink()`, and YU14's override — cut from a
  pre-tap, pre-tracing YU13 — had no such method. Rebuilt as the YU13 file plus YU14's five options
  hunks; verified by construction (`diff` vs YU13 shows those five hunks and nothing else).
  Generated YU14 now: **320 tests, 0 failures**. YU15: **340, 0**.
- **Reframed the kdb docs** to lead with why kdb+/q (the time-series database this corner of finance
  runs on, for journaling and playback) rather than with the two-store split.
- **Ran the full proof suite on the YU15 rig: 19/19 PASS, 0 fail, 0 skip**, one uninterrupted run,
  rig restored to baseline images with all three members in agreement.
- The home-vs-tip audit in this document came out of the YU14 repair — it is the generalisation of
  the bug that repair fixed.

---

## Key files

| Path | Why it matters |
|---|---|
| `~/dev/lmax/CLAUDE.md` | Loaded from every worktree. Git conventions, worktree↔branch map, the lineage rule. Read first. |
| `.claude/skills/propagate-spec-fix/SKILL.md` | The procedure for exactly this class of work — home-branch-is-authoritative, verify by md5, beware shadowed layers. |
| `.claude/skills/spec-pack-audit/SKILL.md` | House-style audit for spec packs; useful if packs need normalising before landing on main. |
| `specs/README.md` (on `main`) | How main presents the existing 15 states — match this shape. |
| `pipeline/validate-state-doc-consistency.sh` | The parity contract `runtime-script-parity.yml` enforces. |
| `.github/workflows/runtime-script-parity.yml` | Will start judging the new packs. |
| `specs/YU13-…/generation/implementation-status.md` | Contains the layer-coverage note and the YU14 repair record — the worked example of this divergence class. |

---

## Architecture / context the next chat needs

**The lineage rule.** Each branch carries spec packs for **itself and all its ancestors, never its
descendants**. Generation composes `runtime-overrides` layers cumulatively, last-wins. Two
consequences that bite:

1. A fix landed on a state's home branch after its descendants were cut reaches **nothing**
   automatically.
2. A fix applied to a **shadowed** layer is inert — and stays silently inert until that layer becomes
   the operative one on some other state. That is precisely how generated YU14 ended up not
   compiling for a week without anyone noticing.

**`main` is the upstream-sync branch.** It tracks `finos/main`. The website's `repoBranch` in
`website/docusaurus.config.js` defaults to `YU15-eod-risk-extract` **specifically because main does
not carry our `docs/` or `specs/`** — every "Edit this page" link would 404 against main. If specs
land on main, that default becomes a live question. **Do not change it as a side effect** of this
task; raise it separately.

---

## Decisions already made (don't re-litigate)

- **Never `git push`.** yaakov pushes. Sole allowlisted exception: `git push origin YU15-eod-risk-extract*`.
- **Never add a `Co-Authored-By: Claude` trailer** or "Generated with Claude Code" to any commit or
  PR body. Applies to subagents and background tasks too.
- **Handoff/scratch docs stay untracked** at the worktree root. Durable project facts go in tracked
  `issues/`.
- The 15 YU states are final — this task carries them across, it does not add, rename or merge any.
- `traderX` (the plainly-named worktree) is **YU03**. Not a candidate for "the source of truth".

---

## Open questions / known issues

1. ~~Which direction wins per file?~~ — **answered 2026-08-03 by the session doing the work.** It
   adjudicated all 51 differing files from git history: **23 home-wins / 26 tip-wins / 1 merge / 1
   equivalent.** Genuinely per-file; neither branch is authoritative as a rule.

   **Do not use the per-pack "zero code divergence → copy from the tip" shortcut** that an earlier
   draft of this document implied. That classification counted only `runtime-overrides/**.{java,…}`
   as code and swept Kubernetes manifests and Dockerfiles in with the docs — and those are where at
   least 4 of the 6 "clean" packs actually diverge. Copying them from the tip ships regressions:
   YU07's `tick-store-deployment.yaml` is pinned to a private `pkg.dev` registry (the sole
   unreverted straggler of a pin that was walked back elsewhere), and YU13's GKE manifests still
   select a node pool the runbook no longer creates, so all three members sit `Pending` forever.
2. **Should the reconciliation be fixed on the YU branches first, then copied to main once?** That is
   almost certainly cheaper than reconciling into main and leaving the branches divergent — but it
   turns this task into "heal 12 packs, then copy," which is a bigger job than the ask.
   **Ask the originating session, not yaakov** — see "Who to ask" below. It has the full context for
   why this divergence exists and what each instance of it turned out to be.
3. **`engine-tests.yml` on main** — carry it or not? Separate decision.
4. ~~PowerShell parity~~ — **resolved, was a false alarm.** See the corrected CI section; the gate
   only requires that *some* `.ps1` exist under `scripts/` and parse, and main has 14. The real
   coupling is the README `.sh`-implies-`.ps1` rule, which trips exactly 3 packs.
5. **YU01 (111 diffs)** needs its own session, and the presumption is **home wins, not tip** — 31
   files newer on home vs 0 newer on the tip, plus real bidirectional file-level differences.
6. **`code/generated-state-YU*` branches do not exist** (28 exist for the `0XX` states). Whether the
   YU states should get them is a scope call for yaakov — see the CI section.
6. This work will not be visible on the site until `deploy-docusaurus-pages.yml` runs, and that needs
   a push first.

---

## Who to ask — message the originating session, not yaakov

yaakov's explicit instruction: **route the judgement calls in this document to the session that wrote
it, not to him.** That session did the YU14 repair this divergence generalises from, ran the 19/19
proof suite, and holds the context for why each pack differs.

**How.** Use `mcp__ccd_session_mgmt__send_message` with:

```
session_id: local_872b3441-8939-499b-922a-02bba9c3e9f3
```

If that id does not resolve, try the bare form `872b3441-8939-499b-922a-02bba9c3e9f3`; if neither
works, call `mcp__ccd_session_mgmt__list_sessions` and pick the `cwd: /Users/yaakov/dev/lmax` session
whose recent work is the YU14 clustered-service repair and the YU15 proof-suite run. (That session
could not set its own title — the tool refuses to rename the calling session — so match on content,
not on a title string.)

Three constraints worth knowing before you rely on this:

- **It only works while that session is still open.** If it has been closed, fall back to asking
  yaakov directly — the question itself is stated plainly in Open Question #2.
- `send_message` is **unavailable in unattended sessions** (scheduled-task runs, remote-dispatched)
  and cannot deliver to them either. This needs to be an interactive chat on both ends.
- The send **prompts yaakov for approval**, so he sees the traffic. Write the message to be
  intelligible to him as well as to the other session — one clear question, not a context dump.

**Ask it early.** Question #2 changes the shape of everything after step 3; do not do half the work
and then discover the other shape was correct.

---

## Known bug this task will otherwise carry into main

**Generated YU02 does not compile.** Proven by building it, not inferred:

```
PricingNatsBinarySubscriberService.java:88: error: cannot find symbol
  symbol:   method onPriceTickRaw(String,long,long)
  location: variable orderMatcherService of type OrderMatcherService
```

The YU02-layer `OrderMatcherService` does not define `onPriceTickRaw`, while the YU02-layer
`PricingNatsBinarySubscriberService` (which no later layer overrides) calls it. **This is not a
home-vs-tip reconciliation** — the file reports 0 on `YU02-lmax-kubernetes-blp-ha`,
`YU03-in-memory-risk-gateway` and `YU15-eod-risk-extract` alike. There is no correct side to copy
from; the method has to be added to the YU02 layer on YU02's home branch and propagated forward, as
an ordinary bug fix, before or alongside this task.

**Blast radius is exactly state YU02.** `OrderMatcherService` is overridden at 009, YU01, YU02, YU03
and YU05, and the YU03 and YU05 copies both define the method — so YU03 onward compose a copy that
has it. That is why generated YU15 builds green at 340 tests with YU13's `LmaxHotPathParityTest`
calling `onPriceTickRaw` three times.

**Sampling caution:** the YU03-layer copy reports 1 on YU03/YU04/YU05/YU09/YU12/YU15 and **0 only on
`traderX-blp-ha-demo`** — a YU02 variant carrying a stale YU03 pack. YU03's real home is the
`traderX` worktree. Do not treat `blp-ha` as authoritative for any pack but YU02.

---

## Suggested first steps for next chat

1. Read `~/dev/lmax/CLAUDE.md` and `.claude/skills/propagate-spec-fix/SKILL.md`.
2. Re-run the audit to confirm it still holds (it is cheap, and branches may have moved):
   compare `git ls-tree -r <home-branch> specs/<pack>` against
   `git ls-tree -r YU15-eod-risk-extract specs/<pack>` for each of the 14.
3. **Message the originating session with open question #2** (see "Who to ask" above) — heal-then-copy
   vs reconcile-into-main. Do this before writing anything.
4. Start with **YU09 and YU11** (0 diffs). They are a free end-to-end rehearsal of the whole
   pipeline — pack + scripts + CI gates — with the content question removed.
5. Then the small-diff packs (YU04, YU05, YU08 at 2 each), then the mid ones, then YU02/YU14, and
   **YU01 last**.
6. Land the mechanical script groups (the 60 per-state runners) alongside, and check the three root
   gates on main go green before adding more.
