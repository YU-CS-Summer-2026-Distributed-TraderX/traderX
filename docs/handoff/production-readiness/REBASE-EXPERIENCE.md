# Tracking an upstream: what it costs a layered downstream fork

> **A live narrative — written as it happens, not reconstructed after.** This is the story of rebasing our
> 14-state TraderX lineage onto seven weeks of upstream FINOS changes. It is a companion to the sizing doc
> ([01-upstream-rebase-spike-FINDINGS.md](01-upstream-rebase-spike-FINDINGS.md)): that one is *the decision*,
> this one is *the journey* — the surprises, the traps, the judgment calls, kept honest as they land.
>
> Audience: FINOS / open-source practitioners who maintain, or depend on, a public baseline. The lesson
> generalizes past TraderX: this is what it actually costs to be a downstream.
>
> **Status: IN PROGRESS.** Spike done. Merges pending one cross-lane decision (§4). Batches logged in §6 as
> they complete.

---

## 0. The one-sentence version

Upstream shipped **62 commits and changed zero lines of application code**, yet catching up still costs
~150 hand edits and 2–3 days — and *none* of that cost is visible to `git`. Why that sentence is true, and
true of layered forks in general, is the whole talk.

---

## 1. Setup — why rebase, and the shape of the fork

**The forcing function.** Our FINOS conference talk was accepted, and the mandate flipped from *build
features* to *prove production credibility*. A fork that has drifted seven weeks behind its upstream's
**security baseline** is not a credible production story — 29 of the 62 upstream commits are CVE
remediation. "We're behind on CVEs" is the one slide that actively undercuts the talk. So: rebase.

**What we forked, and how.** FINOS TraderX isn't a single app; it's a **spec-kit** — a lineage of numbered
"states" (001 → 014), each a spec pack that *generates* a runnable system by overlaying its own changes onto
the previous state's output. We forked at upstream state 014 and grew our own lineage on top: **YU02 → YU15**,
fourteen more states carrying the LMAX/Aeron engine, the clustered matcher, durable risk feeds, the EOD risk
extract, and everything else that makes this "ours."

**Why that makes a rebase hard — the mechanism the audience needs.** Two composition models coexist, and
both bite:

- `generation/runtime-overrides/` **composes cumulatively, last-wins.** Every state can drop in its own copy
  of any file; at render time the layers stack and the highest one wins. Powerful — and the source of the
  central trap (§5): if an ancestor and a descendant both carry a file, editing the ancestor changes
  *nothing*, because the descendant's copy wins.
- `generation/kubernetes/` **does not compose at all.** It's a per-state `cp -R`. Every state carries a
  complete manifest set; a change to one must be hand-carried to all.

So a single upstream change to a shared baseline file can, in principle, ripple through all fourteen states —
and the toolchain will not tell you which ones. That "will not tell you" is the recurring villain of this
story.

---

## 2. Sizing the delta — measure the output, never trust the commits

The first instinct on any rebase is `git log upstream..HEAD` and read the commit messages. **For a
generation-model fork, that instinct is wrong**, and proving so was the point of the spike.

**What the commits *say*:** 62 commits, 116 files, +29 904 / −23 801 lines. That line count reads like a
rewrite. It is almost entirely `package-lock.json` churn and a new marketing website.

**What we did instead — diff the rendered outputs.** We found the true fork point
(`git merge-base finos/main YU01` → `de58b8fa`, 2026-06-07), then **rendered the shared baseline state (014)
twice** — once from our fork point, once from `finos/main` today — into two clean trees, and diffed the
*generated* result:

```
differing *.java in any service ............ 0
differing NestJS / front-end *.ts logic .... 0
```

**Zero.** Not one line of service logic changed in seven weeks. Everything that differs is a `build.gradle`
version, an `application.properties` line, a Dockerfile, a `package.json`, an Angular `environment.*.ts`, or
a timestamp. The scary line count was noise; the signal was in the rendered tree, and only there.

> **Surprise #1:** the honest size of an upstream delta is not in its diffstat. In a generation model, the
> repository is a *program that emits the system*; you have to run the program and compare what it emits.
> We diffed outputs, not sources, for every claim in this document — it's the only trustworthy view.

---

## 3. Classifying — clean / conflicting / structural

We sorted the delta into three buckets, by how much work each imposes.

| Bucket | Definition | Count | Cost |
|---|---|---|---|
| 🟢 **Clean** | Upstream changed files we never override | ~172 generated paths + all of `website/`, `docs/`, `specs/015` | Free; half we don't even consume |
| 🟡 **Conflicting** | Upstream changed files we override | 13 generated files (+ a 5–7 file git-conflict set) | The real work — see §4/§5 |
| 🔴 **Structural** | New/renamed/removed services, schema, message contracts | **0** | — |

**The structural bucket — the expensive one — is empty.** No new/renamed/removed services (upstream's one
new "state 015" is a Docusaurus homepage, not a runtime component). No message-contract change. No API
change. And one genuine trap:

> **Surprise #2 — a re-layering is indistinguishable from a feature.** `database/initialSchema.sql` appeared
> to gain an `OrderBook` table — a *structural* schema change, potentially colliding with our own YU13 order
> read model. It wasn't. Upstream **moved** the existing table definition from the state-009 overlay up into
> the state-004 overlay (same DDL, different owning layer). Net generated schema: unchanged. Catching it took
> a `git log -S"CREATE TABLE OrderBook"` across both revisions. **In a layered fork, upstream re-layers
> constantly, and every re-layering wears the costume of an addition.** You cannot classify from the diff —
> only from the rendered output (again).

The collision surface is tiny for a reason worth stating plainly: **across 304 of our own commits, we edited
exactly 7 files that upstream owns.** We put essentially everything in our own layers instead of hacking
upstream's tree. That discipline is why the structural bucket is empty and why the conflict set (§4) is the
*same handful of files on every branch* — we never fought upstream for ownership of a file, so upstream never
had to fight us back.

---

## 4. The decisions — where judgment actually lives

Merging `finos/main` into each YU branch produces **5–7 git conflicts, strictly nested, identical on every
branch** (5 from YU02, +2 more from YU09). They are not all "take upstream" — most need real 3-way reasoning
to keep *our* features alive. The decided resolutions:

| Conflict file | Resolution | Why |
|---|---|---|
| `pipeline/prepublish-generated-state-gate.sh` | **3-way** — keep our `009b` letter-suffix handling **and** take upstream's leading-zero decimal fix | Pick-one loses either our `009b` state's gate *or* their fix. We need both. |
| `pipeline/render-state-009…sh` | **3-way** — adopt upstream's new `normalize-observability-runtime.sh` **and** re-append our `GF_DASHBOARDS_MIN_REFRESH_INTERVAL: 1s` | Upstream refactored our Grafana hardening into a shared script — but dropped the sub-5s refresh our trades/sec benchmark dashboard depends on. |
| `scripts/start-` / `status-state-010-*.sh` | **take upstream** | Baseline demo scripts using upstream's new shared readiness lib; our real k8s lives in our own layer, so their version is self-consistent and low-stakes. |
| `website/docusaurus.config.js` | **`--ours`** | We don't ship the site. |
| `pipeline/install-generated-runtime-harness.sh` | **per-branch 3-way** | The one genuinely branch-specific conflict — it's *ours*, extended differently at every state. |

> **Surprise #3 — the biggest decision surfaced through a merge conflict, disguised as a small one.** The
> `specs/004` overlay-patch conflict looked like "our 7 test-script tweaks vs upstream's refresh." It is
> actually this: **our fork's overlay patch deletes 32 of upstream's baseline test/smoke scripts** from the
> generated tree (every per-service overlay test — account, database, people, position, reference-data,
> trade-service, trade-feed, trade-processor, web — and every per-state test script 002–014), and upstream
> has spent much of these seven weeks *maintaining and expanding exactly those*. So the "conflict" is a
> strategic question wearing a diff's clothing: **keep stripping upstream's baseline test harness, or
> re-inherit it?** — and it collides head-on with our own concurrent work to add baseline tests and get CI
> green.
>
> This is the transferable point: **a merge conflict is a lousy place to make a strategy decision, but it's
> where they ambush you.** The right move is to *recognize* it as strategy, lift it out of the merge, and
> decide it deliberately in the lane that owns it — not to let `git`'s conflict UI railroad you into a
> keystroke. We paused the merge and escalated it to the testing lane, which decided `--ours` (keep
> stripping): the 32 are *runtime* smokes that assume vanilla behavior we changed, and the wrong kind of test
> for the unit-test brief.

> **Surprise #6 — and then `--ours` turned out to be mechanically impossible.** We committed `--ours` and
> tried to render. It failed: `[fail] unable to apply patch even with 3-way merge`. **An overlay patch is a
> derived artifact welded to the baseline it patches** — its context lines *are* the upstream files (the very
> test scripts, the `start-base` script, the dependency lines). Upstream refreshed those files, so our stale
> `--ours` patch no longer applies, and the state does not generate *at all*. The "keep today's known-green
> generation" rationale for `--ours` evaporated: the stale patch generates *nothing*.
>
> The lesson sharpens: **you cannot resolve a generated-artifact conflict by picking a side, because one
> side is stale against a baseline that moved.** The mechanically-valid options collapse to (a) take
> upstream's refreshed patch and accept the re-inherited scripts, or (b) re-author your intent on top of
> upstream's new structure — which, for a *deletion*, means re-stripping files upstream now actively
> maintains, i.e. buying the same conflict again on every future rebase. And the decision's own premise
> shifted under it: we verified the CVE bumps land *regardless* of this choice (our dead-layer push-down
> already carries them in the shadowing layer, §5), and the 32 scripts are not wired into any unit suite or
> CI — so re-inheriting them cannot redden the green gate. The choice was re-escalated to the owning lane
> with that ground truth. *(Outcome recording here when it lands.)*
>
> This is the single best story in the rebase: **the process caught its own bad decision — but only because
> we verified the rendered output.** A fork that trusted the green merge would have shipped a branch that
> doesn't build. Inputs lie; outputs don't (lesson 2, again, with teeth).

---

## 5. The dead-layer execution — where the real 150 edits are

Here is the part `git` refuses to help with, and the single most important thing this document has to teach.

Upstream bumped Spring Boot 3.5.14 → 3.5.16, logback 1.5.32 → 1.5.34, log4j 2.25.4 → 2.26.1, kotlin-stdlib
2.3.20 → 2.4.10, PostgreSQL-JDBC 42.7.11 → 42.7.12, H2 2.3.232 → 2.4.240, tomcat 10.1.55 → 10.1.57, added a
Jackson BOM, and added npm overrides. Those land at baseline layers **004/005/006/007/009**. And **every one
of those files is re-declared, verbatim, in our YU02 layer and above.** Last-wins.

So the merge succeeds, the bump is inherited into the ancestor layer — **and discarded at generation time,
because our copy shadows it.** Green merge. Green build. Green tests. Zero CVE fixes actually in the running
system. Nothing anywhere warns you.

> **Surprise #4 (the headline) — `git` reports on the merge INPUTS, not the generated OUTPUTS that actually
> run.** A clean cherry-pick into a shadowed layer *applies to git* and is *inert at generation*. The
> dangerous merges in a layered fork are the ones with **no conflicts at all.** If we had merged upstream and
> shipped on the strength of a green pipeline, we'd have believed we picked up nine CVE fixes and picked up
> none.

We proved the shadowing empirically rather than assuming it — rendering the full chain 004 → 014 → YU02 and
comparing md5s: the generated `account-service/build.gradle` is **byte-identical to our YU02 override**; the
ancestor layer contributes nothing. So the actual work of the rebase is **~150 one-line edits**, each applied
at the **highest carrying layer** on each branch, driven off upstream's `catalog/dependency-version-targets.json`.

**Verify two ways, every time** — this is the discipline that makes the dead-layer trap survivable:
1. **Spec md5** — confirm the override file changed.
2. **Re-rendered, marker-grepped tree** — render from the *lowest changed ancestor forward* (not from the
   tip) and grep the generated output for the new version strings. Only the generated tree tells the truth.

> **Surprise #5 — the cost curve is measurable, and it grows with your history.** The number of shadow-copies
> to re-edit per branch: **8 at YU03, 10 at YU05, 12 at YU09, 14 at YU11, 16 at YU15.** Every state we ever
> added made this catch-up — and every future one — a little more expensive. Nobody decided that; it accreted
> one `cp -R` at a time, because copying a whole file into your layer is the fastest way to change one line in
> it. That curve *is* the price of lineage depth, and it's invisible until an upstream change lands on a file
> you copied.

---

## 6. Validation — and the per-batch log (LIVE)

**Approach.** After each branch's merge + dead-layer edits: re-run that branch's suites **one at a time**
(concurrent gradle breaks `ThreeMemberClusterTest`). Local-green is the gate; engine-tests CI is the
confirmation. The allocation gates + the two Epsilon-GC gates (incl. `noGcTest`) are the regression net —
they catch a semantic regression from a dependency bump (the one to watch is `kotlin-stdlib 2.3.20 → 2.4.10`,
the only minor-not-patch bump). Push in **green batches**, in lineage order, never piecemeal.

| Batch | Branch(es) | Merge | Dead-layer edits | Suites | Notes |
|---|---|---|---|---|---|
| 1 | YU02 (template) | ✅ `89280f68` — 5 conflicts (2 true 3-way, 2 take-upstream, 004 **`--theirs`** after Surprise #6) | ✅ `1adfc2fd` — 8 build.gradle + NATS across YU02+009b layers | ✅ **no regression** — 37 tests / 12 fail, **identical to pre-merge baseline** | CVEs verified in rendered tree (boot 3.5.16 / log4j 2.26.1 / kotlin 2.4.10 / logback 1.5.34 / nats 2.14), **zero old-version leakage**. The 12 failures are pre-existing `@SpringBootTest` context tests needing DB/infra a bare `./gradlew test` doesn't provide — proven by rendering + testing the pre-merge tip (`e30ac2e3`): same 37/12, same two classes. |
| 2 | YU03 | ✅ merge (5 conflicts, recipe) | ✅ java push-down | ✅ **63 tests, 0 fail** (BUILD SUCCESSFUL) | Risk-gateway layer wires the `@SpringBootTest` context YU02 lacked → passes what YU02 couldn't. |
| 3 | YU04 | ✅ merge | ✅ java + **npm** push-down | ✅ render-verified | Surfaced the **npm dead-layer**: `reference-data/package.json` shadowed the baseline and dropped upstream's new npm overrides. Extended push-down to merge catalog `npm.overrides` (multer 2.2.0 etc.). |
| 4 | YU05, YU07, YU08 | ✅ merge (recipe) | ✅ java + npm | ✅ **135 / 134 / 134 tests, 0 fail** | Clean render, CVEs land, zero old-version leakage on all three. |
| 4 | YU06 | ✅ merge | ✅ java + npm | ⚠️ pre-existing compile break (**baseline-confirmed NOT the rebase**) | `OrderMatcherRiskMismatchTest` calls the `OrderMatcherService` constructor with stale args — a version-independent arity mismatch. Neither file touched by the rebase; the **pre-merge tip fails to compile identically**. A dead/broken test the rebase *surfaced*, did not cause (brief-03/04 item). |
| 5 | YU09–YU12 | ✅ merge (7-conflict) | ✅ java + npm | ✅ **144 / 151 / 195 / 211, 0 fail** | `install-generated-runtime-harness.sh` resolved by **union** (keep our `build-jvm-jar` lib + add upstream's new `observability-runtime` + `kubernetes-smoke-readiness` libs); docusaurus `--ours`. |
| 6 | YU13 / YU14 / YU15 | ✅ merge (7-conflict) | ✅ java + npm | ✅ **269 / 283 / 300, 0 fail** | The deterministic-core suites — matched their known-good counts exactly. YU14 threw one *load-induced* flake (`SnapshotBarrierPerformanceTest`, 50.3ms vs a hard 50ms threshold under heavy parallel-build load); isolated re-run passed at 0.355s. Not a regression. |

**✅ ALL 14 BRANCHES DONE (YU02→YU15).** Every branch: merged, dead-layer push-down (Java + npm + NATS),
renders clean, CVEs land in the generated tree with **zero old-version leakage**, suites green or
no-regression. Two pre-existing broken tests surfaced (YU02 `@SpringBootTest` infra set; YU06 stale
constructor) — both baseline-confirmed as *not* rebase-caused. All commits local; **unpushed** (push goes to
yaakov, in green batches). Only `kotlin-stdlib 2.3.20→2.4.10` was a minor-not-patch bump; it broke nothing
(deprecation warnings on `RestTemplateBuilder`, no errors).

**Surprise #7 — a config decision silently reverted by `--theirs`, invisible in the merge diff.** yaakov had
scoped the three inherited governance workflows to **PR-only** (dropping their `on: push` trigger) so they'd
stop failing on every working-branch push — a standing decision made *before* this rebase, on YU15 only.
Taking `--theirs` on those workflows during the merge silently **re-armed the push trigger** on YU02–YU14.
There was no conflict to see: on those branches our version *was* upstream's push-triggered version, so it
flowed clean. The only way to catch it was to inspect the **trigger of the pushed file**, not the merge
diff — the same "verify the output, not the merge" lesson (lesson 2) in a new costume: **a merge doesn't
just fail to apply your intent to shadowed files (§5); it can silently *undo* a config decision you made on
another branch, and report nothing.** Fix folded in as a third commit per branch (restore PR-only, keep
upstream's job content). This is *not* the conform-vs-retire decision — that's still the brief-03/04 call;
PR-only is the interim that keeps the push from re-flooding the inbox.

**The multer catch, concretely.** The npm dead-layer (§ batch log, YU04) dropped upstream's
`multer` override — pinning it back to **2.2.0** closes the file-upload DoS advisories
(GHSA-fjgf-rc76-4x9p / GHSA-g5hg-p3ph-g8qg class) that upstream's baseline had already pinned and our
shadowing `reference-data/package.json` was silently reverting. That is the "shadow layer swallows a CVE"
lesson (§7, lesson 6) as a single, nameable fix.

**One separable follow-up remains (not core rebase):** upstream's three spec-kit governance workflows
(`spec-kit-root-gates`, `runtime-script-parity`, `docs-spec-sanity`) lint doc conventions our states don't
follow — e.g. our READMEs read `# Feature Pack: YU02-lmax-kubernetes` but the gate wants `# Feature Pack
YU02:`. That's mechanical to conform, but the gates check more than headings, so it deserves its own focused
pass (conform, then *run the gates to confirm green* — a blind heading swap isn't "conform"). Scoped and
handed to the testing/CI lane; the gates are currently PR-scoped (non-blocking), so it isn't urgent.

**Recipe mechanization (what made the replay safe and fast).** The deterministic conflicts (prepublish-gate,
render-009, start/status-010, 004) resolve identically on every branch, so they were captured once and
replayed by script: canonical resolved blobs for the byte-identical files, a marker-region swap for
render-009, `--theirs`/`--ours` for the take-a-side files. Only `install-generated-runtime-harness.sh`
(YU09+) needs real per-branch reasoning — it's *ours*, extended differently per state. The push-down is
catalog-driven and idempotent (Java build.gradle + npm package.json overrides). Net: the 5-conflict batch
(YU02–YU08, seven branches) went green as a unit. **Two branches (YU02, YU06) surfaced pre-existing broken
tests** — the rebase's regression-net method (baseline-diff) is what tells "the bump broke it" from "it was
already broken," and both here are the latter.

**Regression-net method (established on YU02, reused per branch):** a raw failure count is meaningless
without a baseline. To tell *rebase regression* from *pre-existing environmental failure*, render **and test
the pre-merge tip** of the branch and diff the failing set. YU02: pre-merge `e30ac2e3` and post-rebase both
show 37 tests / 12 failed / same two classes → the dependency bump (incl. the one minor, kotlin
2.3.20→2.4.10) breaks nothing; the 12 are `@SpringBootTest` context loads that need H2/DB the compose stack
provides and a bare gradle run does not. *(That gap — engine `@SpringBootTest`s red under plain `./gradlew
test` — is a real CI-setup item for brief 04, independent of this rebase.)*

**004 decision (recorded):** `--ours` — keep stripping upstream's 32 baseline **runtime** smoke scripts.
They curl live services and assume upstream behavior we deliberately changed (CORS, unknown-user 404,
blotter-upsert contract), so re-inheriting wholesale reddens CI against our modified runtime; and they're
the wrong *kind* of test for brief 03, which wants **unit** tests. Keeping `--ours` preserves today's
known-green generation and keeps the rebase a clean dependency/CVE catch-up. **The link worth noting:**
stripping these scripts is *why* the coverage inventory shows trade-service/position/etc. with zero
executable tests + "dead smoke files" — so "re-inherit upstream's baseline smoke harness" is filed as an
explicit brief-03/04 task (the deliberate fix-path for that gap), **not** smuggled in through a merge
conflict. That is lesson 5 (§7) in the concrete.

*(Rows filled as batches complete. Nothing here is projected — only logged after it's green.)*

---

## 7. The transferable lessons — the gold

None of this is TraderX-specific. If you maintain a public baseline, or depend on one, these are the parts to
take away.

1. **The paradox: 62 commits, 0 code changes, ~150 hand edits.** The cost of tracking an upstream has almost
   nothing to do with what the upstream *changed*, and almost everything to do with the *shape of your fork*.
   A flat fork would have absorbed this in an afternoon. Ours didn't, because of layering — which we chose,
   for good reasons, and now pay for on every catch-up.

2. **`git` reports on inputs, not outputs.** In any generation / templating / overlay model, `git merge`
   tells you about the source files, and the source files are not what runs. A clean merge into a shadowed
   layer is *inert and dangerous*. **Treat a conflict-free merge as unverified, not as done** — render the
   output and diff *that*.

3. **The shadow-copy cost curve is real and monotonic.** 8 → 16 shadowed files, YU03 → YU15. Lineage depth
   has a measurable maintenance tax. If you must layer, *patch* files into your layer, never `cp -R` them —
   a whole-file copy silently signs you up to hand-carry every future upstream change to that file, forever.

4. **Re-layerings masquerade as features.** Upstream moving a definition between layers looks exactly like
   an addition in your fork. Diff rendered outputs, or you'll chase ghosts (and mis-size your structural bucket).

5. **Strategy decisions ambush you inside merge conflicts.** The biggest call in this whole rebase (keep vs
   re-inherit 32 baseline test scripts) arrived disguised as a 3-line diff. Recognize them, lift them out of
   the merge, and decide them in the owning lane — don't let the conflict UI make them for you.

6. **The CVE dimension is *why* you can't just not-track.** 29 of 62 commits were security fixes. It is
   tempting for a heavily-customized downstream to freeze against upstream ("we changed too much to merge").
   But a security baseline is exactly the thing you cannot afford to let drift. The layering that makes the
   catch-up expensive is also what silently swallows the fixes (lesson 2) — so a lazy downstream doesn't just
   *fall behind* on CVEs, it can believe it caught up while it didn't. That combination is the real hazard.

7. **The most valuable thing an upstream can ship a fork is a machine-readable manifest of intent.** Upstream
   spent these seven weeks centralizing every dependency pin into `catalog/dependency-version-targets.json`
   plus validation gates. That single file turns our catch-up from "read 62 commits" into "diff one JSON and
   push the values down." Their own refresh tooling even generalizes to our layers if pointed at the right
   roots. **Ask your upstream for a version manifest you can diff. It's worth more than any number of merge
   commits.**

---

## 8. The verdict

Tracking an upstream open-source baseline, as a deeply-layered downstream fork, cost us **2–3 days and ~150
edits for a delta that contained no code changes at all.** That is not a bug in TraderX or in our fork; it is
the *inherent* tax of the trade-off we made — cumulative-overlay layering buys us clean, independent,
composable states, and charges us a maintenance tax that scales with lineage depth and hides itself from the
version-control tooling.

Was it worth doing? **Yes, unambiguously** — 29 CVE fixes against a production-credibility mandate, for a few
days of mechanical work, with our own allocation/GC gates as the safety net. Deferring would have been
defensible on raw commit-value (55 of 62 commits are worthless to us) and *wrong* on security posture.

Was it worth documenting? That's what this file is. The friction is the finding: **a layered downstream fork
can track an upstream cheaply in code-review terms and still silently fail to inherit what it merged.** The
forks that survive that are the ones that verify outputs, not inputs — and that is a lesson worth a slide.

---

*Living document — last updated during execution. Numbers are measured, not projected; per-batch outcomes in
§6 are logged only after they're green.*
