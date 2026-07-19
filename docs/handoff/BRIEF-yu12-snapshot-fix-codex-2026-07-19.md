# Brief for codeX: Phase 1b — snapshot barrier O(n) fix (joint plan)

Assignment from `PLAN-yu12-subsecond-failover-joint-2026-07-19.md` Phase 1b. This is your own
§3.1 design — implement it. fable runs Phase 0 + 1a concurrently and owns the live cluster;
you need NO cluster access for this.

## Worktree discipline (do not skip)

- Do NOT work in `traderX-YU12-aeron-cluster` — it is fable's ACTIVE worktree.
- Branch `YU12-snapshot-fix` off the CURRENT `YU12-aeron-cluster` tip (fable will have
  committed the Phase-0 FIRST_APPLY stamp in `MatchingEngineClusteredService` before you
  start — branch AFTER that commit so the merge is trivial; check `git log --oneline -3`).
- New worktree for it (e.g. `traderX-YU12-snapshot-fix`). Commit there; never push. Deliverable
  = commits + a short handoff note (`docs/handoff/HANDOFF-yu12-snapshot-fix-codex.md`) listing
  the commits, the measured before/after callback cost, and anything you had to decide.
  fable merges and builds the single integrated image.

## Settled (don't relitigate)

- The fix: eliminate the O(terminals x allOrders) scan in
  `specs/YU12-aeron-cluster/generation/runtime-overrides/.../cluster/MatchingEngineClusteredService.java`
  `writeSnapshot` (terminal phase: per-terminal-ref linear scan of `allOrderTuples()`).
  Snapshot record FORMAT and ordering contract are unchanged: open rows first (ring-neutral),
  then terminal rows in exact eviction-FIFO order; restore side untouched.
- Cold path: allocation during snapshot is acceptable; allocation on the apply hot path is NOT
  (clusterAllocationGateTest stays exact-zero).
- Gate: snapshot callback max <= 50 ms at bench-inflated state (build a big state in-test:
  >=100k orders with >=50k terminals — measure before AND after; record both numbers).
- Full re-verification: snapshot completeness matrix tests, zero-tail + snapshot+tail
  recoveries, terminal-eviction FIFO order preserved byte-identically, full suite + noGcTest +
  clusterAllocationGateTest green. Edit via the YU12 runtime-overrides layer + regenerate
  (`bash pipeline/generate-state.sh YU12-aeron-cluster`); never edit generated/ directly.

## Open to your judgment

- Index structure (Int2ObjectHashMap ref->tuple built once from allOrderTuples, vs iterating
  the engine's structures directly, vs a single-pass partition). Simplest thing that meets the
  gate wins; agrona collections are on the classpath.
- Whether to also stream other record families without intermediate lists — only if cheap and
  provably identical output; do not expand scope to §3.2 designs (double-buffer/incremental
  are explicitly parked).

## Anchor

If any tension arises: snapshot bytes identical in content+order to today's output, <= 50 ms
callback at the stated state size, zero new hot-path allocation, matrix green. Speed of the
snapshot is the ONLY intended behavior change.
