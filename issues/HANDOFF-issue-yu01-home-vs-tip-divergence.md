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

## YU01 generation is currently broken on the tip

Independent of the merge question, one file settles itself and matters immediately:
`generation/patches/0001-state-overlay.patch`.

Home carries `20a3d52c` (2026-07-24): the `finos/main` merge refreshed the generated
`RUN_FROM_GENERATED.md`, so the overlay's exact-context 28-line retitle hunk stopped applying and
**the whole overlay patch failed** — taking 57 unrelated file sections down with it. That commit
drops the one cosmetic doc hunk and leaves the rest intact. It exists on `YU01-lmax-sequencer` only.

The tip still carries the un-dropped hunk, and the tip's own `eceefa36` (2026-07-30) records that
"Generation of that state fails". So `main` must take **home's** copy of this file; taking the tip's
would ship a YU01 that cannot be generated at all.

## The blocking decision

**What shape is `OrderSnapshot`?** Home's is no-GC primitives; the tip's is boxed
`Instant`/`BigDecimal`. `OrderMatcherService.listOrders` and `InMemoryOrderReadModel` both have to
follow whichever is chosen, and `AccountTrade.java` / `InMemoryOrderReadModel.java` /
`AccountTradeHandler.java` move as one unit with it.

This is not a merge mechanic, it is a decision about what YU01 *is*. YU01 is the state whose stated
point is the LMAX sequencer hot path, and the allocation gates are how that claim is currently
tested — so boxing the snapshot is not obviously free. Whoever picks this up should settle that
first and let the other nine merges follow from it.

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
