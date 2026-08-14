# Issue: the YU01-lmax-sequencer pack is two unmerged development threads

**Status:** open. Adjudicated 2026-08-03; not yet resolved. Blocks carrying YU01 into `main`.
**Related:** `HANDOFF-issue-spec-layer-propagation-gaps.md` — same family, different shape.

---

## Summary

Every other YU pack has been reconciled: home branch and the YU15 tip now agree entry-for-entry for
YU02 through YU15, and `main` carries those fourteen. **YU01 is the one exception**, and it is not
more of the same problem — it is a different problem.

The other packs diverged because a fix landed on one side and never propagated. YU01 diverged
because **two separate development threads were pursued against the same layer and never merged**:

| | thread | representative files |
|---|---|---|
| **Home branch** (`YU01-lmax-sequencer`) | no-GC / allocation-hardened output path | `OutputValueCache.java`, `AccountTopicCache.java`, `OutputHandlerAllocationGateTest.java`, `OutputHandlerAllocationAttributionTest.java`, `LmaxEndToEndLatencyBenchmarkTest.java`, primitive `OrderSnapshot.java` |
| **YU15 tip** | recovery / snapshot / MariaDB cutover | `JournalReader.java`, `SnapshotStore.java`, `CpuAffinity.java`, `AsyncPublisher.java`, `mariadb-init/initialSchema.sql`, per-service `application.properties` |

Both threads are real work that YU01 should have. Neither side is stale. That is why the correct
answer for ten files is a **merge**, not a pick.

## The adjudication

All 66 differing files were adjudicated from git history, with an adversarial re-check on every
verdict that was not high-confidence (14 re-checked, 2 corrected).

| verdict | count |
|---|---:|
| tip-wins | 33 |
| home-wins | 16 |
| merge-both | 10 |
| either-equivalent (rebase/rename artifact) | 7 |

By category: 45 content-differs, 8 home-only (all home-wins), 13 tip-only (all tip-wins).

### The presumption in the earlier handoff is wrong

The prior guidance was **"presumption home-wins"**, derived from last-commit recency: 31 files newer
on home, 0 newer on the tip. The per-file work contradicts it — the tip wins roughly twice as often
as home (33 vs 16). Recency measured which side was *touched* most recently, not which side
*contains* the other's work. Do not carry that presumption into the fix.

Taking home wholesale would also have dropped all 13 tip-only files, including the entire journal
recovery and snapshot store.

## The ten merges, and why they interlock

These cannot be resolved file-by-file; the adjudication flagged explicit dependencies between them.

- `build.gradle` — tip's mariadb driver + `net.openhft:affinity`; home's `outputLatencyBenchmark` /
  `outputTopologyBenchmark` tasks, `allocationAttribution` sysprop, allocation-gate test filter, 512m
  heap. The driver line only lands together with the tip's `application.properties` and
  `order-management-matcher/docker-compose.yml`.
- `LmaxEngine.java` — base on the tip (recovery + snapshot + no-DB cutover is the larger, later,
  better-documented thread), then re-apply home's `1b2bf842` / `c815545b` output-path changes onto
  the `outputHandlers` list. Keep the tip's ordering rule verbatim: `setSnapshotTrigger` AFTER
  recovery, `startSnapshotScheduler` after `inputDisruptor.start()`.
- `ProjectorHandler.java` — base on the tip; it is the only version whose SQL runs against the
  MariaDB backend every state now uses. Do **not** restore home's Postgres `ON CONFLICT` path.
- `AccountTradeHandler.java`, `PositionUpdateHandler.java`, `TradeSubmitHandler.java`,
  `NatsBridgeHandler.java` — take home's allocation-free bodies, prepend the tip's
  `if (readModel.isReplaying()) return;` as the first statement of `onEvent`.
- `InMemoryOrderReadModel.java` — home's `apply()` and `countUnfilled()` (allocation-free,
  primitive) plus the tip's `replaying` field and its two accessors verbatim.
- `OrderMatcherService.java` — cannot be chosen independently of `OrderSnapshot.java` and
  `LmaxEngine.java`; `listOrders`' filter/sort must follow whichever snapshot shape is chosen.
- `TradeOrderController.java` (trade-service) — shadowed on the tip; corrected from home-wins to
  merge-both on re-check.

## The pack README must come from the tip, or `main`'s docs gate fails

A second file settles itself, and it bites at carry time rather than at build time.
`specs/YU01-lmax-sequencer/README.md` differs on its first line:

```
home  YU01-lmax-sequencer  : # Feature Pack 009b: LMAX Sequencer Architecture (Trading Hot Path)
tip   YU15-eod-risk-extract: # Feature Pack YU01: LMAX Sequencer Architecture (Trading Hot Path)
```

Home still carries the pre-rename `009b` heading. `main`'s
`pipeline/validate-state-doc-consistency.sh` captures the id from that line and requires it to equal
the state id, so home's copy fails (`009b` != `YU01`) while the tip's passes. The adjudication
independently resolved this file to **tip-wins** from git history, which agrees.

Note this is specific to `main`. On the YU branches the same validator still has the un-widened
`^# Feature Pack ([0-9]{3}[a-z]?):` regex and rejects every YU pack heading, so the check has never
accepted a YU state there and this divergence is invisible. `main` carries the widened form
(`[0-9]{3}[a-z]?|YU[0-9]{2}`) and the 14 packs it already holds were normalised to
`# Feature Pack <id>: <Title>` on the way in — the same shape the 13 numbered packs use.

Canonical heading is `# Feature Pack <id>: <Title>`. Keeping the id inside the capture group is what
makes the duplicate-Feature-Pack-number bookkeeping work: on `main` that check now covers 27 of 28
states, against 13 of 28 on the YU branches. Normalising YU02–YU15's headings on the YU branches to
match is the remaining follow-up, and is a propagation pass rather than new authoring.

## YU01 generation is currently broken on the tip — RESOLVED, verified 2026-08-14

**This section is stale and the sub-issue is closed.** `bash pipeline/generate-state.sh
YU01-lmax-sequencer` run from `YU17-otc-rates` exits **0**, and the log shows
`[done] applied 1 patch(es) for YU01-lmax-sequencer`. Home's `20a3d52c` reached every tip branch:
`specs/YU01-lmax-sequencer/generation/patches/0001-state-overlay.patch` is byte-identical
(`51a649ba8a95…`) on `YU01-lmax-sequencer`, `YU15-eod-risk-extract`, `YU16-cdm-instruments` and
`YU17-otc-rates`.

So the "one file settles itself and matters immediately" below is already settled in the tree, and
the merge no longer has to carry it. The rest of this section is kept for the history of why.

## YU01 generation was broken on the tip (history)

Independent of the merge question, one file settles itself and matters immediately:
`generation/patches/0001-state-overlay.patch`.

Home carries `20a3d52c` (2026-07-24): the `finos/main` merge refreshed the generated
`RUN_FROM_GENERATED.md`, so the overlay's exact-context 28-line retitle hunk stopped applying and
**the whole overlay patch failed** — taking 57 unrelated file sections down with it. That commit
drops the one cosmetic doc hunk and leaves the rest intact. It exists on `YU01-lmax-sequencer` only.

The tip still carries the un-dropped hunk, and the tip's own `eceefa36` (2026-07-30) records that
"Generation of that state fails". So `main` must take **home's** copy of this file; taking the tip's
would ship a YU01 that cannot be generated at all.

## The decision: home's primitive `OrderSnapshot` — settled by the spec, not by preference

`OrderSnapshot`'s shape drives nine of the ten merges. It is **not** an open judgement call: YU01's
own `spec.md` — the text is identical on both branches — already requires the answer.

> The position store SHALL be an allocation-free primitive structure (no `HashMap`/autoboxing/
> `BigDecimal`), subject to the no-GC and banned-API gates (NFR-09B02, SC-09B13).

The state's purpose line commits it to "zero steady-state allocation (no-GC)", and it carries a
whole `NGC` requirement namespace (`requirements/no-gc-conformance.md`) plus a user story asking for
CI enforcement under Epsilon GC.

The tip's `OrderSnapshot` is `Integer` ×4, `BigDecimal` ×2, `Instant` ×2, and it is **constructed on
the hot path** — four construction sites in `LmaxEngine` (the BLP thread), plus the output-ring
handlers. That is per-order allocation on the exact path the state exists to make allocation-free.
So the tip's code violates the tip's own spec.

**Why nobody noticed:** the YU01 layer carries **three** allocation-gate tests on home and **one** on
the tip. The tip dropped the gates that would have failed on this. The violation is not absent
there, it is unobserved — the same shape as the YU14 breakage.

### What the merge actually is

Home's `OrderSnapshot` is a **mutable flyweight**: `volatile` primitives, one object per order,
updated in place through `updateFromEvent`, never reallocated (that is what `OutputValueCache` is
for). `limitPx` / `lastExecPx` are `long` fixed-point ticks, matching the spec's "computed in `long`
fixed-point on the hot path … rendered at the edge."

So the work is **not** "de-box the tip's version". It is "express the tip's recovery additions
against home's flyweight". The seam already exists: home's `fromRecord(int, OrderRecord)` /
`toRecord()` is precisely the serialisation hook `SnapshotStore` and `JournalReader` need — very
likely why home has it and the tip does not.

**One detail to settle explicitly:** `BigDecimal limitPrice` can be null (market order); `long
limitPx` cannot. Do not invent a sentinel — the project already has a convention for an absent price
in this same long-ticks representation. See `KdbTapWriterTest.pxNoneRendersAsZeroNotNegative` and the
engine's `PRICE_MISSING` reject path.

### The interlock set is closed at eleven files

Confirmed against both branches. Home's eight — `OrderSnapshot`, `InMemoryOrderReadModel`,
`LmaxEngine`, `OrderMatcherService`, `ProjectorHandler`, `TradeSubmitHandler`,
`AccountTradeHandler`, `OutputValueCache` — plus the tip's `AccountTrade`, `TradeOrder` and
`NatsBridgeHandler`. All eleven live in the YU01 layer; nothing outside it participates, so the
merge does not reach into another state's layer.

(In the composed YU15 tree ten files reference `OrderSnapshot`, including `DbWarmupReader` and
`TradeBlotterHandler` — but those come from later layers and are not part of this merge.)

## Verification the fix needs

The other packs were reconciled with a spec-layer md5 check plus a marker-grep of the rendered tree.
That is not sufficient here, because these are interdependent source merges:

1. `bash pipeline/generate-state.sh YU01-lmax-sequencer` must exit 0.
2. The generated `order-matcher` must compile, `compileJava` and `compileTestJava`.
3. The allocation gates and latency benchmarks must run — they are the reason home's thread exists,
   and a merge that quietly drops them looks green while removing the state's whole point.

YU01's pack is carried by all fifteen branches, so the result propagates to every worktree, and
`main` needs a fifteenth catalog entry, `specs/README.md` line, learning doc, sidebar entry and
getting-started link once it lands.

## Where the working data is

The full per-file verdict list with evidence — commit shas, dates, subjects, and the risk of taking
the wrong side for each of the 66 files — was produced by this adjudication. Re-running it is cheap
if the artifacts are gone: compare `git ls-tree -r YU01-lmax-sequencer specs/YU01-lmax-sequencer`
against the same for `YU15-eod-risk-extract`, and adjudicate each differing entry from
`git log` on both sides.
