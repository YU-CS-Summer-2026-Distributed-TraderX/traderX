# The STP proof crosses a version boundary frozen in July, while the system moves

**Filed 2026-08-22.** **RESOLVED 2026-08-23** by reading 3 (a recorded revert patch applied to
today's commit), verified on `kind-traderx-yu12-cluster`. See the bottom of this file.

The coverage this resolution deliberately gave up is NOT closed here — it is tracked in
`issues/open/nothing-proves-recovery-across-a-real-format-and-capacity-gap.md`.

`scripts/proofs/yu13-stp-and-replace.sh` proves the cluster recovers its epoch across a real version
boundary, by rolling members from a pre-change build to a post-change build. Its value is that a real
upgrade does not get to wipe state, so a fresh-epoch bring-up cannot exercise the path an actual
upgrade takes — divergence, snapshot-format mismatch, symbol-table overflow are all invisible to a
clean start.

**The apparatus is two opaque images that no tree can rebuild.** They were built 2026-07-22, almost
certainly from a working tree carrying uncommitted changes: dating them against history puts the only
commit between them at one about `gs://` delivery, which has nothing to do with self-trade prevention.

## What that cost, in one day

- Archaeology to identify the builds — **dead-ended**; the commits are not recoverable.
- An **ASM bytecode graft** to lift `MAX_SECURITIES` 64→1024 inside the binaries, because rebuilding
  from source was not available. Correct, careful work — and it had to disambiguate five inlined
  `bipush 64` sites from `MAX_ACCOUNTS` (also 64) and a `0x40` flag mask.
- A seeding gap that hid for months: the runner's prep seeded through a tip gateway onto historical
  members, so **the stp epoch never carried the fixture universe at all**.
- A false-accusation hazard (`snapshot corrupt: symbol id N` with the pods still `READY`), closed by
  dropping the bare tags.

None of that is the cost of testing an upgrade. **It is the cost of testing an upgrade with artifacts
nobody can rebuild.**

## The design fault

**The boundary is frozen while the system moves.** The pair was recent when the proof was written. It
recedes further into history every week, so the proof exercises an upgrade nobody will ever perform,
while the upgrade that will actually be performed — tip → tip+1 — is untested. The gap it crosses is
now four snapshot formats and a 16× capacity change wide, none of which the bundle under test
introduced.

## DECISION (yaakov, 2026-08-22): go synthesized

Replace the pinned pair with a pair **derived from the repository**, so the boundary is reproducible
and tracks the system instead of receding from it.

**"Synthesized" is not yet unambiguous, and the choice matters. Three readings:**

1. **Build-time flag** — one commit, `pre` built with self-trade prevention disabled. Cheapest.
   **Recommend against:** it puts a switch that disables a safety control into production code, and a
   flag that can turn STP off is a worse liability than the problem being solved.
2. **Commit-pinned rebuild** — record the parent commit of the STP change and build `pre` from it.
   Authentic and reproducible in principle; in practice an old commit may no longer build against
   today's dependencies, which is the same trap the archaeology hit from the other side.
3. **Recorded revert patch (recommended)** — both sides built from **today's** commit, with `pre`
   produced by applying a small patch in the repo that removes the STP hunk at build time. Fully
   reproducible, needs no production flag, and the boundary tracks the system: next month it is
   *next month's* engine with and without STP.

## What must survive the change

- **The falsification arm.** Step 6 fills the identical economics from **two** accounts
  (`[2 2 2] → [4 4 4]`), which is what proves step 5's non-fill is STP and not a dead engine. A
  synthesized pair makes it *easier* to accidentally build two identical images; without step 6 that
  would pass while proving nothing. Keep it, and verify the two images differ before trusting a run.
- **The `pre` side must genuinely lack the behaviour**, not merely have it configured off.
- The proof's other honest limits, already stated in its header (engine trade counter asserted,
  MariaDB reported not asserted; no order read model), are unaffected.

## What is LOST, stated plainly

A synthesized pair proves the **behavioural** boundary. It does **not** prove recovery across a
genuine historical format-and-capacity gap — which is what the pinned pair actually exercised, and
which is where a real long-delayed upgrade gets hurt.

**Nothing else currently covers that.** If it matters, it needs its own proof with deliberately
built, provenance-recorded artifacts — the point being that the artifacts are *built on purpose with
their commit recorded*, which is exactly what the July pair was not. Do not let this issue close
while quietly assuming the old coverage still exists.

## Also fix while in this file (small, same class as today's other work)

- **The header premise has drifted.** It says *"format 3 is unchanged by this bundle, so the cluster
  recovers its epoch across the image change"*. The tip is now format **7**. What makes the roll work
  today is that the **runner** wipes and mints the epoch at format 3, plus the tip's
  `MIN_READABLE_SNAPSHOT_FORMAT = 3`. Format identity and reader tolerance are different guarantees.
- **The `Usage:` line is wrong.** It offers `./yu13-stp-and-replace.sh (needs both images present
  locally)`, but the proof never mints its own epoch — three internal comments say the runner does it.
  Standalone against a tip epoch, `roll_to`'s PVCs-intact roll puts a strict-equality format-3 reader
  in front of a format-7 snapshot: the exact false accusation closed in
  `issues/resolved/the-retired-64-capacity-images-can-still-be-rolled-onto-a-widened-epoch.md`. The
  real precondition is "the runner has minted a fresh epoch on IMAGE_PRE".

## RESOLVED 2026-08-23 — reading 3, a recorded revert patch applied to today's commit

The pair is now built from this tree by `scripts/yu15/build-stp-boundary-images.sh`:

- **`traderx/cluster-node:stp-boundary-fix`** — today's generated order-matcher, untouched.
- **`traderx/cluster-node:stp-boundary-pre`** — the same tree with
  `scripts/yu15/stp-boundary-revert.patch` applied to a throwaway copy.

Both sides go through `scripts/yu15/build-cluster-image.sh`, so they share a gradle invocation, a
Dockerfile and a base image, and the only difference between the binaries is the patch.

**Reading 1 (a build-time flag) was not taken**, for the reason recorded above: a switch that can
turn self-trade prevention off does not belong in production code. **Reading 2 (build `pre` from
the pre-STP commit) was not attempted** — the recommendation was explicitly not to spend long on
it, and reading 3 was implemented directly. Whether an old commit still builds against today's
dependencies is therefore still unknown; if it does, it is the more authentic apparatus.

### What the patch removes, and what it deliberately does not

The behaviour under proof is `cross()`'s decision to **cancel the resting order rather than fill
against it**. The patch removes that branch and the `preventSelfTrade` method it calls, plus the
gateway's `server.createContext("/replace", ...)` registration — the ADR-058 half, which step 3
asserts 404s.

Left in place on purpose: `selfTradesPrevented` and `countSelfTradesPrevented()` (ClusterNodeMain
publishes them as `traderx_stp_cancels`, and on `pre` the counter simply stays 0),
`RiskReason.SELF_TRADE_PREVENTED`, and `handleReplace()` itself. **Scoping to the decision rather
than the bookkeeping was the explicit requirement** and it is what the assertions actually test.

### Where the patch had to apply — the layer trap, confirmed

`MatchingEngine.java` is operative at **`specs/YU16-cdm-instruments`**, not at YU13 where the STP
bundle was introduced; YU13's copy is shadowed on the tip. Verified by diffing every
`specs/*/…/lmax/MatchingEngine.java` against the copy under `generated/`: YU16's is byte-identical,
YU13's differs by 28 lines. `ClusterGatewayMain.java` is operative one layer further on, at
**`specs/YU17-otc-rates`**.

**In the end no spec layer was edited at all.** The patch applies to an rsync'd copy of
`generated/code/target-generated/order-matcher`, which is both simpler and necessary: the YU16
layer must keep STP for every state that ships it. `build-cluster-image.sh` gained one `OM_DIR`
override to build from the copy. The shared generated tree is never patched — a patch left applied
there would ship a no-STP engine under an ordinary tag.

### Verified on the rig

`kind-traderx-yu12-cluster` / `traderx`, from a real `bash scripts/yu15/run-proofs.sh
yu13-stp-and-replace` invocation, all nine steps:

```
=== 1. roll BACK to the pre-change members (traderx/cluster-node:stp-boundary-pre) ===
  members + gateway now on traderx/cluster-node:stp-boundary-pre; book agreed at [0 0]

=== 2. on the pre-change engine a self-cross BOOKS A WASH TRADE ===
  42422 sell -> {"orderRef":7,"kind":1}
  42422 buy  -> {"orderRef":8,"kind":1}
  engine trades: [6 6 6] -> [8 8 8 ]  (a self-trade books BOTH sides)
  book: 0 0 -> 0 0
  MariaDB trades rows for STP002040: 0 -> 2   (read model agrees)

=== 3. on the pre-change gateway /replace does not exist ===
  POST /replace -> 404 <h1>404 Not Found</h1>No context found for request

=== 4. roll FORWARD to the member bundle (traderx/cluster-node:stp-boundary-fix) ===
  waiting for a snapshot barrier on all three members (from [1 1 1])
  snapshot barrier taken; the tail this roll replays holds no pre-change event
  members + gateway now on traderx/cluster-node:stp-boundary-fix; book agreed at [0 0]

=== 5. the SAME self-cross now books nothing and cancels the resting order instead ===
  42422 sell -> {"orderRef":9,"kind":1}
  book with the sell resting: 1 2800181334410752906
  42422 buy  -> {"orderRef":10,"kind":1}
  engine trades: [8 8 8] -> [8 8 8 ]   (must not move)
  book after:  1 1843308958870840166
  traderx_stp_cancels advanced on all three members

=== 6. falsification arm: the identical economics from TWO accounts still fill ===
  engine trades: [8 8 8] -> [10 10 10 ]

=== 7. atomic replace takes effect, under the SAME orderRef ===
  resting sell ref=13 @ 105.0
  POST /replace (qty 5->9, px +5 -> +3) -> 200 {"orderRef":13,"kind":1,"replaced":true}
  book: 2 6315556747874437157 -> 2 8604211451798197550

=== 8. a REJECTED replace leaves the order untouched — the atomicity claim ===
  POST /replace to an out-of-band price -> 422 {"orderRef":13,"kind":2,"replaced":false,"reason":"PRICE_COLLAR"}
  book: 2 8604211451798197550 -> 2 8604211451798197550
  engine trades: [10 10 10] -> [12 12 12 ]   (the survived order fills)

=== 9. no member bounced during the proof ===
```

**The falsification arm survived and matters more now.** Step 6 fills the identical economics from
two accounts and was asserted in the passing run. Two identical images would satisfy every other
step; they cannot satisfy step 2 (which requires the wash trade to BOOK) and would not survive the
class-tree check below.

**The pair is checked at build time, not hoped about.** `build-stp-boundary-images.sh` refuses to
finish unless (a) `preventSelfTrade` and the `/replace` context string are absent from `pre`'s
class files and present in `fix`'s, and (b) every differing class comes from one of the two source
files the patch edits. That is five class files, not two: deleting one line from
`ClusterGatewayMain.java` shifts the `LineNumberTable` of the inner classes compiled from the rest
of that file. Verified with `javap -v` that their bytecode is byte-identical and only the line
tables differ.

**Reproducible.** The build path was run twice from a clean state; all 265 class files in both
images hashed identically across the two runs, and the dependency sets matched. A fresh clone can
do it: the inputs are the tracked patch, the tracked build scripts, and the generated tree.

### A latent bug this surfaced, now fixed

The proof's `SKIP_REGRESSION` path **could not have passed in the supported flow**, and the
synthesized pair exposed it on the first run. The staleness check compared each image's build date
against the *deployed* image's — but the runner mints this proof's epoch **on `IMAGE_PRE`**, so the
deployed image is one of the pair and the check compared the pair against itself. Whichever side
was built second marked the other "stale", and the skip path then ran steps 5-9 against the
deployed build: asserting that self-trade prevention works, against the build with self-trade
prevention removed. It failed correctly — `[FAIL] member 0 booked a self-trade under STP` — because
step 5's assertion is sound.

It was replaced rather than repaired, deliberately. The check now asks the question that actually
matters for a synthesized boundary: **are the images older than the generated source they claim to
be built from?** If so it refuses and names the one command that fixes it. The skip path is gone:
with the pair rebuildable on demand there is no run that legitimately lacks a pre-change image,
only one that has not built it.

### Machinery that became unnecessary and was deliberately LEFT

Both are recorded in the scripts with why, so a later reader does not delete them as dead or trust
them as live:

- **`GW_HISTORICAL_PROBES`** in the proof. It exists because the July gateways predated the probe
  server on 18111; the synthesized pair serves 18111 and `/live` exactly like the tip. It is inert
  and harmless (`/ready` is registered on 18110 as well as the probe port, on every build), and it
  is the only thing that still lets the retired pair be rolled by hand. Removing it is a separate
  act; the comment now says so instead of asserting the false premise.
- **`stp_borrow_gateway`** in the runner. It exists because a tip gateway in front of historical
  members refused every `/seed`; a synthesized `pre` gateway is built from the same tree as the tip.
  Kept, and the seeding is demonstrably healthy through it — 68 securities, 48 instruments enabled.

One comment next to the borrow **was factually wrong after the change and was corrected, not left**:
it claimed the borrowed gateway "carries no `CONTROL_FEED_SUBSCRIBER` at all (verified by javap), so
the hazard is absent, not merely disabled". True of the July builds, false of the synthesized pair.
It is now merely disabled, and that is only sufficient because `set env CONTROL_FEED_SUBSCRIBER=0`
lands and its rollout is awaited *before* the epoch is minted. The comment now says so and warns
against reordering.

### Also fixed in this file, from the list above

- **The header premise** no longer claims "format 3 is unchanged by this bundle". Both synthesized
  sides write `SNAPSHOT_FORMAT` 7, so format identity across the roll is now structural rather than
  a property of two frozen builds.
- **The `Usage:` line** had already been corrected before this work started; it now also names the
  build command as the precondition.

### The old images are NOT deleted

`:yu15-pre-1k`, `:yu15-stp-1k` and the un-grafted `:yu15-pre-orig64` / `:yu15-stp-orig64` all remain
on the host. They are the only surviving evidence of what the boundary was and cannot be re-derived.
Repointing the proof is not disposing of them; retiring them is a separate decision. The bare
`:yu15-pre` / `:yu15-stp` tags stay deleted.

## What this resolution GAVE UP — tracked separately, still open

A synthesized pair proves the **behavioural** boundary and nothing else. It does not prove recovery
across a genuine historical format-and-capacity gap — four snapshot formats and a 16x capacity
change — which is what the pinned pair crossed incidentally on every run, and which is where a real
long-delayed upgrade gets hurt. **Nothing else in the suite covers it.**

That gap is filed as its own open issue and must not be considered closed by this one:
`issues/open/nothing-proves-recovery-across-a-real-format-and-capacity-gap.md`.
