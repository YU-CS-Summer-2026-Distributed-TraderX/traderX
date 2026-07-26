# 01 — Upstream TraderX rebase: SPIKE FINDINGS

> Spike executed 2026-07-24. Timebox 1–2 days; actual elapsed ≈ half a day.
> Deliverable per [01-upstream-rebase-spike.md](01-upstream-rebase-spike.md): a **sized decision**, not a migration.
> Nothing was migrated. No branch was modified. All measurements are reproducible from the commands in
> [Appendix A](#appendix-a--how-every-number-here-was-produced).

---

## TL;DR

| Question | Answer |
|---|---|
| How far behind are we? | **62 commits**, 2026-06-07 → 2026-07-22 (~7 weeks) |
| Structural bucket (new/renamed/removed services, schema, message contracts) | **EMPTY.** Zero. |
| Service source code changed upstream | **Zero lines** — proven by rendered-tree diff, not commit messages |
| Real content of the 62 commits | 29 dependency/CVE bumps, 29 generator-plumbing fixes, 6 website/docs |
| Git-level conflicts merging upstream into our branches | **5–7 files**, the *same* files on every branch (strictly nested) |
| Generation-level work (the part git will not tell you about) | **13 shadowed files × 14 branches** ≈ 150 one-line edits |
| Effort | **2–3 days**, of which ~1 day is test wall-clock |
| Risk | **Low** — the only semantic change is patch/minor dependency versions |


---

## 1. The fork point, and a correction to the brief's framing

The brief says "FINOS TraderX has changed since we forked it". True, but the topology matters and it is
better than assumed:

```
finos/traderX  main ────●───────────────●─────────────────────────● f60def6e (2026-07-22, tip)
                        │               │
                        │          36b192d5 (2026-06-21) = our GitHub fork's origin/main
                        │
                   de58b8fa (2026-06-07) ── FORK POINT
                        └──── YU01 → YU02 → … → YU15   (304 commits, ours)
```

- **Fork point: `de58b8fa`**, 2026-06-07, *"[codex] fix generated dependency CVE targets (#390)"*.
  Confirmed as `git merge-base finos/main YU01-lmax-sequencer`, and it is the merge-base for the whole
  lineage — YU01…YU15 all descend from that single commit.
- **`origin/main` (the YU class fork) is a strict ancestor of `finos/main`.** It carries no commits of its
  own. So there is exactly **one** upstream, linear, no divergence to reconcile. The brief implicitly
  assumed a two-level upstream problem; there isn't one.
- **We are 62 commits behind**, 116 files, +29 904 / −23 801 lines. That line count looks alarming and is
  almost entirely `package-lock.json` and a new Docusaurus homepage.

### What the 62 commits actually are

| Category | Commits | Reaches our runtime? |
|---|---|---|
| Renovate / CVE dependency bumps | 29 | **Yes — this is the only category with value for us** |
| Generator + publish-gate plumbing (upstream's own CI for states 001–014) | 29 | Mostly no |
| Website / Docusaurus / docs portal (incl. new `specs/015-docs-portal-homepage`) | 6 | No |

**Not a single upstream commit touches a TraderX service's source code.** Verified *three* ways:

1. The changed-file list contains zero paths under `account-service/`, `position-service/`,
   `trade-processor/`, `trade-service/`, `reference-data/`, `people-service/`, `web-front-end/`,
   `price-publisher/`, `database/`, `ingress/` at the repo root.
2. Inside the generated overlay patches, the only touched paths in those components are `build.gradle`,
   `package.json`, `application.properties`, `gradlew`/wrapper, and `SPEC.manifest.json`.
3. **The decisive check — rendered output, not git names.** I rendered the shared baseline state (014) from
   *our* fork point and from `finos/main` today, into two clean trees, and diffed them. Result:

   ```
   differing *.java in any service ............ 0
   differing NestJS/front-end *.ts logic ...... 0   (excludes .spec, environment configs)
   ```

   Every file that differs is a `build.gradle` (dep versions), an `application.properties` (upstream
   dropped one `springdoc.swagger-ui.disable-swagger-default-url` line), a Dockerfile, a `package.json`,
   an Angular `environment.*.ts`, or the timestamped `state-ui.json`. Plus a handful of Angular *additions*
   at the baseline (`tradingview-symbol-map.ts`, `serve-angular-dist.js`, an `environment.interface.ts`).
   **Build files and config. No service logic, anywhere.** This is what makes the brief-03 go/no-go safe.

---

## 2. The three buckets

### 🟢 CLEAN — takes automatically

~172 of the 185 genuinely-changed generated paths are files we have never overridden, plus everything under
`website/`, `new_website/`, `docs/`, `specs/015-*`, and most of `pipeline/`. These merge with no action and
most of them we don't even consume.

### 🟡 CONFLICTING — and this is where the brief's warning earns its keep

There are **two different, non-overlapping conflict sets**, and confusing them is the trap.

**(a) Git conflicts — trivial.** A dry-run merge of `finos/main` into every YU branch
(`git merge-tree --write-tree`) yields:

| Branch | Conflicts |
|---|---|
| YU01-lmax-sequencer | 3 |
| YU02 … YU08 | 5 |
| YU09 … YU15 | 7 |

And they are **the same files on every branch** — a strict nesting, not fifteen different problems:

```
pipeline/prepublish-generated-state-gate.sh                                    ┐
pipeline/render-state-009-order-management-matcher.sh                          │ the 5,
scripts/start-state-010-kubernetes-runtime-generated.sh                        │ everywhere
scripts/status-state-010-kubernetes-runtime-generated.sh                       │ from YU02
specs/004-containerized-compose-runtime/generation/patches/0001-state-overlay.patch ┘
pipeline/install-generated-runtime-harness.sh     ┐ added at YU09
website/docusaurus.config.js                      ┘
```

Resolve once on YU02, replay fourteen times, add two more resolutions at YU09. Half a day, mechanical.

Worth noting *why* the collision surface is that small: **we have edited almost nothing upstream owns.**
Across the entire lineage (`de58b8fa..YU15`), our diff against upstream-owned paths is **7 files**:
`catalog/learning-paths.{md,yaml}`, `catalog/state-catalog.json` (registering our states), three *new*
Grafana dashboards dropped into `specs/009/generation/runtime-overrides/`, and one real edit —
`specs/004-.../0001-state-overlay.patch`, where we removed 7 upstream test-script sections. Upstream
wholesale-refreshed that same patch. That single file is the only genuine content conflict in the repo.

**(b) Generation conflicts — the real work, and git reports none of them.**

This is the dead-layer trap from the INDEX, and the spike confirms it is not theoretical. Upstream bumped
Spring Boot 3.5.14 → 3.5.16, logback 1.5.32 → 1.5.34, log4j 2.25.4 → 2.26.1, kotlin-stdlib 2.3.20 → 2.4.10,
PostgreSQL JDBC 42.7.11 → 42.7.12, H2 2.3.232 → 2.4.240, tomcat 10.1.55 → 10.1.57, added a Jackson BOM pin,
and added `multer`/`qs` overrides to reference-data. Those bumps land at layers **004 / 005 / 006 / 007 /
009**. Every one of those files is **re-declared verbatim in our YU02 layer and above**. Last-wins.
The merge succeeds, the bump is discarded, and nothing anywhere warns you.

Proven empirically, by rendering the full chain 001 → 014 → YU02 into a scratch tree and comparing md5s
(both verification methods the brief demands — spec md5 **and** a re-rendered tree):

```
generated/…/account-service/build.gradle   md5 6830111785c3e2c6715a154f1a70a19a
specs/YU02-…/runtime-overrides/account-service/build.gradle   md5 6830111785c3e2c6715a154f1a70a19a   SHADOWED
… identical for order-matcher, trade-processor, position-service build.gradle
```

and grepping the rendered tree:

```
id 'org.springframework.boot' version '3.5.14'      ← upstream now says 3.5.16
implementation 'ch.qos.logback:logback-core:1.5.32' ← upstream now says 1.5.34
implementation 'org.apache.logging.log4j:log4j-api:2.25.4'   ← upstream now says 2.26.1
```

The generated file is **byte-identical to our YU02 override**. The ancestor layer contributes nothing.

The 13 files that must be hand-edited at the highest carrying layer on every branch:

| File | Carriers on YU15 (last wins) |
|---|---|
| `account-service/build.gradle` | 009b, YU02, YU04 |
| `account-service/src/main/resources/application.properties` | 009b, YU02, YU04 |
| `position-service/build.gradle` | 009b, YU02, YU06 |
| `position-service/src/main/resources/application.properties` | 009b, YU02, YU06 |
| `trade-processor/build.gradle` | 009b, YU02 |
| `trade-processor/src/main/resources/application.properties` | 009b, YU02, YU05, YU06 |
| `order-matcher/build.gradle` | 009b, YU02, YU09, YU10, YU11, YU12, YU13 |
| `order-matcher/.../OrderMatcherApplicationTests.java` | 009b, YU02 |
| `reference-data/package.json` | YU04 |
| `web-front-end/.../trade/trade.component.ts` | YU02 (frontend-overrides), YU03 |
| `web-front-end/.../trade/order-ticket/order-ticket.component.{ts,html}` | YU02, YU03 |
| `kubernetes-runtime/manifests/base/nats-broker-deployment.yaml` | YU02 |
| `kubernetes-runtime/…/observability-grafana-dashboards-configmap.yaml` | YU03, YU05, YU06 |

**The shadow count grows monotonically with lineage depth** — the number of redundant copies of these
files per branch:

```
YU03  8      YU07 11      YU11 14      YU15 16
YU04 10      YU08 11      YU12 15
YU05 10      YU09 12      YU13 16
YU06 11      YU10 13      YU14 16
```

That is the cost curve of a layered fork, measured. Every state we add makes the next upstream catch-up
slightly more expensive, and none of that cost is visible to git.

### 🔴 STRUCTURAL — empty

The expensive bucket the brief warned about **does not exist in this delta.**

- **No new, renamed, or removed services.** Upstream added one new *spec* (`015-docs-portal-homepage`) and
  it is a marketing website, not a runtime component.
- **No database schema change.** One near-miss worth recording: `database/initialSchema.sql` appears to gain
  an `OrderBook` table. It does not. Upstream **moved** the existing table definition from the state-009
  overlay up into the state-004 overlay (commit `0059e48d`, "Refresh state 009 generated overlay patch").
  Same DDL, different owning layer. Net generated schema unchanged. *(I nearly filed this as a structural
  finding; it took a `git log -S` across both revisions to see it was a re-layering. Flagging it because
  the same illusion will recur — upstream re-layers a lot, and in a layered fork a re-layering looks
  exactly like an addition.)*
- **No message-contract change.** The six OpenAPI contracts under `ingress/api-explorer/contracts/` show
  only patch-refresh churn. NATS subjects untouched.
- **No API change.** The only endpoint-shaped diff is the blackbox-exporter probe target
  `trade-processor:18091/health` → `/system/health` (commit `b38fba2f`) — the *probe* was wrong, the
  service always served `/system/health`. Observability config, not a contract.
- Additive only: an `/api/docs/` nginx location, and `nats:2.10-alpine` → `nats:2.14-alpine`.

---

## 3. Blast radius per state

Damage is concentrated exactly where the brief predicted — the inherited services — and it is shallow.

| State | Git conflicts | Shadowed files to re-apply | Notes |
|---|---|---|---|
| YU02-lmax-kubernetes-blp-ha | 5 | 8 | Carries the most baseline overrides (95 files); most bumps land here |
| YU03-in-memory-risk-gateway | 5 | 8 + web-front-end (4) | Owns `trade.component.ts`, `order-ticket.*` → shadows upstream's pricing-UI fix |
| YU04-durable-control-feeds | 5 | 10 | Sole carrier of `reference-data/package.json` |
| YU05-post-trade-compliance | 5 | 10 | |
| YU06-eod-price-production | 5 | 11 | |
| YU07-historical-tick-store | 5 | 11 | |
| YU08-execution-algo-engine | 5 | 11 | |
| YU09-ops-hardening | 7 | 12 | |
| YU10-fix-ingress | 7 | 13 | |
| YU11-aeron-replication | 7 | 14 | First state with `generation/kubernetes` (9 files, no overlay → hand-carry) |
| YU12-aeron-cluster | 7 | 15 | `generation/kubernetes` 13 files |
| YU13-limit-order-book | 7 | 16 | `generation/kubernetes` 13 files |
| YU14-listed-equity-options | 7 | 16 | `generation/kubernetes` 13 files |
| YU15-eod-risk-extract | 7 | 16 | `generation/kubernetes` 23 files |

**Our own code is untouched, as predicted.** Of our 407 unique override paths, 219 are `order-matcher`
(the LMAX engine + cluster), 38 `execution-algo-engine`, 12 `tick-store`, 10 `aeron-replication-sidecar`,
22 `kubernetes-runtime`. Upstream cannot and did not touch any of it. The intersection with upstream's
delta is 13 files — **3.2% of our override surface.**

The `generation/kubernetes` no-overlay problem is real but small here: the only manifest-level change is
the NATS image tag, present in 2 files per state on YU11–YU15 plus the YU02 override. ~11 files, hand-carried.

---

## 4. Recommendation

### Reframe the work

**This is not a rebase. There is no upstream code to rebase onto** — upstream changed zero service source
in seven weeks. What upstream actually produced is a **dependency and CVE baseline update**, and it is
delivered through one file we can consume directly: `catalog/dependency-version-targets.json`, which
upstream promoted into a single source of truth for every pin (Spring Boot, logback, log4j, kotlin, tomcat,
commons-lang3, springdoc, gradle wrapper, and now a `docker.images.nats` entry), together with gates
(`pipeline/validate-generated-dependency-targets.sh`,
`pipeline/validate-generated-branch-dependency-consistency.sh`) that check generated branches against it.

So: **take the catalog file, then push its values down to the highest carrying layer on each branch.**
That is a scripted find-and-replace over ~150 one-line edits, not a merge exercise.

Upstream's own refresh tool, `pipeline/refresh-generated-java-dependency-baseline.sh`, takes directory roots
as arguments and rewrites every `build.gradle` beneath them — so **we can point it straight at
`specs/*/generation/runtime-overrides/`** and it will do the right thing. Two caveats: nothing in the
pipeline points it there today (it is only ever invoked on generated output, which is why our layers have
drifted seven weeks behind), and it only normalizes **Spring Boot, springdoc and tomcat**. The logback,
log4j, kotlin-stdlib, PostgreSQL-JDBC and H2 pins — and every `package.json` — still need a small script of
our own. Call it 30 lines, driven off the same catalog JSON.

### Proposed plan (RECOMMENDED)

1. **Merge `finos/main` into each YU branch.** 7 conflicts, identical everywhere; resolve on YU02, replay.
   Take `--ours` for `website/docusaurus.config.js` (we don't ship the site). ~3h.
2. **Adopt `catalog/dependency-version-targets.json` verbatim**, then script the push-down of every pin to
   the highest carrier per branch. ~4h.
3. **Hand-carry the NATS `2.10-alpine → 2.14-alpine` tag** into the 11 `generation/kubernetes` + override
   manifests. ~1h.
4. **Verify two ways, per branch** — md5 the spec layer, then re-render from the *lowest changed ancestor*
   forward (004, not the tip) and grep the generated tree for the new version strings. ~2h.
5. **Re-run suites per branch, one at a time** (YU13 269 / YU14 283 / YU15 300 + the earlier states).
   ~1 day wall-clock. This is the long pole and the actual risk control.
6. **Skip entirely:** `website/`, `new_website/`, `specs/015-docs-portal-homepage`, upstream's publish-gate
   plumbing for states we don't publish.

**Total: 2–3 days.** **Risk: low** — every semantic change is a dependency version. The only bump warranting
attention is `kotlin-stdlib 2.3.20 → 2.4.10` (minor, not patch), which the order-matcher pulls in via
socket.io-client and which our allocation gates and `noGcTest` will catch if it regresses.

### Options rejected, with reasons

- **Rebase everything commit-by-commit.** No. 304 of our commits versus 62 of theirs, on files that do not
  overlap. A merge is strictly cheaper and loses nothing.
- **Rebase the baseline layer only and let descendants inherit.** Doesn't work here, and this is the finding
  worth carrying to the talk: **there is no inheritance for these files.** Our YU02+ layers re-declare them
  whole, so "fix it at the bottom" is precisely the inert cherry-pick the INDEX warns about. Verified.
- **Defer with a documented rationale.** Defensible, and it was a real candidate — 55 of 62 commits are
  worthless to us. But 29 of them are CVE remediation, the phase mandate is *production credibility*, and
  the price is 2–3 days. "We are seven weeks behind on our upstream's security baseline" is a bad slide.
  **Deferring is the wrong call when the cost is this low.**
- **Track upstream continuously from here.** Not now — it is a post-conference process question. Noted in §6.

### Sequencing

Not on the critical path for the conference. Slot it **after** briefs 02/03/05 are moving, or run it in
parallel — it touches build files only, so it collides with nobody. The one hard constraint: **it must not
run concurrently with brief 03's test-writing on the same branch**, because it rewrites `build.gradle`.

---

## 5. ⭐ Go/no-go for brief 03 — **GO, START NOW**

The whole reason this spike went first was: *"Brief 03 unit-tests the plain-vanilla TraderX we forked;
brief 01 rebases that exact code; doing 03 first means rewriting those tests."*

**That collision does not exist**, and this is not an inference from commit messages — it is a rendered-tree
diff (§1, check 3). The plain-vanilla `account-service`, `position-service`, `trade-processor`,
`trade-service`, `reference-data`, `people-service`, and `web-front-end` **logic** that brief 03 would test
is byte-identical between our fork point and `finos/main` today: zero differing `.java`, zero differing
service `.ts`. Tests written against it cannot be invalidated by this rebase.

Two caveats, both cheap to design around:

1. **`build.gradle` and `package.json` will move.** Brief 03 should not assert on dependency versions, and
   should expect the test runner's classpath to shift by a patch release. Trivial.
2. **The Angular front-end is the one place with real upstream churn** — new About/Status components,
   `routing.ts` entries, an expanded `trade-feed.service.ts`, `tradingview-symbol-map.ts`. These are
   *additions at the 004 baseline layer*, not rewrites of what we forked. **Recommendation: brief 03 starts
   with the Java and NestJS services and defers front-end unit tests until after the merge.** That is the
   right order anyway — the backend is where the production-credibility story lives.

**Verdict: brief 03 is unblocked. It should not wait for this work. The two can run in parallel provided
they are not on the same branch at the same time.**

---

## 6. Running notes — what it actually costs a layered fork to track upstream

*(This section is the talk content. Kept honest, including the parts where the spike's own assumptions
were wrong.)*

**The headline is a paradox, and it is the interesting part.** Upstream shipped 62 commits and changed
**zero lines of application code**. Yet catching up still costs 2–3 days and ~150 hand edits. The cost has
almost nothing to do with what upstream changed and almost everything to do with **how our fork is shaped**.

**1. Git's conflict report is not the cost estimate.** Seven conflicted files across fourteen branches
reads as "an afternoon." The actual work is thirteen files that merge *cleanly* and are then silently
discarded at generation time. **The dangerous merges here are the successful ones.** If we had merged
upstream and shipped, we would have believed we picked up nine CVE fixes and picked up none — with a green
build, green tests, and no warning anywhere in the toolchain. This is the single most transferable lesson
of the spike: *in a layered generation model, `git merge` reports on the inputs, not the outputs, and the
inputs are not what runs.*

**2. Layering converts one upstream change into N downstream edits, where N grows with your history.**
We measured the curve directly: 8 shadow copies at YU03, 16 at YU15. Each state we added made the next
upstream catch-up marginally more expensive. Nobody decided this; it accreted, one `cp -R` at a time,
because copying a whole file into your layer is the fastest way to change one line in it. The debt is
invisible until an upstream change lands on a file you copied.

**3. Cumulative-overlay and no-overlay coexist in the same repo, and the no-overlay part is quieter than
expected.** `generation/runtime-overrides` composes last-wins; `generation/kubernetes` is a per-state
`cp -R` with no composition at all. Going in, the manifests looked like the expensive half. They weren't —
one image tag in eleven files. The *composing* layer was the expensive one, precisely because composition
creates the illusion that a fix at the bottom propagates. **The mechanism that looks safer is the one that
lies to you.**

**4. Upstream re-layers, and in a fork a re-layering is indistinguishable from a feature.** The `OrderBook`
table looked like a new schema addition — a structural finding, the expensive bucket, potentially colliding
with our own YU13 order read model. It was the same DDL moved from the 009 overlay to the 004 overlay.
Catching that took `git log -S` across both revisions. A downstream fork must diff **rendered outputs**, not
spec sources, or it will keep chasing ghosts. (We did render both, which is how the rest of this document
is trustworthy.)

**5. The most valuable thing upstream shipped was a *file format*, not a change.** Upstream spent much of
these seven weeks centralizing every dependency pin into `catalog/dependency-version-targets.json` plus
validation gates. That single file turns our catch-up from "read 62 commits" into "diff one JSON and push
the values down." Their refresh script even generalizes to our layers if you hand it the right roots —
nobody upstream had a reason to, but the tool doesn't care. **Upstream did the most useful thing for its
forks by accident: it published a machine-readable statement of intent.** A version manifest a downstream
can diff is worth more than any number of merge commits, and it is the concrete thing to ask an upstream for.

**6. Being a good fork citizen paid, measurably.** Our collision surface with upstream is **7 files across
304 commits** — because we put essentially everything in our own layers instead of editing upstream's tree.
That discipline is why the conflict set is five files and *the same* five files on every branch — a fork
that had edited upstream's tree would face a different merge fifteen times. It is why a seven-week gap costs
days instead of weeks. It is also the reason the structural bucket is empty: we never fought upstream for ownership of a
file, so upstream never had to fight us back.

**7. What we would tell the next fork.** (a) Never `cp -R` a file into your layer to change one line —
patch it, or you have signed up to hand-carry every upstream change to it, forever. (b) Diff rendered
outputs, never spec sources. (c) Treat a clean merge into a layered tree as *unverified*, not as *done*.
(d) Ask upstream for a machine-readable version manifest; it is worth more than any number of merge commits.

---

## Appendix A — how every number here was produced

```bash
# fork point (2026-06-07) and upstream tip (2026-07-22)
git remote add finos https://github.com/finos/traderX.git && git fetch finos
git merge-base finos/main YU01-lmax-sequencer          # -> de58b8fa
git rev-list --count de58b8fa..finos/main              # -> 62
git merge-base --is-ancestor origin/main finos/main    # -> true (no fork divergence)
git diff --shortstat de58b8fa..finos/main              # -> 116 files, +29904 -23801

# zero service-source changes
git diff --name-only de58b8fa..finos/main \
  | grep -cE '^(account-service|position-service|trade-processor|trade-service|reference-data|people-service|web-front-end|price-publisher|order-matcher|database|ingress)/'   # -> 0

# what WE changed of upstream's
git diff --name-status de58b8fa..YU15-eod-risk-extract -- specs/00\* specs/01\* templates catalog \
  account-service position-service trade-processor trade-service reference-data people-service \
  web-front-end price-publisher database ingress order-matcher                                   # -> 7 files

# git-level conflicts, per branch, without touching the branch
for b in YU02-… YU15-…; do git merge-tree --write-tree --messages finos/main $b | grep -c '^CONFLICT'; done

# genuinely-changed generated paths (index-hash churn stripped) — scratchpad/extract.py
# and the intersection with our 407 override paths                — scratchpad/diffsec.py

# the two-way verification: render the real chain, then md5 + grep the OUTPUT
TRADERX_GENERATED_ROOT=$SCRATCH/gen bash pipeline/generate-state.sh YU02-lmax-kubernetes
md5 -q $SCRATCH/gen/code/target-generated/account-service/build.gradle
md5 -q specs/YU02-lmax-kubernetes/generation/runtime-overrides/account-service/build.gradle   # identical
grep "springframework.boot' version" $SCRATCH/gen/code/target-generated/*/build.gradle       # -> 3.5.14

# the decisive brief-03 check: render the SAME baseline state from both revisions, diff rendered trees
TRADERX_GENERATED_ROOT=$SCRATCH/genours bash pipeline/generate-state.sh 014-fdc3-intent-interoperability
git worktree add --detach $SCRATCH/upstream-wt finos/main
( cd $SCRATCH/upstream-wt && TRADERX_GENERATED_ROOT=$SCRATCH/genup \
    bash pipeline/generate-state.sh 014-fdc3-intent-interoperability )
for s in account-service position-service trade-processor trade-service; do
  diff -rq $SCRATCH/genours/code/target-generated/$s/src \
           $SCRATCH/genup/code/target-generated/$s/src | grep '\.java'   # -> (empty)
done
```

Scratch artifacts (this session only): `scratchpad/{ours.txt,upstream_real.txt,extract.py,diffsec.py}`,
rendered trees under `scratchpad/gen` and `scratchpad/genup`.
