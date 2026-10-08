# LR-06 — Representative engine and projector memory workloads

Updated: 2026-10-07. Status: queued for bounded local campaign; production sizing deferred. Owner: unassigned validation lane; coordinator maintains queue.

Parent work: [RI-24/13](../24-bounded-runtime-memory.md). This entry tracks acceptance evidence; it is not a separate implementation contract.

## Target and revision

Rig: Owned disposable local engine/projector rig; deployment-profile extension in GKE.

Candidate: Engine/reconciliation diagnostics and bounded-result copy integratedbf1d13e9. Pin the exact tested commit, generated source, image and configuration before execution.

## Existing evidence

18 engine and15 reconciliation selected-graph cases pass. These are partial identity-deduplicated shallow graphs, not exclusive retained heap, full-process RSS or managed JDBC measurement.

## Required live scenario

Run bounded nonempty symbol/order/trade/history gradients through actual engine and SQL projector. Measure heap/RSS/GC, backlog, scoped full-history reconciliation and repeated saved reports. Include managed JDBC path and verify recovery if testing failures.

## Acceptance and failure controls

Pin JVM/runtime/image/limits/input populations. Separate retained/live/temporary/native/fixture memory and sample-window effects; do not infer bytes freed from selected graph subtraction. Counts and successful work must remain observable under pressure. No production limits chosen from a tiny fixture.

## Dependencies

Owned workload driver and bounded profile; production capacity/sizing needs representative deployment measurements.

## Result record

- Claim/worktree/runner: pending.
- Tested revisions, images and rig identity: pending.
- Commands, actual outcomes, artifacts and negative controls: pending.
- Verdict: not executed for this entry.
- Cleanup and remaining qualification: pending.

Keep failed and inconclusive attempts with their evidence. Complete only the scenario actually exercised; record any untested tier separately.
