# The STP proof crosses a version boundary frozen in July, while the system moves

**Filed 2026-08-22.** **Open — the decision is made, the design is not.**

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
