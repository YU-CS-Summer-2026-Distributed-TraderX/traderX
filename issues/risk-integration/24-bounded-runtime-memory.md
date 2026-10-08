# RI-24 — Venue breadth and projector work need measured memory bounds

Updated: 2026-10-07. Status: in progress (OOM exit and memory accounting integrated; sizing/liveness/recovery remains). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Current finding

The integrated book diagnostic measured 4227280 shallow bytes at 131072 levels on Java21 with compressed references. Projector heap-OOM exit is integrated; liveness and event recovery remain open. Engine and reconciliation diagnostics report selected object graphs, with shared roots and unmeasured heap explicit. Current SQL-backed dedup differs from the historical in-memory-set hypothesis.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [ ] Measure book population/order depth against heap and evaluate bounded/sparse storage without sacrificing apply-path behavior.
- [ ] Measure projector and reconciliation memory using current SQL-backed code, including full-history materialization.
- [ ] Design OOM recovery and liveness so a failed projector cannot remain permanently alive but unusable.
- [ ] Keep throughput/allocation checks meaningful; higher heap alone does not remove unbounded growth.

## Acceptance

- [ ] Heap and allocation measurements record exact runtime configuration, symbols, orders and retained history.
- [ ] Fault tests demonstrate visible OOM/process recovery; restart must not silently lose unrecovered events.

## Original reports

- [a-book-costs-4mb-so-member-heap-caps-venue-breadth.md](../open/a-book-costs-4mb-so-member-heap-caps-venue-breadth.md)
- [trade-processor-limps-through-heap-oom-and-no-probe-fires.md](../open/trade-processor-limps-through-heap-oom-and-no-probe-fires.md)

Next: Measure full-engine/projector growth and choose deployment-profile limits with RI13; define event recovery.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

## Class-window assignment, October 7

Heap-OOM fail-fast subsection assigned to Sol6.1 Medium chat `01a11824-0e8a-7b12-8d08-84cf7a53c705` from accepted a0d6da0b. Bounded local JVM/configuration proof only; memory sizing, liveness and event recovery remain open. Coordinator review/integration pending.

2026-10-07 class-window update: Heap-OOM exit locally reviewed at365f9e14:13 coordinator-run bounded real host JVM/effective config tests pass. Controlled integration pending; no original application/container/live restart or event recovery proof. Broader memory, liveness and durability remain open.

2026-10-07 local book-memory accounting assigned to Sol6.1 Medium chat `01a11848-bde9-7d53-8230-133c1aee23c2`. Bounded current-code/JVM measurements only; no sizing/core/rig changes. Existing OOM-exit milestone remains reviewed/pending integration.

2026-10-07 book-memory accounting locally reviewed at `bd2950d2`:16 independent real Java21 checks pass;44 hashes match. Compressed-reference default131072-level book plus8arrays measures4227280 shallow bytes. Shared external fixture pool separate; retained/full-engine heap and production sizing not measured. Controlled integration pending.

## October 7 integration outcome

Container JVM launchers and the demo env patch now exit on heap OOM while preserving existing options (13 combined checks). The bounded direct-source instrumentation tool passed 16 checks and measured 4227280 book-owned shallow bytes at 131072 levels on Java21/compressed references. Shared fixture orders are separate. Full-engine/projector growth, production sizing, liveness and event recovery remain open.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.


## Evening current-engine component accounting, October 7

Local extension implemented in the same RI24 chat 01a11848-bde9-7d53-8230-133c1aee23c2
from accepted 22d4c906; coordinator review/integration pending. Previous integrated
OOM-exit and standalone book milestones remain intact; RI24 is not closed.

Separate `engine_memory.py` directly compiles the current MatchingEngine/risk/output/
metrics units with existing pinned cached dependencies, then measures one bounded actual
engine empty, after sequenced controls/resting orders, and after a real cross. It reports
engine arrays/index/positions/preallocated entries/books and supplied collaborators as
identity-deduplicated categories, plus explicit metrics/ring-infrastructure exclusions.
Pool-size and capacity controls exercise real allocations; moving orders into books and
terminal retention must not count engine-owned entries again. Unknown graph fields/types
refuse. A qualified first-constructor per-thread allocation window is separate from the
later shallow sums; transient default trigger-array allocation is not retained heap.

Focused direct-source/JVM checks include independent constructor/root inventories,
real omitted-risk/stagedExt/shared-root/unknown-object observers and unchanged 16 book
checks. Exact commands/raw JSON/source/dependency/class/JVM hashes and limits are in local
coordination `review-evidence/ri24-engine-memory-20261007/`. No production/core/config or
rig changes, no full-member/dominator-retained heap/performance/financial claim or sizing
recommendation. Full-engine/projector deployment-profile growth and event recovery remain
separately scoped RI24/RI13 work.


## Evening current orphan-sweep memory diagnostic, October 7

Local diagnosis from accepted 22d4c906 in RI24 chat 01a11848-bde9-7d53-8230-133c1aee23c2;
coordinator review/integration pending. No production fix/configuration or sizing decision.
Previous integrated OOM/book milestones and separate reviewed engine 93c514e0 stay unchanged.

The complete operative YU18 runOrphanSweep executes against a read-only repository proxy
and disposable synthetic loopback HTTP. One SHA-pinned reversible observer hook in a
temporary source copy records populated fullHistoryIds/localIds/allOrphans graphs; the
actual retained result is read from lastOrphanSweep and compared with identical reported
IDs in a disposable bounded copy. No production checkout source changes or live reindex.

Fifteen direct-source/Java 21 checks pass: empty/no-orphan/small/700/1200 orphan profiles,
two history sizes, repeated IDs/rows, paging/output identity, selected graph/storage growth,
source drift/category/wrong-root/nonexecuted refusals and real bounded-copy source control.
With 700 orphans, current output reports 500 IDs yet reaches 700 orphan Strings through
SubList's full backing ArrayList (823 capacity, 200 slots beyond view). Selected last-result
shallow graph 37024 bytes versus 26096 for an identical bounded-copy graph. This proves a
local selected reference-retention path, not exclusive/dominator-retained/process heap
or current production impact. Shared repository Strings and static empty arrays can have
other owners. Managed JDBC Trade-row materialization and total temporary allocation are
unmeasured; no storage/replay/liveness/streaming fix is selected.

Executable tool/docs: scripts/diagnostics/reconciliation-memory; raw commands/outputs/
source+instrumented+dependency+class+JVM hashes/limits in local coordination
review-evidence/ri24-projector-memory-20261008. Full/managed/deployment-profile memory and
production strategy remain separately scoped RI24/RI13 work; RI24 is not closed.

October 7 evening integration: the local milestones above passed coordinator review; delivery-specific historical pending notes are superseded by the final integration record. Broader unchecked deployment, mapping, sizing and recovery work remains open.


## Narrow saved-result retention fix, October 7

Dependent follow-up to reviewed b5f3c7f2 copies only the reported orphan prefix above500
into an independent mutable ArrayList. Orphan/local/history counts, ordered IDs/repetitions,
cap500, <=cap behavior and all paging/scope/detection/alerts remain unchanged. Temporary
fullHistoryIds/localIds/allOrphans materialization is not fixed or bounded by this change.

Current diagnostic pin/expected graph updated honestly; immutable original25-artifact
baseline retained separately. Disposable original-style parent-view source reproduces full
backing and fails current checks. Actual-method copied result reaches500Strings/500slots
rather than700Strings/823capacity for the700-orphan fixture; selected26096shallow bytes,
not exclusive/dominator-retained/full-process heap. Fifteen current diagnostic checks plus
four new and nine existing uninstrumented service cases pass; original service fails the
two new above-cap storage tests as required. Review/integration pending; broaderRI24 remains
open and engine93c514e0 independent. Evidence: review-evidence/ri24-bounded-result-20261008.

Final local outcome: the saved above-cap orphan list is an independent mutable copy of the reported prefix. Total counts, cap, ID order/repetitions and below-cap behavior are unchanged. The diagnostic measures 500 reachable IDs rather than all 700 in its synthetic above-cap case. Full-history materialization, managed JDBC memory, total heap, sizing, liveness and event recovery remain open.
