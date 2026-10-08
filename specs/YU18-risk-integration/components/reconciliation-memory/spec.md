# Reconciliation memory diagnostic specification

Status: local diagnostic implemented/tested; coordinator review/integration pending.
Owner: RI24 reconciliation-memory lane.

- **FR-RMA01:** Execute complete operative runOrphanSweep against only owned synthetic
  loopback HTTP/read-only mocked repository; require actual paging/output/last-result identity.
- **FR-RMA02:** Observe fullHistoryIds/localIds/allOrphans populations and selected shallow
  graphs at a SHA-pinned temporary hook, saving scalar snapshots without retaining inputs.
- **FR-RMA03:** Walk selected last-result graph and backing storage beyond the reported
  view, identity-deduplicating Strings/arrays. Compare identical bounded-copy output.
- **FR-RMA04:** Record source/instrumented/dependency/class/JVM identities and explicit
  unmanaged/graph/ownership/retention limits. Unknown source/layout/type must refuse.
- **NFR-RMA01:** Bound synthetic sizes/heap/child duration, clean owned HTTP/JVM artifacts,
  use cached tools only and make no production source/config/fix/sizing/rig changes.

Acceptance: empty and no-orphan controls; small/above500 orphan counts and at least two
history sizes; repeated HTTP IDs versus local rows; actual bounded root/storage/String
reachability versus bounded copy; reversible pinned source observer and drift refusal;
real original-style parent-view source negative plus wrong-root/category/nonexecuted refusals.
Run `python3 scripts/diagnostics/reconciliation-memory/test_reconciliation_memory.py`.

Only selected temporary/result shallow graphs are measured, not total allocation,
exclusive/dominator-retained/process/member heap. Managed Trade-row materialization is
not exercised. No generated-module, DB, live-reindex, financial, HA or performance proof.


## Narrow retained-result fix

**FR-RMA05:** Above the existing500 cap, save an independent mutable ArrayList of the
ordered reported prefix; preserve orphan/full-history/local counts, repetitions and <=cap
behavior. No parent list/backing/hidden-ID reference may remain reachable from the saved
result. All sweep membership/scope/sequencing/paging/repository/alerts remain unchanged.

Acceptance: uninstrumented ReconciliationOrphanRetentionTest above-cap/repeat/below/at-cap/
replacement tests plus the updated15 diagnostic checks. Disposable original-style source
must fail retained-backing regressions; immutable original evidence remains unchanged.
