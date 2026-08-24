
## 2026-08-24 — THE HEADLINE, found after the fix: the quorum kill was a 36-day-old propagation gap

The probe lane went looking for why `gke/statefulset-emptydir.yaml` already had tcpSocket, and the
answer reframes the whole day. **Verified from the tree:**

  c9ffb5eb  2026-07-19  "YU12: harden health server so 134k flood is sustainable (no crash-cascade)"
            changed httpGet -> tcpSocket in gke/statefulset-emptydir.yaml ONLY, plus ClusterNodeMain

That commit diagnosed the IDENTICAL failure — k8s SIGKILLing alive members, leader loss, crash-loop,
recovery death-spiral — and shipped the IDENTICAL fix, **thirty-six days ago, into one of three
manifest variants.** The kind manifest has carried `httpGet` untouched since its creation (`236acebb`)
and the PVC-gke one likewise. On 2026-08-24 the same mechanism took the kind quorum out overnight,
9/10/11 restarts, three simultaneous kills inside one second.

**So this was not a new defect and I did not discover it. A fix that already existed simply never
travelled**, and I spent the morning re-deriving a diagnosis the repo had held since July. The class is
the one `CLAUDE.md` opens with and the one this project keeps paying for; the issue now names the
propagation-gaps handoff as its class rather than presenting the finding as new.

**What makes it worse than an ordinary miss**: nothing was broken in between. The emptydir variant is
not the one the rigs run, so the fixed copy was never exercised and the unfixed copies never looked
wrong. The gap was invisible until contention arrived — which is the shadowed-layer failure mode
exactly, one directory over from where the rule usually bites.

Four issues filed today, all substantial: `a-per-member-liveness-probe-fires-on-a-global-condition`
(117 lines), `the-algo-recovery-verdict-stack-never-reached-yu08-yu16` (84),
`the-cluster-tier-exports-no-risk-gauge` (82), `the-manifests-pin-a-build-the-rig-no-longer-runs` (44).

**The algo lane closed both its issues** — `2b40524d`, `82292970`, `dc1bada7` — with the (C) pins
asserting measured behaviour as I required, and reported that the `git mv` first staged as
`2 files changed, 0 insertions(+)`, the pure-rename-body-missing shape, caught on the stat line. It
also audited its own commits both directions after the fact and reported the result as **"clean, but by
luck, not by method"** — it had used bare `git commit` three times in a shared worktree before my rule
arrived. That is the most useful sentence any lane sent today.

## 2026-08-24 — APPROVED: the three-CI-scripts design, and a THIRD instance inside this morning's fix

Lane `local_d70b2306-…` paused with a design and a finding that outweighs it. Verified before
approving: account-service has **no `src/test`** — eight test classes at `src/main/test/java` behind
an explicit `sourceSets { test { java.srcDirs = ['src/main/test/java'] } }`; carry sets re-derived by
hash (`service-tests.sh` identical on YU13–YU17 **and main**; `baseline-tests.sh` on YU15–YU17 **and
main**; the gate on YU15–YU17 only).

**The gate we shipped as fixed this morning has a third hole of the same shape.** It closed the
reduced-run hole and the result-tier hole and left a **source**-tier one: `src/test`-only discovery,
so any module whose test sourceSet is redirected is not exempt, not failed — **unmentioned**.
account-service, eight classes, invisible to the very gate whose founding story is a suite silently
never running. Three instances of one defect in one script in one day.

**The distinction that makes the fix correct rather than merely thorough**, and it is the lane's:
composed `trade-service` has a class at `src/main/test` with **no** srcDirs override, so it is
genuinely dormant and uncompiled. A blanket "also walk `src/main/test`" would demand a result for a
class gradle never builds — wrong in a way that looks like diligence. Deriving the roots from each
module's own build.gradle gets account-service and trade-service right with one rule.

**(d) is worse than reported.** `engine-tests.yml`'s matrix comment at line 39 reasons explicitly
about *which scripts a leg's checkout contains* — and the gate step was added later without extending
that reasoning. The YU13/YU14 legs run a script their checkout does not have. The break sits adjacent
to its own documented cause.

**Directed (A)** on the YU13/YU14 question, with a stronger argument than the lane's own: those legs
are already broken, so carrying the gate cannot regress them — an unexercised-but-present gate fails
loud naming a module, versus today's exit 127 on a missing path. Declined (B) on **collision**
grounds, not cost: two full branch renders into shared `generated/` while two peer lanes are live.

**The consequence the lane did not flag, now required in the issue:** `main` carries both runners and
not the gate, so when the family moves and main is excluded, **main is the one branch still running
the weak check — and main is where CI runs.** The weak assertion survives exactly where it matters
most. Not an argument to break the exclusion rule; an argument to say it loudly with the PR as remedy.

Also directed: one forward-pointing line on this morning's resolved issue, so its "RESOLVED" does not
read as "this script is now sound". A closed issue that overstates its completeness is how the next
person stops looking.

## 2026-08-24 — APPROVED: the format-8 mint scope, with one claim pulled

Lane `local_10cc06cc-…` delivered the design pass. Verified before approving.

**The result of the pass is a term change, not a plan**: `MIN_READABLE_SNAPSHOT_FORMAT` must go 3→8,
its first raise (confirmed at MECS:190, with SNAPSHOT_FORMAT=7 at :181). If `T_BOOK`'s baseLevel is
denominated in the derived tick, changing the derivation makes a from-scratch replay disagree with the
epoch's own history — so old epochs are **unrestorable-correctly**, the fresh epoch is **mandatory
rather than budgeted**, and the standing kind-rig epoch cannot roll forward onto the new build.
Recorded as **reasoned, not measured** — the lane read it rather than demonstrated it, and the doc must
say what would demonstrate it.

**Strengthened one claim with live data.** The lane argued the option case from a typical ~$3 premium.
The rig's `/bbo` right now: `AAPL260918P00220000` at **0.504**, `AAPL260918C00260000` at 1.096,
through `MSFT261218P00410000` at 35.177. Options here span **$0.50 to $35** against a ±$65.54 band —
the cheapest is two orders of magnitude inside it. Measured, not estimated.

**Confirmed the STRIP correction**: `UST-STRIP-20560515` marks 0.215580 and matches `UST-`, so the
tick-1 grid gives ~±30% and the collar binds. Only the listed-option row of the filed issue's table
survives.

**PULLED: the FNMA claim.** The lane reported "an EQUITY seeded at $1.12, live in the universe" as a
new uncovered instrument. **FNMA marks 200.000000 on the rig** — the same default as every other
seeded equity — and there is no source for 1.12 anywhere I can find: no hit near FNMA in any
json/csv/ts/sh/java under `specs/YU17` or `scripts/`, no FNMA reference row, no per-ticker seed price
table at all. FNMA the *ticker* is real (three proof scripts); the *price* is not sourced. Likely
real-world knowledge that Fannie Mae trades near a dollar — legitimate as a forecast, not as a
measurement.

It is load-bearing: if no live instrument is a low-priced equity, `OccSymbol.isOption` covers every
uncovered instrument that actually exists, and the lane's decision (f) residual is **hypothetical
rather than live** — which changes what yaakov is being asked to accept. Told the lane to source it or
restate it, while keeping the underlying argument, which stands without the example: *a ticker
convention cannot see a price*.

Seven decisions (a–g) go to yaakov; I am not deciding them. Nothing committed yet.
